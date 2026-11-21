// Host-side tests of frame_warp_mask.h: the HUD mask holder (polled through an injected source in
// place of LiGetFrameWarpMask), the redraw rule, the shader's uniform and the redraw tick.
// Built and run by run.sh.
#include "../../main/jni/frame_warp_mask.h"

#include <cassert>
#include <cmath>
#include <cstdint>
#include <cstring>
#include <vector>

namespace {

// A host's mask as LiGetFrameWarpMask() hands it out
struct FakeMask {
    uint16_t generation = 0;
    uint16_t width = 0;
    uint16_t height = 0;
    std::vector<uint8_t> bits;
    int calls = 0;
    int bitCopies = 0;

    uint32_t operator()(uint16_t* gen, uint16_t* w, uint16_t* h, uint8_t* out, uint32_t capacity)
    {
        calls++;
        *gen = generation;
        *w = width;
        *h = height;
        if (bits.empty()) {
            return 0;
        }
        if (out != nullptr && capacity >= bits.size()) {
            std::memcpy(out, bits.data(), bits.size());
            bitCopies++;
        }
        return uint32_t(bits.size());
    }
};

// width x height cells from a string of '#' (set) and '.', row by row
FakeMask maskOf(uint16_t generation, uint16_t width, uint16_t height, const char* cells)
{
    FakeMask mask;
    mask.generation = generation;
    mask.width = width;
    mask.height = height;
    mask.bits.assign((size_t(width) * height + 7) / 8, 0);
    for (size_t i = 0; i < size_t(width) * height; ++i) {
        if (cells[i] == '#') {
            mask.bits[i >> 3] |= uint8_t(1u << (i & 7));
        }
    }
    return mask;
}

void startsWithoutAMask()
{
    FrameWarpCells cells;
    assert(!cells.any());
    FakeMask none;
    // Nothing from the host and nothing held: no change
    assert(!cells.poll(none));
    assert(!cells.any());
    assert(none.bitCopies == 0);
}

void unpacksBitsToOneBytePerCellRowsFromTheTop()
{
    FrameWarpCells cells;
    // 5x2, the protocol's example: row 0 two clear, one set, two clear; row 1 all set
    FakeMask mask = maskOf(1, 5, 2, "..#..#####");
    assert(cells.poll(mask));
    assert(cells.any() && cells.width == 5 && cells.height == 2 && cells.generation == 1);
    const uint8_t expected[10] = {0, 0, 255, 0, 0, 255, 255, 255, 255, 255};
    assert(cells.cells.size() == 10);
    assert(std::memcmp(cells.cells.data(), expected, 10) == 0);
}

void copiesTheBitsOnlyForANewGeneration()
{
    FrameWarpCells cells;
    FakeMask mask = maskOf(7, 8, 1, "#.#.#.#.");
    assert(cells.poll(mask));
    assert(mask.bitCopies == 1);
    // The same generation: the peek finds it, nothing is copied, nothing changed
    assert(!cells.poll(mask));
    assert(mask.bitCopies == 1 && cells.generation == 7);
    // A newer one, of another size
    mask = maskOf(8, 4, 2, "#..#.##.");
    assert(cells.poll(mask));
    assert(cells.generation == 8 && cells.width == 4 && cells.height == 2);
    assert(cells.cells[0] == 255 && cells.cells[1] == 0 && cells.cells[3] == 255 && cells.cells[6] == 255);
    // Generation 0 is a generation like any other (the host counts from there, and wraps)
    mask = maskOf(0, 4, 2, "#..#.##.");
    assert(cells.poll(mask) && cells.generation == 0 && cells.any());
}

void aClearedMaskDropsTheHeldOne()
{
    FrameWarpCells cells;
    FakeMask mask = maskOf(2, 3, 3, "#........");
    assert(cells.poll(mask));
    FakeMask none;
    none.generation = 3;
    assert(cells.poll(none));
    assert(!cells.any() && cells.generation == FrameWarpCells::kEmpty);
    // Nothing more to tell
    assert(!cells.poll(none));
}

void refusesAMaskLargerThanAnyTheHostSends()
{
    FrameWarpCells cells;
    FakeMask huge;
    huge.generation = 1;
    huge.width = FrameWarpCells::kMaxSide + 1;
    huge.height = 1;
    huge.bits.assign((size_t(huge.width) + 7) / 8, 0xFF);
    assert(!cells.poll(huge));
    assert(!cells.any() && huge.bitCopies == 0);
    // Bits that do not cover the cells it says
    FakeMask odd;
    odd.generation = 1;
    odd.width = 8;
    odd.height = 8;
    odd.bits.assign(3, 0);
    assert(!cells.poll(odd));
    assert(!cells.any());
}

void aMaskThatChangesBetweenTheTwoCallsWaitsForTheNextPoll()
{
    FrameWarpCells cells;
    int call = 0;
    FakeMask first = maskOf(1, 8, 1, "########");
    FakeMask second = maskOf(2, 16, 1, "................");
    auto racing = [&](uint16_t* gen, uint16_t* w, uint16_t* h, uint8_t* out, uint32_t capacity) {
        // The peek sees the small mask; the host sends the larger before the copy
        return call++ == 0 ? first(gen, w, h, out, capacity) : second(gen, w, h, out, capacity);
    };
    assert(!cells.poll(racing));
    assert(!cells.any());
}

void redrawsOnlyAfterAQuarterPixel()
{
    assert(!frameWarpMoved(0.0f, 0.0f, 0.0f, 0.0f));
    assert(!frameWarpMoved(10.0f, 5.0f, 10.2f, 5.0f));
    assert(frameWarpMoved(10.0f, 5.0f, 10.25f, 5.0f));
    // Both axes count together
    assert(frameWarpMoved(0.0f, 0.0f, 0.18f, 0.18f));
    // A shift back to nothing (a fade out, the model unlocking) is a move too
    assert(frameWarpMoved(3.0f, -2.0f, 0.0f, 0.0f));
}

void aPictureDrawnAsItIsHasNoShiftAndAFlatPicturesLens()
{
    // One float more than the block: nothing past it is touched
    float out[kFrameWarpUniformFloats + 1];
    for (float& value : out) {
        value = 7.0f;
    }
    frameWarpStill(out);
    const float expected[kFrameWarpUniformFloats] = {0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 1.0f, 1.0f};
    assert(kFrameWarpUniformFloats == 8);
    assert(std::memcmp(out, expected, sizeof(expected)) == 0);
    assert(out[kFrameWarpUniformFloats] == 7.0f);
}

// The shader's mapping of a position through the block
void sourceOf(const float* block, float px, float py, float* sx, float* sy)
{
    const float qx = px - 0.5f;
    const float qy = py - 0.5f;
    const float depth = std::fmax(1.0f + qx * block[4] + qy * block[5], 0.25f);
    *sx = 0.5f + (qx * block[6] - block[0]) / depth;
    *sy = 0.5f + (qy * block[7] - block[1]) / depth;
}

void aFlatPictureIsSampledAtItsPositionLessTheShift()
{
    float block[kFrameWarpUniformFloats];
    frameWarpUniform(19.2f, -9.6f, 0.0f, 1920.0f, 1080.0f, true, block);
    assert(std::fabs(block[0] - 0.01f) < 1e-6f && std::fabs(block[1] + 0.00888889f) < 1e-6f);
    assert(block[2] == 1.0f && block[3] == 0.0f);
    assert(block[4] == 0.0f && block[5] == 0.0f && block[6] == 1.0f && block[7] == 1.0f);
    float sx, sy;
    sourceOf(block, 0.3f, 0.8f, &sx, &sy);
    assert(std::fabs(sx - 0.29f) < 1e-6f && std::fabs(sy - (0.8f + 0.00888889f)) < 1e-6f);
    // Without a HUD to keep
    frameWarpUniform(19.2f, -9.6f, 0.0f, 1920.0f, 1080.0f, false, block);
    assert(block[2] == 0.0f);
}

void theLensTurnsTheEdgesFartherThanTheCentre()
{
    const float width = 1920.0f;
    const float height = 1080.0f;
    // A 90 degree horizontal field of view
    const float focal = width / 2.0f;
    float block[kFrameWarpUniformFloats];
    frameWarpUniform(96.0f, 0.0f, focal, width, height, false, block);
    // The centre moves by focal * tan(shift / focal) pixels
    const float centre = focal * std::tan(96.0f / focal);
    assert(std::fabs(block[0] * width - centre) < 1e-3f);
    float sx, sy;
    sourceOf(block, 0.5f, 0.5f, &sx, &sy);
    assert(std::fabs((0.5f - sx) * width - centre) < 1e-3f);
    // The left and right edges move by different amounts: the camera turns toward one of them
    float lx, rx;
    sourceOf(block, 0.0f, 0.5f, &lx, &sy);
    sourceOf(block, 1.0f, 0.5f, &rx, &sy);
    const float leftMoved = (0.0f - lx) * width;
    const float rightMoved = (1.0f - rx) * width;
    assert(std::fabs(leftMoved - rightMoved) > 1.0f);
    // Both are near what the camera's turn is for the pixel there, tan-wise
    assert(leftMoved > 0.0f && rightMoved > 0.0f);
}

void theLensApproachesTheFlatShiftAsTheFocalLengthGrows()
{
    float flat[kFrameWarpUniformFloats];
    float lens[kFrameWarpUniformFloats];
    frameWarpUniform(30.0f, -20.0f, 0.0f, 1920.0f, 1080.0f, false, flat);
    frameWarpUniform(30.0f, -20.0f, 1.0e6f, 1920.0f, 1080.0f, false, lens);
    for (float x : {0.0f, 0.25f, 0.9f}) {
        for (float y : {0.0f, 0.5f, 1.0f}) {
            float fx, fy, lx, ly;
            sourceOf(flat, x, y, &fx, &fy);
            sourceOf(lens, x, y, &lx, &ly);
            assert(std::fabs(fx - lx) < 1e-3f && std::fabs(fy - ly) < 1e-3f);
        }
    }
}

void aBadModelStaysFinite()
{
    float block[kFrameWarpUniformFloats];
    // A shift of many focal lengths, a nonsense picture size, a non-finite shift
    frameWarpUniform(1.0e6f, 0.0f, 100.0f, 1920.0f, 1080.0f, false, block);
    for (float value : block) {
        assert(std::isfinite(value));
    }
    frameWarpUniform(5.0f, 5.0f, 100.0f, 0.0f, 0.0f, true, block);
    assert(block[0] == 0.0f && block[2] == 0.0f && block[6] == 1.0f);
    frameWarpUniform(INFINITY, 0.0f, 0.0f, 1920.0f, 1080.0f, true, block);
    assert(block[0] == 0.0f && block[2] == 0.0f);
}

void theTickDrawsOncePerRefreshAndNeverBursts()
{
    FrameWarpTicker tick;
    tick.setPeriod(8333333);
    assert(tick.periodNs == 8333333);
    // A period that is not a display's is ignored
    tick.setPeriod(10);
    tick.setPeriod(10000000000LL);
    assert(tick.periodNs == 8333333);

    const int64_t ms = 1000000;
    tick.presented(100 * ms);
    assert(!tick.due(100 * ms) && !tick.due(108 * ms));
    assert(tick.due(100 * ms + 8333333));
    tick.advance(109 * ms);
    assert(tick.nextNs == 100 * ms + 2 * 8333333);
    assert(!tick.due(110 * ms) && tick.due(117 * ms));
    // A tick taken long after its time does not queue the missed ones
    tick.advance(500 * ms);
    assert(tick.nextNs == 500 * ms + 8333333);
    // A new picture restarts it
    tick.presented(600 * ms);
    assert(tick.nextNs == 600 * ms + 8333333);
}

} // namespace

int main()
{
    startsWithoutAMask();
    unpacksBitsToOneBytePerCellRowsFromTheTop();
    copiesTheBitsOnlyForANewGeneration();
    aClearedMaskDropsTheHeldOne();
    refusesAMaskLargerThanAnyTheHostSends();
    aMaskThatChangesBetweenTheTwoCallsWaitsForTheNextPoll();
    redrawsOnlyAfterAQuarterPixel();
    aPictureDrawnAsItIsHasNoShiftAndAFlatPicturesLens();
    aFlatPictureIsSampledAtItsPositionLessTheShift();
    theLensTurnsTheEdgesFartherThanTheCentre();
    theLensApproachesTheFlatShiftAsTheFocalLengthGrows();
    aBadModelStaysFinite();
    theTickDrawsOncePerRefreshAndNeverBursts();
    return 0;
}
