package com.limelight.binding.video;

/**
 * Frame Warp's lens: what warp.frag takes from the shift MoonBridge.frameWarpShift() gives. With a focal
 * length the picture's centre moves by focal * tan(shift / focal) and the rest of it is turned through k and n,
 * as a camera turning shows it; without one every pixel moves by the shift.
 */
final class FrameWarpLens {
    /** Floats {@link #terms} writes: the centre's shift as picture fractions (x, y), then k (x, y), then n (x, y). */
    static final int TERMS = 6;
    // A turn this far from straight ahead (radians) is clamped, so tan stays finite
    private static final float MAX_ANGLE = 1.4f;

    private FrameWarpLens() {}

    /**
     * Fills {@code out} with the terms for a shift of ({@code shiftX}, {@code shiftY}) picture pixels in a picture of
     * {@code width} by {@code height}, lens focal length {@code focal} (0 or less for none). All with y down.
     */
    static void terms(float shiftX, float shiftY, float width, float height, float focal, float[] out) {
        if (!(focal > 0f)) {
            out[0] = shiftX / width;
            out[1] = shiftY / height;
            out[2] = 0f;
            out[3] = 0f;
            out[4] = 1f;
            out[5] = 1f;
            return;
        }
        float tx = (float) Math.tan(clamp(shiftX / focal));
        float ty = (float) Math.tan(clamp(shiftY / focal));
        out[0] = focal * tx / width;
        out[1] = focal * ty / height;
        out[2] = tx * width / focal;
        out[3] = ty * height / focal;
        out[4] = (float) Math.sqrt(1.0 + ty * ty);
        out[5] = (float) Math.sqrt(1.0 + tx * tx);
    }

    private static float clamp(float angle) {
        return Math.max(-MAX_ANGLE, Math.min(MAX_ANGLE, angle));
    }
}
