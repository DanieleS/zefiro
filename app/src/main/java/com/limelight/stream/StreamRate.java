package com.limelight.stream;

import android.content.res.Resources;

import androidx.annotation.NonNull;

import com.limelight.R;
import com.limelight.binding.video.PerfStats;
import com.limelight.bitrate.BitrateSteps;

/**
 * The stream's measured bitrate, written one way wherever it shows: the companion stats, the Thor
 * sheet's header and the side panel's all read "80 Mbps", in the unit the Bitrate entry and the
 * settings use for the bitrate asked for, so the two can be compared at a glance.
 *
 * <p>The decoder measures kilobytes (of 1024 bytes) a second, across both directions; this turns
 * that into kilobits of 1000, the bitrate's own unit. Pure but for the string, so
 * {@code StreamRateTest} pins the conversion.
 */
public final class StreamRate {

    private StreamRate() {
    }

    /** The measured rate in kilobits a second; meaningless unless {@link PerfStats#hasBandwidth}. */
    public static int kbps(@NonNull PerfStats stats) {
        return kbpsOf(stats.bandwidthKbps);
    }

    /** {@link #kbps(PerfStats)} on the bare number of kilobytes a second. */
    static int kbpsOf(float kilobytesPerSecond) {
        return Math.round(Math.max(0f, kilobytesPerSecond) * 1024f * 8f / 1000f);
    }

    /**
     * "80 Mbps", or "0.7 Mbps" under ten, as the Bitrate entry writes a bitrate; only for stats
     * that {@link PerfStats#hasBandwidth}.
     */
    @NonNull
    public static String mbps(@NonNull Resources res, @NonNull PerfStats stats) {
        return res.getString(R.string.stream_value_mbps,
                BitrateSteps.formatMbps(rounded(kbps(stats)), res.getConfiguration().locale));
    }

    /**
     * A measured rate rounded to what is worth reading: whole Mbps from ten up, tenths below. It
     * moves every second, and a digit that changes every time is noise.
     */
    static int rounded(int kbps) {
        return kbps >= 10_000 ? Math.round(kbps / 1000f) * 1000 : Math.round(kbps / 100f) * 100;
    }
}
