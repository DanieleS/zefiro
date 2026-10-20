package com.limelight.stream;

import androidx.annotation.NonNull;

import com.limelight.binding.video.PerfStats;

/**
 * The colour of the HUD's dot: one glance at whether the stream is fine, without reading the
 * numbers beside it.
 *
 * <h3>Why its own rule</h3>
 *
 * The numbers alone do not say what is bad: 30 ms is a great latency over the internet and a
 * worrying one on a home network, 59 FPS is fine on a 60 Hz stream and a disaster on a 120 Hz
 * one. The thresholds below are one fixed reading of them, tuned for a home stream, kept as named
 * constants so they can be argued about in one place. Pure, so {@code StreamHealthTest} pins them
 * without a device.
 *
 * <h3>The rule</h3>
 *
 * The worst of the signals wins:
 *
 * <pre>
 *                 GOOD        WARN          BAD
 *   loss          &lt; 1 %       &gt;= 1 %       &gt;= 5 %
 *   latency       &lt; 30 ms     &gt;= 30 ms     &gt;= 60 ms
 *   fps / target  &gt;= 0.9      &lt; 0.9        &lt; 0.75
 *   poor link     -           always        -
 * </pre>
 *
 * The host's "poor connection" report never makes things worse than WARN on its own: it is the
 * host's opinion of the link, and the numbers are what says how bad it got.
 */
public final class StreamHealth {

    public enum Level {
        GOOD, WARN, BAD
    }

    /** Frames lost by the network, in percent, from which the stream is visibly stuttering. */
    public static final float LOSS_WARN_PERCENT = 1f;
    /** Frames lost by the network, in percent, from which it is barely playable. */
    public static final float LOSS_BAD_PERCENT = 5f;

    /** Network round trip, in ms, from which input starts to feel late. */
    public static final int LATENCY_WARN_MS = 30;
    /** Network round trip, in ms, from which input is clearly late. */
    public static final int LATENCY_BAD_MS = 60;

    /** Below this share of the target frame rate the stream is falling behind. */
    public static final float FPS_WARN_RATIO = 0.9f;
    /** Below this share it is visibly choppy. */
    public static final float FPS_BAD_RATIO = 0.75f;

    private StreamHealth() {
    }

    /**
     * @param stats          one window of the decoder's measurements
     * @param targetFps      the frame rate the stream was asked for; 0 or less skips the frame
     *                       rate rule, for a stream whose target is not known
     * @param poorConnection whether the host last reported the connection as poor
     */
    @NonNull
    public static Level classify(@NonNull PerfStats stats, float targetFps, boolean poorConnection) {
        return classify(stats.networkDropsPercent, stats.networkLatencyMs, stats.totalFps,
                targetFps, poorConnection);
    }

    /** {@link #classify(PerfStats, float, boolean)} on the bare numbers. */
    @NonNull
    public static Level classify(float lossPercent, int latencyMs, float fps, float targetFps,
                                 boolean poorConnection) {
        Level level = poorConnection ? Level.WARN : Level.GOOD;

        // A window with no frames at all divides 0 by 0 for its loss; that says nothing about
        // the network, so it is left to the other signals rather than read as either extreme.
        if (!Float.isNaN(lossPercent)) {
            if (lossPercent >= LOSS_BAD_PERCENT) {
                return Level.BAD;
            }
            if (lossPercent >= LOSS_WARN_PERCENT) {
                level = Level.WARN;
            }
        }

        if (latencyMs >= LATENCY_BAD_MS) {
            return Level.BAD;
        }
        if (latencyMs >= LATENCY_WARN_MS) {
            level = Level.WARN;
        }

        if (targetFps > 0 && !Float.isNaN(fps)) {
            if (fps < targetFps * FPS_BAD_RATIO) {
                return Level.BAD;
            }
            if (fps < targetFps * FPS_WARN_RATIO) {
                level = Level.WARN;
            }
        }

        return level;
    }
}
