package com.limelight.binding.video;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class DecoderRecoveryBackoffTest {
    @Test
    public void recoveriesWithoutAFrameWaitLongerThenGiveUp() {
        DecoderRecoveryBackoff backoff = new DecoderRecoveryBackoff();
        for (long expected : DecoderRecoveryBackoff.DELAYS_MS) {
            assertEquals(expected, backoff.beforeRecovery(0));
        }
        assertEquals(-1, backoff.beforeRecovery(0));
        assertEquals(DecoderRecoveryBackoff.DELAYS_MS.length + 1, backoff.recoveriesWithoutFrame());
    }

    @Test
    public void aFrameInBetweenStartsTheCountOver() {
        DecoderRecoveryBackoff backoff = new DecoderRecoveryBackoff();
        assertEquals(0, backoff.beforeRecovery(0));
        assertEquals(250, backoff.beforeRecovery(0));
        assertEquals(0, backoff.beforeRecovery(5));
        assertEquals(1, backoff.recoveriesWithoutFrame());
        assertEquals(250, backoff.beforeRecovery(5));
    }
}
