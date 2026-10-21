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

    /** A window that is fine on every count but the one under test. */
    private static Level classify(float loss, int latencyMs) {
        return StreamHealth.classify(loss, latencyMs, false);
    }

    private static PerfStats window(float fps, float lossPercent, int latencyMs) {
        return new PerfStats(1920, 1080, "c2.test",
                fps, fps, fps,
                lossPercent, 1000f,
                latencyMs, 2, 3f,
                false, 0, 0, 0);
    }

    @Test
    public void aCleanStreamIsGood() {
        assertEquals(Level.GOOD, classify(0f, 8));
    }

    @Test
    public void lossWarnsFromOnePercentAndIsBadFromFive() {
        assertEquals(Level.GOOD, classify(0.99f, 8));
        assertEquals(Level.WARN, classify(1f, 8));
        assertEquals(Level.WARN, classify(4.99f, 8));
        assertEquals(Level.BAD, classify(5f, 8));
    }

    @Test
    public void latencyWarnsFromThirtyAndIsBadFromSixty() {
        assertEquals(Level.GOOD, classify(0f, 29));
        assertEquals(Level.WARN, classify(0f, 30));
        assertEquals(Level.WARN, classify(0f, 59));
        assertEquals(Level.BAD, classify(0f, 60));
    }

    @Test
    public void theWorstSignalWins() {
        assertEquals(Level.BAD, classify(1f, 60));
        assertEquals(Level.BAD, classify(5f, 30));
        assertEquals(Level.WARN, classify(1f, 30));
    }

    @Test
    public void aPoorConnectionIsAtLeastWarn() {
        assertEquals(Level.WARN, StreamHealth.classify(0f, 8, true));
        // ...but never worse on its own, nor does it soften a bad window
        assertEquals(Level.BAD, StreamHealth.classify(5f, 8, true));
    }

    @Test
    public void aWindowWithNoFramesSaysNothingAboutLoss() {
        // 0 lost of 0 sent is NaN percent
        assertEquals(Level.GOOD, classify(Float.NaN, 8));
        assertEquals(Level.WARN, classify(Float.NaN, 30));
    }

    @Test
    public void aStillScreenOnAHealthyLinkIsGood() {
        // The emulator's Remote Monitor session: 18.8 FPS of a 60 FPS stream, nothing lost, 1 ms
        // away. The host only sent what changed; the link is fine.
        assertEquals(Level.GOOD, StreamHealth.classify(window(18.8f, 0f, 1), false));
    }

    @Test
    public void readsTheSameFieldsOffPerfStats() {
        assertEquals(Level.GOOD, StreamHealth.classify(window(60f, 0.5f, 12), false));
        assertEquals(Level.WARN, StreamHealth.classify(window(60f, 0.5f, 12), true));
        assertEquals(Level.BAD, StreamHealth.classify(window(60f, 6f, 12), false));
    }
}
