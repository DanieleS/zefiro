package com.limelight.nvstream;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class StreamResumeLimitTest {
    @Test
    public void allowsThreeResumesInTheWindow() {
        StreamResumeLimit limit = new StreamResumeLimit();
        assertTrue(limit.tryAcquire(1000));
        assertTrue(limit.tryAcquire(2000));
        assertTrue(limit.tryAcquire(3000));
        assertFalse(limit.tryAcquire(4000));
    }

    @Test
    public void oldResumesFallOutOfTheWindow() {
        StreamResumeLimit limit = new StreamResumeLimit();
        assertTrue(limit.tryAcquire(0));
        assertTrue(limit.tryAcquire(1000));
        assertTrue(limit.tryAcquire(2000));
        assertFalse(limit.tryAcquire(StreamResumeLimit.WINDOW_MS - 1));
        assertTrue(limit.tryAcquire(StreamResumeLimit.WINDOW_MS));
    }
}
