package com.limelight.utils;

import android.os.Build;
import android.view.Display;

import com.limelight.LimeLog;

/**
 * The luminance of the screen a stream is shown on, as the three /launch and /resume query
 * parameters of a host that announces ClientLuminanceSupported (nits, decimal point whatever the
 * locale). A report is all or nothing: {@link #compute} returns null when the values cannot make a
 * valid set, and then nothing is sent.
 */
public final class HdrBrightness {
    public static final float MIN_PEAK = 1f, MAX_PEAK = 10000f;
    public static final float MAX_BLACK = 100f;
    public static final int MIN_OVERRIDE_NITS = 300, MAX_OVERRIDE_NITS = 4000;

    /** SDR white assumed when the peak is estimated from the HDR/SDR ratio. */
    static final float ASSUMED_SDR_WHITE_NITS = 300f;
    static final float DEFAULT_BLACK = 0.001f;

    public final float maxNits;
    public final float minNits;
    public final float maxAverageNits;
    /** The peak came from the HDR/SDR ratio, not from the screen's declaration. */
    public final boolean estimated;
    /** The peak is the user's manual value. */
    public final boolean overridden;

    private HdrBrightness(float max, float min, float avg, boolean estimated, boolean overridden) {
        this.maxNits = max;
        this.minNits = min;
        this.maxAverageNits = avg;
        this.estimated = estimated;
        this.overridden = overridden;
    }

    /** The query string tail, starting with '&'. Float.toString never uses the locale. */
    public String toQuery() {
        return "&minBrightness=" + minNits +
                "&maxBrightness=" + maxNits +
                "&maxAverageBrightness=" + maxAverageNits;
    }

    @Override
    public String toString() {
        return "max " + maxNits + " / avg " + maxAverageNits + " / min " + minNits + " nits" +
                (overridden ? " (manual)" : estimated ? " (estimated)" : " (declared)");
    }

    private static boolean valid(float max, float min, float avg) {
        return isFinite(max) && isFinite(min) && isFinite(avg)
                && max >= MIN_PEAK && max <= MAX_PEAK
                && min >= 0f && min <= MAX_BLACK
                && avg >= MIN_PEAK && avg <= max
                && min <= avg;
    }

    private static boolean isFinite(float v) {
        return !Float.isNaN(v) && !Float.isInfinite(v);
    }

    private static float clamp(float v, float lo, float hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    private static boolean peakUsable(float peak) {
        return isFinite(peak) && peak >= MIN_PEAK && peak <= MAX_PEAK;
    }

    /**
     * @param peak            declared desired max luminance (anything unusable means missing)
     * @param black           declared desired min luminance (negative or not finite means missing)
     * @param average         declared desired max average luminance (not positive means missing)
     * @param hdrSdrRatio     usable HDR/SDR headroom ratio, or 0 when the system has none
     * @param overrideNits    the user's manual peak in nits, or 0 for none
     * @return the report to send, or null to send nothing
     */
    public static HdrBrightness compute(float peak, float black, float average,
                                        float hdrSdrRatio, int overrideNits) {
        boolean blackKnown = isFinite(black) && black >= 0f && black <= MAX_BLACK;
        boolean averageKnown = isFinite(average) && average > 0f;

        if (overrideNits > 0) {
            float p = clamp(overrideNits, MIN_OVERRIDE_NITS, MAX_OVERRIDE_NITS);
            float avg = clamp(0.2f * p, 100f, 1000f);
            float min = blackKnown ? black : 0f;
            return valid(p, min, avg) ? new HdrBrightness(p, min, avg, false, true) : null;
        }

        if (peakUsable(peak)) {
            // Declared as is; a missing or contradicting part means no report, not a guess
            if (blackKnown && averageKnown && valid(peak, black, average)) {
                return new HdrBrightness(peak, black, average, false, false);
            }
            return null;
        }

        // The peak itself is missing: estimate it from the HDR/SDR ratio, when there is one
        if (!(isFinite(hdrSdrRatio) && hdrSdrRatio > 1f)) {
            return null;
        }
        float estimate = ASSUMED_SDR_WHITE_NITS * hdrSdrRatio;
        if (!peakUsable(estimate)) {
            return null;
        }
        float min = blackKnown ? black : DEFAULT_BLACK;
        float avg = averageKnown ? average : Math.max(min, 0.2f * estimate);
        float p = Math.max(estimate, averageKnown ? average : 0f);
        return valid(p, min, avg) ? new HdrBrightness(p, min, avg, true, false) : null;
    }

    /** What the system declares for a display, for the log and the query. */
    public static HdrBrightness fromDisplay(Display display, int overrideNits) {
        float peak = -1f, black = -1f, average = -1f;
        float ratio = 0f;
        if (display != null) {
            try {
                Display.HdrCapabilities caps = display.getHdrCapabilities();
                if (caps != null) {
                    peak = caps.getDesiredMaxLuminance();
                    black = caps.getDesiredMinLuminance();
                    average = caps.getDesiredMaxAverageLuminance();
                }
            } catch (Throwable t) {
                LimeLog.warning("HDR capabilities unavailable: " + t);
            }
            ratio = readRatio(display);
        }
        HdrBrightness report = compute(peak, black, average, ratio, overrideNits);
        LimeLog.info("HDR luminance: declared max/min/avg " + peak + "/" + black + "/" + average +
                ", ratio " + ratio + ", override " + overrideNits + " -> " +
                (report == null ? "nothing sent" : report));
        return report;
    }

    /** The larger usable HDR/SDR ratio of the display (Android 14+), or 0. */
    private static float readRatio(Display display) {
        float best = 0f;
        if (Build.VERSION.SDK_INT < 34) {
            return best;
        }
        try {
            if (display.isHdrSdrRatioAvailable()) {
                float r = display.getHdrSdrRatio();
                if (isFinite(r) && r > 1f) {
                    best = r;
                }
            }
        } catch (Throwable t) {
            LimeLog.warning("HDR/SDR ratio unavailable: " + t);
        }
        if (Build.VERSION.SDK_INT >= 36) {
            try {
                Object r = Display.class.getMethod("getHighestHdrSdrRatio").invoke(display);
                if (r instanceof Float && isFinite((Float) r) && (Float) r > best) {
                    best = (Float) r;
                }
            } catch (Throwable ignored) {
                // Not present on this build of Android 16
            }
        }
        return best;
    }
}
