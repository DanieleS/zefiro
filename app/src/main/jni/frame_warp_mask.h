// Frame Warp for the native PyroWave renderer: what it needs on the CPU side, free of Vulkan types and
// of the library so the host tests can run it (app/src/test/native/frame_warp_mask_test.cpp). The HUD
// mask as the host last sent it, one byte per cell; the uniform's shift and lens terms from the
// library's shift; the rule for drawing a picture again between the host's pictures; and the tick
// that paces those redraws.
#pragma once

#include <algorithm>
#include <cmath>
#include <cstddef>
#include <cstdint>
#include <vector>

// A picture is drawn again between the host's pictures once its shift moved at least this far,
// picture pixels.
constexpr float kFrameWarpRedrawPx = 0.25f;

// Whether a shift of (x, y) picture pixels moved far enough from the one on screen, (drawnX,
// drawnY), to draw the picture again.
inline bool frameWarpMoved(float drawnX, float drawnY, float x, float y)
{
    return std::hypot(x - drawnX, y - drawnY) >= kFrameWarpRedrawPx;
}

// The floats of the shader's FrameWarpShift block (shaders/frame_warp.glsl): vec4 shift, vec4 lens
constexpr size_t kFrameWarpUniformFloats = 8;

// The block for a picture drawn as it is: no shift, and a flat picture's lens terms.
inline void frameWarpStill(float* out)
{
    for (size_t i = 0; i < kFrameWarpUniformFloats; ++i) {
        out[i] = i >= 6 ? 1.0f : 0.0f;
    }
}

// The block for a shift of (shiftX, shiftY) picture pixels of a width x height picture (what
// LiGetFrameWarpShift gives) and the model's focal length in picture pixels (0: no lens), x right and
// y down: xy the shift of the picture's centre as fractions of the picture, z 1 to keep the HUD in
// place, then the lens terms k (xy) and n (zw). The shader samples
//   0.5 + ((p - 0.5) * n - xy) / max(1 + dot(p - 0.5, k), 0.25)
// which for a flat picture is p - shift / size.
inline void frameWarpUniform(float shiftX, float shiftY, float focal, float width, float height, bool hud,
                             float* out)
{
    frameWarpStill(out);
    if (!(width > 0.0f) || !(height > 0.0f) || !std::isfinite(shiftX) || !std::isfinite(shiftY)) {
        return;
    }
    out[2] = hud ? 1.0f : 0.0f;
    if (!(focal > 0.0f)) {
        out[0] = shiftX / width;
        out[1] = shiftY / height;
        return;
    }
    // The host keeps the turn well short of a right angle; this keeps a bad model finite
    constexpr float kMaxAngle = 1.4f;
    const float tx = std::tan(std::clamp(shiftX / focal, -kMaxAngle, kMaxAngle));
    const float ty = std::tan(std::clamp(shiftY / focal, -kMaxAngle, kMaxAngle));
    // How far the picture's centre moves, in pixels, and as fractions
    out[0] = focal * tx / width;
    out[1] = focal * ty / height;
    out[4] = tx * width / focal;
    out[5] = ty * height / focal;
    out[6] = std::sqrt(1.0f + ty * ty);
    out[7] = std::sqrt(1.0f + tx * tx);
}

// The newest HUD mask from the host: one byte per cell (255 where the HUD stays put, 0 elsewhere),
// rows from the top. One render thread polls it.
struct FrameWarpCells {
    // No mask (generation)
    static constexpr int32_t kEmpty = -1;
    // Wider or taller than any mask the host sends (2048 at most), within every Vulkan device's image limit
    static constexpr uint32_t kMaxSide = 4096;

    std::vector<uint8_t> cells;
    uint32_t width = 0;
    uint32_t height = 0;
    int32_t generation = kEmpty;

    // A mask to keep the HUD in place with
    bool any() const { return generation >= 0 && width > 0 && height > 0; }

    // Takes a newer mask from `source`, if there is one; true when the mask changed. `source` is
    // LiGetFrameWarpMask(): (uint16_t* generation, uint16_t* width, uint16_t* height, uint8_t* bits,
    // uint32_t capacityBytes) returning the bytes the mask takes, 0 for none, copying the bits (one per
    // cell, row by row, bit (i & 7) of byte (i >> 3)) only when they fit.
    template <class Source>
    bool poll(Source&& source)
    {
        uint16_t gen = 0;
        uint16_t w = 0;
        uint16_t h = 0;
        // First without the bits: most polls find the mask they have
        uint32_t bytes = source(&gen, &w, &h, nullptr, 0);
        if (bytes == 0 || w == 0 || h == 0) {
            const bool changed = generation != kEmpty;
            generation = kEmpty;
            width = 0;
            height = 0;
            return changed;
        }
        if (w > kMaxSide || h > kMaxSide || bytes != (uint32_t(w) * h + 7) / 8) {
            return false;
        }
        if (generation == int32_t(gen) && width == w && height == h) {
            return false;
        }
        m_Packed.resize(bytes);
        bytes = source(&gen, &w, &h, m_Packed.data(), uint32_t(m_Packed.size()));
        // It changed between the two calls: the next poll takes the newer one
        if (bytes != m_Packed.size() || bytes != (uint32_t(w) * h + 7) / 8 || w == 0 || h == 0) {
            return false;
        }
        cells.resize(size_t(w) * h);
        for (size_t i = 0; i < cells.size(); ++i) {
            cells[i] = ((m_Packed[i >> 3] >> (i & 7)) & 1) != 0 ? 255 : 0;
        }
        generation = int32_t(gen);
        width = w;
        height = h;
        return true;
    }

private:
    std::vector<uint8_t> m_Packed;
};

// When to draw the picture on screen again, one display refresh apart (nanoseconds on any steady
// clock). A new picture restarts it: the redraws follow a refresh after the picture, which was
// presented as soon as it was decoded.
struct FrameWarpTicker {
    int64_t periodNs = 16666667;
    int64_t nextNs = 0;

    void setPeriod(int64_t ns)
    {
        // A period outside 1 ms to 100 ms is not a display's
        if (ns >= 1000000 && ns <= 100000000) {
            periodNs = ns;
        }
    }

    // A picture was presented at nowNs
    void presented(int64_t nowNs) { nextNs = nowNs + periodNs; }

    bool due(int64_t nowNs) const { return nowNs >= nextNs; }

    // The tick at nowNs is taken: the next is a period later, never in the past (a late tick does
    // not make up the ones it missed with a burst of draws)
    void advance(int64_t nowNs)
    {
        nextNs += periodNs;
        if (nextNs <= nowNs) {
            nextNs = nowNs + periodNs;
        }
    }
};
