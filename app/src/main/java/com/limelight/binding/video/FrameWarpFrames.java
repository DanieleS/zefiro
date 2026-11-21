package com.limelight.binding.video;

/**
 * Frame Warp finds the tag of a picture by the host's frame number, which MediaCodec does not carry: the decoder
 * hands back the presentation time each input was queued with. This ring remembers which frame number went in with
 * which time, so the decoded picture can be matched to its tag.
 */
final class FrameWarpFrames {
    static final int UNKNOWN = -1;
    private static final int SLOTS = 256;
    private final long[] ptsUs = new long[SLOTS];
    private final long[] frames = new long[SLOTS];
    private final boolean[] used = new boolean[SLOTS];

    /** Input with presentation time {@code pts} carries host frame {@code frame}; called as it is queued. */
    synchronized void note(long pts, int frame) {
        int slot = slot(pts);
        ptsUs[slot] = pts;
        frames[slot] = frame & 0xFFFFFFFFL;
        used[slot] = true;
    }

    /** The host frame number that went in with {@code pts}, or {@link #UNKNOWN} when it is not (or no longer) known. */
    synchronized long frameFor(long pts) {
        int slot = slot(pts);
        return used[slot] && ptsUs[slot] == pts ? frames[slot] : UNKNOWN;
    }

    synchronized void clear() {
        java.util.Arrays.fill(used, false);
    }

    private static int slot(long pts) {
        return (int) (pts & (SLOTS - 1));
    }
}
