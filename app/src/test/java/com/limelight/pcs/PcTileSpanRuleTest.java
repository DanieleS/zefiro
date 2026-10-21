package com.limelight.pcs;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

/**
 * The PCs grid's columns by width: two on a phone, then one per 220dp within three to five, four
 * on the Thor's top screen.
 */
public class PcTileSpanRuleTest {

    @Test
    public void aPhoneGetsTwo() {
        assertEquals(2, PcTileSpanRule.spans(360));
        assertEquals(2, PcTileSpanRule.spans(599));
    }

    @Test
    public void aWideGridStartsAtThree() {
        assertEquals(3, PcTileSpanRule.spans(600));
    }

    @Test
    public void theThorGetsFour() {
        // The top screen beside the rail: about 900dp.
        assertEquals(4, PcTileSpanRule.spans(900));
    }

    @Test
    public void wideWindowsStopAtFive() {
        assertEquals(5, PcTileSpanRule.spans(1400));
        assertEquals(5, PcTileSpanRule.spans(2400));
    }

    @Test
    public void anUnmeasuredGridIsAPhone() {
        assertEquals(2, PcTileSpanRule.spans(0));
    }
}
