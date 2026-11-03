package com.limelight;

import com.limelight.nvstream.jni.MoonBridge;

/**
 * Changes the resolution of a running stream without ending it, and falls back to a reconnect at
 * the new resolution when that does not work, once.
 *
 * <p>The client asks (a Foundation Sunshine style request, only to a host that said it takes
 * one); the host reconfigures its display and encoder and reports the size of the frames from now
 * on; the decoder restarts at that size and waits for the new picture. The change is done only
 * when a frame of exactly the new size has been shown ({@link #onFramePresented}): a request that
 * was sent, or the host's report, prove nothing. If that frame is not there within
 * {@link #FRAME_CONFIRM_TIMEOUT_MS}, or the request is refused, or the connection ends meanwhile,
 * the stream is reconnected at the new resolution, as a change of resolution on a host without
 * this feature is. One attempt, one fallback: nothing here tries again.
 *
 * <p>What the stream looks like meanwhile (the last frame dimmed under a status card), what the
 * decoder does and what a reconnect is are the {@link Host}'s: this class is the state machine
 * between them. Main thread only; Game hands it the events of other threads.
 */
final class ResolutionSwitchController {
    /** From the request to the first frame of the new size: display, encoder and IDR included. */
    static final long FRAME_CONFIRM_TIMEOUT_MS = 5_000;

    interface Host {
        /** Sends the request; {@link MoonBridge#requestResolutionChange}'s code, 0 for sent. */
        int requestChange(int width, int height);

        /** Starts watching the decoder for a shown frame of this size; see {@link #onFramePresented}. */
        void awaitFrame(int width, int height);

        void cancelFrameWait();

        /** The host reported the new size: the decoder is to be restarted at it. */
        void restartDecoder(int width, int height);

        /**
         * Holds the last frame under a status card. {@code ready} is run, once, when that is
         * done, or when it could not be: the request goes out then.
         */
        void beginTransition(int width, int height, Runnable ready);

        /** The change is confirmed: the card fades out over the new picture. */
        void revealNewStream();

        /** The card goes (the change was cancelled), or says the stream is being restored. */
        void abandonTransition(boolean reconnecting);

        /** The new size is in force: sizes, input mapping and the recommended bitrate follow it. */
        void confirmed(int width, int height);

        /** Reconnects the stream at this size. */
        void reconnect(int width, int height);
    }

    interface Scheduler {
        void postDelayed(Runnable runnable, long delayMs);

        void cancel(Runnable runnable);
    }

    /**
     * Whether a stream of this video format can change its size in place: SDR H.264, HEVC and AV1
     * on a decoder that can restart at a new size. HDR and 10-bit SDR streams and PyroWave
     * reconnect instead, until they have been tried. The one place that decides it, so it can be
     * widened.
     */
    static boolean isLiveSwitchSupported(int videoFormat, boolean decoderCanResize) {
        if (!decoderCanResize) {
            return false;
        }
        if ((videoFormat & (MoonBridge.VIDEO_FORMAT_MASK_PYROWAVE | MoonBridge.VIDEO_FORMAT_MASK_10BIT)) != 0) {
            return false;
        }
        return (videoFormat & (MoonBridge.VIDEO_FORMAT_MASK_H264 | MoonBridge.VIDEO_FORMAT_MASK_H265
                | MoonBridge.VIDEO_FORMAT_MASK_AV1)) != 0;
    }

    /** Whether a size can be asked of the host: even, each side within its limits. */
    static boolean isValidSize(int width, int height) {
        return width >= 256 && height >= 256 && width <= 16384 && height <= 16384
                && width % 2 == 0 && height % 2 == 0;
    }

    private enum State { WAITING, COMPLETE, RECONNECTING, CANCELLED }

    private final class Attempt {
        final int width;
        final int height;
        State state = State.WAITING;
        final Runnable timeout = () -> fallback(this, "no frame of the new size in time");

        Attempt(int width, int height) {
            this.width = width;
            this.height = height;
        }

        boolean waitsFor(int width, int height) {
            return state == State.WAITING && width == this.width && height == this.height;
        }
    }

    private final Host host;
    private final Scheduler scheduler;
    private Attempt current;

    ResolutionSwitchController(Host host, Scheduler scheduler) {
        this.host = host;
        this.scheduler = scheduler;
    }

    /** Whether a change is under way: no second one starts meanwhile. */
    boolean isActive() {
        return current != null && current.state == State.WAITING;
    }

    /**
     * Starts a change of the running stream to this size; the caller has checked that the host
     * takes it and the stream can. False when one is already under way.
     */
    boolean start(int width, int height) {
        if (isActive()) {
            return false;
        }
        final Attempt attempt = new Attempt(width, height);
        current = attempt;
        host.beginTransition(width, height, () -> {
            if (!isWaiting(attempt)) {
                return;
            }
            host.awaitFrame(width, height);
            scheduler.postDelayed(attempt.timeout, FRAME_CONFIRM_TIMEOUT_MS);
            int result = host.requestChange(width, height);
            if (result != 0) {
                fallback(attempt, "request not sent: " + result);
            }
        });
        return true;
    }

    /** The host reported the size of the frames from now on. Not a confirmation. */
    void onHostResolution(int width, int height) {
        Attempt attempt = current;
        if (attempt == null || !attempt.waitsFor(width, height)) {
            // Another size than the one asked, or none asked: the 5 s rule settles it
            return;
        }
        host.restartDecoder(width, height);
    }

    /** A frame of this size has been shown, out of the decoder restarted for it: the change is done. */
    void onFramePresented(int width, int height) {
        Attempt attempt = current;
        if (attempt == null || !attempt.waitsFor(width, height)) {
            return;
        }
        attempt.state = State.COMPLETE;
        current = null;
        scheduler.cancel(attempt.timeout);
        host.cancelFrameWait();
        LimeLog.info("Resolution change confirmed: " + width + "x" + height);
        host.confirmed(width, height);
        host.revealNewStream();
    }

    /**
     * The connection ended. While a change is waiting for its frame the stream is reconnected at
     * the new resolution instead of ending.
     *
     * @return whether that took over, and the caller should leave the end alone
     */
    boolean onConnectionTerminated() {
        Attempt attempt = current;
        if (attempt == null || attempt.state != State.WAITING) {
            return false;
        }
        fallback(attempt, "connection terminated");
        return true;
    }

    /** The stream is going away, or the user's screen left: forget the change. */
    void cancel() {
        Attempt attempt = current;
        if (attempt == null) {
            return;
        }
        current = null;
        if (attempt.state != State.WAITING) {
            return;
        }
        attempt.state = State.CANCELLED;
        scheduler.cancel(attempt.timeout);
        host.cancelFrameWait();
        host.abandonTransition(false);
    }

    private boolean isWaiting(Attempt attempt) {
        return current == attempt && attempt.state == State.WAITING;
    }

    private void fallback(Attempt attempt, String reason) {
        if (!isWaiting(attempt)) {
            return;
        }
        attempt.state = State.RECONNECTING;
        current = null;
        LimeLog.info("Resolution change falling back to a reconnect: " + reason);
        scheduler.cancel(attempt.timeout);
        host.cancelFrameWait();
        host.abandonTransition(true);
        host.reconnect(attempt.width, attempt.height);
    }
}
