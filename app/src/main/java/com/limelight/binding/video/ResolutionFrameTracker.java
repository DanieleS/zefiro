package com.limelight.binding.video;

import java.util.ArrayDeque;

/**
 * Tells when a resolution change has really reached the screen: a frame of exactly the new size,
 * out of the decoder instance that was configured for it, and then shown. A notice from the host,
 * or frames of the old size still on their way through the decoder, never count.
 *
 * <p>The decoder reports each frame it produced with the size it came out at ({@link #decoded}),
 * and each frame it handed to the display ({@link #presented}). Only a frame that was both, and
 * that left the same decoder instance, confirms the change. The instance is what keeps a frame of
 * a decoder that was replaced from confirming for its successor, whose timestamps start over.
 */
final class ResolutionFrameTracker {
    private static final int MAX_TRACKED = 32;

    private static final class Frame {
        final Object decoder;
        final long pts;

        Frame(Object decoder, long pts) {
            this.decoder = decoder;
            this.pts = pts;
        }
    }

    final int width;
    final int height;
    private final ArrayDeque<Frame> frames = new ArrayDeque<>();

    ResolutionFrameTracker(int width, int height) {
        this.width = width;
        this.height = height;
    }

    /** A frame came out of the decoder at this size; only the target size is kept. */
    synchronized void decoded(Object decoder, long pts, int width, int height) {
        if (width != this.width || height != this.height) {
            return;
        }
        // Frames the decoder drops are never presented: keep the memory bounded
        if (frames.size() == MAX_TRACKED) {
            frames.removeFirst();
        }
        frames.addLast(new Frame(decoder, pts));
    }

    /** @return whether this frame was seen at the target size, from this decoder: the change is done */
    synchronized boolean presented(Object decoder, long pts) {
        for (Frame frame : frames) {
            if (frame.decoder == decoder && frame.pts == pts) {
                frames.clear();
                return true;
            }
        }
        return false;
    }
}
