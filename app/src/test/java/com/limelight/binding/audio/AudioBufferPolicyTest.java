package com.limelight.binding.audio;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class AudioBufferPolicyTest {
    @Test public void startsAtTheInitialReserveWithinBounds() {
        assertEquals(384, new AudioBufferPolicy(192, 3072, 384, 0, 0).sample(0, 500));
        assertEquals(384, new AudioBufferPolicy(192, 3072, 100, 0, 0).sample(0, 500));
    }

    @Test public void growsAfterUnderrunsUpToEightBursts() {
        AudioBufferPolicy policy = new AudioBufferPolicy(192, 3072, 384, 0, 0);
        assertEquals(576, policy.sample(1, 1000));
        for (int i = 2; i < 20; i++) policy.sample(i, i * 1000L);
        assertEquals(192 * 8, policy.sample(20, 20000));
    }

    @Test public void shrinksAfterThirtyCleanSeconds() {
        AudioBufferPolicy policy = new AudioBufferPolicy(192, 3072, 384, 0, 0);
        assertEquals(576, policy.sample(1, 1000));
        assertEquals(576, policy.sample(1, 30000));
        assertEquals(384, policy.sample(1, 31000));
        assertEquals(384, policy.sample(1, 70000));
    }

    @Test public void sampledAtMostOncePerSecond() {
        AudioBufferPolicy policy = new AudioBufferPolicy(192, 3072, 384, 0, 0);
        assertEquals(384, policy.sample(5, 500));
        assertEquals(576, policy.sample(5, 1000));
    }

    @Test public void appliedValueReplacesTheTarget() {
        AudioBufferPolicy policy = new AudioBufferPolicy(192, 3072, 384, 0, 0);
        policy.applied(480);
        assertEquals(480, policy.sample(0, 500));
        policy.applied(-1);
        assertEquals(480, policy.sample(0, 600));
    }

    @Test public void repeatedStarvationAtTheLimitRequestsFallback() {
        AudioBufferPolicy policy = new AudioBufferPolicy(192, 576, 384, 0, 0);
        assertEquals(576, policy.sample(1, 1000));
        assertFalse(policy.needsFallback());
        for (int i = 2; i <= 4; i++) {
            policy.sample(i * 10, i * 5000L);
            assertEquals(i == 4, policy.needsFallback());
        }
    }

    @Test public void cleanPlaybackAndInvalidCountersClearTheFallbackHistory() {
        AudioBufferPolicy policy = new AudioBufferPolicy(192, 384, 384, 0, 0);
        policy.sample(20, 1000);
        assertFalse("A burst of underruns is one observation", policy.needsFallback());
        policy.sample(21, 2000);
        policy.sample(21, 32000);
        policy.sample(22, 33000);
        assertFalse(policy.needsFallback());
        policy.sample(-1, 34000);
        policy.sample(0, 35000);
        policy.sample(1, 36000);
        assertFalse(policy.needsFallback());
    }

    @Test public void aaudioOnlyForStereoWithoutEffectsAtTheNativeRate() {
        assertTrue(AudioBufferPolicy.useAAudio(36, 2, false, 48000, 48000));
        assertFalse(AudioBufferPolicy.useAAudio(26, 2, false, 48000, 48000));
        assertFalse(AudioBufferPolicy.useAAudio(36, 6, false, 48000, 48000));
        assertFalse(AudioBufferPolicy.useAAudio(36, 2, true, 48000, 48000));
        assertFalse(AudioBufferPolicy.useAAudio(36, 2, false, 48000, 44100));
    }
}
