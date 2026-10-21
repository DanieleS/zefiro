package com.limelight.stream;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

/**
 * The measured rate's one conversion: the decoder's kilobytes (of 1024 bytes) a second into the
 * kilobits (of 1000) the bitrate is chosen in, and the rounding every surface shows it with.
 */
public class StreamRateTest {

    @Test
    public void kilobytesBecomeKilobits() {
        assertEquals(0, StreamRate.kbpsOf(0f));
        // 1 KB/s is 8192 bits a second
        assertEquals(8, StreamRate.kbpsOf(1f));
        // The capture's "82 K/s" was well under a megabit
        assertEquals(672, StreamRate.kbpsOf(82f));
        assertEquals(80_003, StreamRate.kbpsOf(9766f));
    }

    @Test
    public void aNegativeRateIsNone() {
        assertEquals(0, StreamRate.kbpsOf(-1f));
    }

    @Test
    public void roundsToWholeMbpsFromTenAndTenthsBelow() {
        assertEquals(80_000, StreamRate.rounded(80_003));
        assertEquals(10_000, StreamRate.rounded(9_960));
        assertEquals(9_900, StreamRate.rounded(9_940));
        assertEquals(700, StreamRate.rounded(672));
        assertEquals(0, StreamRate.rounded(40));
    }
}
