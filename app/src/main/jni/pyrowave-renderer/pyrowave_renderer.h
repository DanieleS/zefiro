// PyroWave renderer for Zefiro (arm64-v8a only).
//
// PyroWave (docs/pyrowave-protocol.md in Vibepollo) is an intra-only GPU
// wavelet codec. MediaCodec cannot decode it, so this module
// decodes each frame with PyroWave on Vulkan and presents it in a Vulkan
// swapchain on the stream's Surface. Decode units reach it straight from
// callbacks.c, without crossing into Java. The JNI entry points live in
// simplejni.c (moonlight-core hides the symbols of its static libraries).
#pragma once

#include <Limelight.h>

#include <android/native_window.h>
#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

// Whether this device can decode PyroWave: a Vulkan 1.3 GPU on which a
// PyroWave decoder can be created. Probed once (creates a Vulkan device).
int PyroWaveRendererIsSupported(void);

// Starts a renderer presenting to window, which it takes ownership of (it is
// released even on failure). Returns 0 on success.
int PyroWaveRendererStart(ANativeWindow* window, int videoFormat, int width, int height,
                          int colorspace, int colorRange);

// Stops and destroys the renderer, if any.
void PyroWaveRendererStop(void);

// Frame Warp (LiGetFrameWarpShift in Limelight.h) for the running renderer: draws each picture shifted
// by the turn the host has not drawn yet, and draws the last picture again once per display refresh
// while the shift moves. Call it after PyroWaveRendererStart, once the stream offers Frame Warp, from
// any thread; idempotent. Returns 1 when it is on, 0 when it cannot be (no running renderer, the device
// cannot, or this build has no warp shader: see PYROWAVE_WARP_SPV in shaders_spv.h): the stream then
// runs unshifted. Without it the renderer is exactly what it was.
int PyroWaveRendererSetFrameWarp(void);

// The display's refresh rate, Hz, for the redraws Frame Warp does between pictures (60 until told).
// Optional; no effect without a running renderer.
void PyroWaveRendererSetRefreshRate(float hz);

// One window of the running renderer's stats, for the stats overlay.
typedef struct _PYROWAVE_RENDERER_STATS {
    uint64_t received;          // frames that reached the renderer
    uint64_t presented;         // frames decoded and presented
    uint64_t dropped;           // frames that arrived but could not be decoded
    uint64_t partial;           // frames decoded with packets missing
    uint64_t lost;              // frames that never arrived (gaps in the frame numbers)
    uint64_t hostLatencyFrames; // frames that carried a host processing latency
    uint64_t hostLatencyTotal;  // their sum, in tenths of a ms
    uint32_t hostLatencyMin;    // tenths of a ms
    uint32_t hostLatencyMax;    // tenths of a ms
    double gpuDecodeMs;         // PyroWave's GPU time per frame, negative before the first sample
    char gpuName[256];
} PYROWAVE_RENDERER_STATS;

// The stats since the previous call (or the start), and a new window from here.
// Returns 0, or -1 without a running renderer.
int PyroWaveRendererTakeStats(PYROWAVE_RENDERER_STATS* stats);

// Hands one frame to the renderer. Copies what it needs and returns at once:
// decoding happens on the renderer's own thread, and a frame still waiting
// when a newer one arrives is dropped (every PyroWave frame is independent).
// Without a running renderer (stopped, or never started) the frame is dropped.
int PyroWaveRendererSubmitDecodeUnit(PDECODE_UNIT decodeUnit);

#ifdef __cplusplus
}
#endif
