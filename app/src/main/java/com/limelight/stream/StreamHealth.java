package com.limelight.stream;

import androidx.annotation.NonNull;

import com.limelight.binding.video.PerfStats;

/**
 * The colour of the HUD's dot, and of the companion strip's: one glance at whether the connection
 * is fine, without reading the numbers beside it. Green while it is, amber or red only when the
 * stream is degraded.
 *
 * <h3>Why its own rule</h3>
 *
 * The numbers alone do not say what is bad: 30 ms is a great latency over the internet and a
 * worrying one on a home network. The thresholds below are one fixed reading of them, tuned for a
 * home stream, kept as named constants so they can be argued about in one place. Pure, so
 * {@code StreamHealthTest} pins them without a device.
 *
 * <h3>The rule</h3>
 *
 * The worst of the signals wins:
 *
 * <pre>
 *                 GOOD        WARN          BAD
 *   loss          &lt; 1 %       &gt;= 1 %       &gt;= 5 %
 *   latency       &lt; 30 ms     &gt;= 30 ms     &gt;= 60 ms
 *   poor link     -           always        -
 * </pre>
 *
 * The host's "poor connection" report never makes things worse than WARN on its own: it is the
 * host's opinion of the link, and the numbers are what says how bad it got.
 *
 * <h3>Why not the frame rate</h3>
 *
 * A host sends a frame only when the picture changes, so a still desktop or a paused game streams
 * at a fraction of the rate asked for over a perfect link: judged against the target, the dot sat
 * red on a healthy Remote Monitor session. From the client a quiet screen and a struggling
 * encoder look the same, so the frame rate is left to the numbers beside the dot; frames the
 * network did lose are the loss above, and a host short of bandwidth says so as a poor link.
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

    private StreamHealth() {
    }

    /**
     * @param stats          one window of the decoder's measurements
     * @param poorConnection whether the host last reported the connection as poor
     */
    @NonNull
    public static Level classify(@NonNull PerfStats stats, boolean poorConnection) {
        return classify(stats.networkDropsPercent, stats.networkLatencyMs, poorConnection);
    }

    /** {@link #classify(PerfStats, boolean)} on the bare numbers. */
    @NonNull
    public static Level classify(float lossPercent, int latencyMs, boolean poorConnection) {
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

        return level;
    }
}
