package com.limelight.stream;

import com.limelight.binding.video.PerfStats;
import com.limelight.stream.StreamHealth.Level;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

/**
 * The HUD's dot against its thresholds: each signal on both sides of each edge, the worst signal
 * winning, the host's poor-connection report as a floor of WARN, and the inputs a real window can
 * produce that are not numbers.
 */
public class StreamHealthTest {

    private static final float TARGET = 60f;

    /** A window that is fine on every count but the one under test. */
    private static Level classify(float loss, int latencyMs, float fps) {
        return StreamHealth.classify(loss, latencyMs, fps, TARGET, false);
    }

    @Test
    public void aCleanStreamIsGood() {
        assertEquals(Level.GOOD, classify(0f, 8, 60f));
    }

    @Test
    public void lossWarnsFromOnePercentAndIsBadFromFive() {
        assertEquals(Level.GOOD, classify(0.99f, 8, 60f));
        assertEquals(Level.WARN, classify(1f, 8, 60f));
        assertEquals(Level.WARN, classify(4.99f, 8, 60f));
        assertEquals(Level.BAD, classify(5f, 8, 60f));
    }

    @Test
    public void latencyWarnsFromThirtyAndIsBadFromSixty() {
        assertEquals(Level.GOOD, classify(0f, 29, 60f));
        assertEquals(Level.WARN, classify(0f, 30, 60f));
        assertEquals(Level.WARN, classify(0f, 59, 60f));
        assertEquals(Level.BAD, classify(0f, 60, 60f));
    }

    @Test
    public void frameRateWarnsBelowNinetyPercentAndIsBadBelowSeventyFive() {
        // 0.9 x 60 = 54, 0.75 x 60 = 45: the edges themselves still pass
        assertEquals(Level.GOOD, classify(0f, 8, 54f));
        assertEquals(Level.WARN, classify(0f, 8, 53.9f));
        assertEquals(Level.WARN, classify(0f, 8, 45f));
        assertEquals(Level.BAD, classify(0f, 8, 44.9f));
    }

    @Test
    public void frameRateIsJudgedAgainstTheTarget() {
        // 100 FPS is plenty for 60 and a stutter for 120
        assertEquals(Level.GOOD, StreamHealth.classify(0f, 8, 100f, 60f, false));
        assertEquals(Level.WARN, StreamHealth.classify(0f, 8, 100f, 120f, false));
    }

    @Test
    public void anUnknownTargetLeavesTheFrameRateOut() {
        assertEquals(Level.GOOD, StreamHealth.classify(0f, 8, 1f, 0f, false));
    }

    @Test
    public void theWorstSignalWins() {
        assertEquals(Level.BAD, classify(1f, 60, 60f));
        assertEquals(Level.BAD, classify(5f, 30, 54f));
        assertEquals(Level.BAD, classify(0f, 30, 40f));
        assertEquals(Level.WARN, classify(1f, 30, 50f));
    }

    @Test
    public void aPoorConnectionIsAtLeastWarn() {
        assertEquals(Level.WARN, StreamHealth.classify(0f, 8, 60f, TARGET, true));
        // ...but never worse on its own, nor does it soften a bad window
        assertEquals(Level.BAD, StreamHealth.classify(5f, 8, 60f, TARGET, true));
    }

    @Test
    public void aWindowWithNoFramesSaysNothingAboutLoss() {
        // 0 lost of 0 sent is NaN percent
        assertEquals(Level.GOOD, classify(Float.NaN, 8, 60f));
        assertEquals(Level.WARN, classify(Float.NaN, 30, 60f));
    }

    @Test
    public void aNaNFrameRateIsIgnored() {
        assertEquals(Level.GOOD, classify(0f, 8, Float.NaN));
    }

    @Test
    public void readsTheSameFieldsOffPerfStats() {
        PerfStats stats = new PerfStats(1920, 1080, "c2.test",
                44f, 44f, 44f,
                0.5f, 1000f,
                12, 2, 3f,
                false, 0, 0, 0);
        assertEquals(Level.BAD, StreamHealth.classify(stats, TARGET, false));
        assertEquals(Level.GOOD, StreamHealth.classify(stats, 0f, false));
        assertEquals(Level.WARN, StreamHealth.classify(stats, 0f, true));
    }
}
