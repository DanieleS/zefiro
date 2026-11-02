package com.limelight.utils;

import org.junit.Test;

import java.util.Locale;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class HdrBrightnessTest {
    @Test
    public void declaredValuesGoAsTheyAre() {
        HdrBrightness r = HdrBrightness.compute(1000f, 0.001f, 600f, 0f, 0);
        assertNotNull(r);
        assertFalse(r.estimated);
        assertEquals("&minBrightness=0.001&maxBrightness=1000.0&maxAverageBrightness=600.0", r.toQuery());
    }

    @Test
    public void zeroBlackIsValid() {
        HdrBrightness r = HdrBrightness.compute(800f, 0f, 500f, 0f, 0);
        assertNotNull(r);
        assertEquals(0f, r.minNits, 0f);
    }

    @Test
    public void ratioNeverOverridesAValidPeak() {
        HdrBrightness r = HdrBrightness.compute(400f, 0.001f, 300f, 5f, 0);
        assertEquals(400f, r.maxNits, 0f);
        assertFalse(r.estimated);
    }

    @Test
    public void validPeakWithMissingOrContradictingPartsSendsNothing() {
        assertNull(HdrBrightness.compute(1000f, -1f, 600f, 5f, 0));
        assertNull(HdrBrightness.compute(1000f, 0.001f, 0f, 5f, 0));
        assertNull(HdrBrightness.compute(1000f, 0.001f, 1500f, 5f, 0));
        assertNull(HdrBrightness.compute(1000f, 150f, 600f, 5f, 0));
    }

    @Test
    public void missingPeakIsEstimatedFromTheRatio() {
        HdrBrightness r = HdrBrightness.compute(0f, -1f, -1f, 5f, 0);
        assertNotNull(r);
        assertTrue(r.estimated);
        assertEquals(1500f, r.maxNits, 0.01f);
        assertEquals(0.001f, r.minNits, 0f);
        assertEquals(300f, r.maxAverageNits, 0.01f);
    }

    @Test
    public void estimateKeepsKnownValues() {
        HdrBrightness r = HdrBrightness.compute(-1f, 0f, 2000f, 5f, 0);
        assertEquals(2000f, r.maxNits, 0f);
        assertEquals(2000f, r.maxAverageNits, 0f);
        assertEquals(0f, r.minNits, 0f);
    }

    @Test
    public void noUsableRatioMeansNothing() {
        assertNull(HdrBrightness.compute(0f, -1f, -1f, 0f, 0));
        assertNull(HdrBrightness.compute(0f, -1f, -1f, 1f, 0));
        assertNull(HdrBrightness.compute(0f, -1f, -1f, Float.NaN, 0));
        assertNull(HdrBrightness.compute(0f, -1f, -1f, 100f, 0));
    }

    @Test
    public void overrideWinsAndIsClamped() {
        HdrBrightness r = HdrBrightness.compute(400f, 0.01f, 300f, 5f, 1000);
        assertTrue(r.overridden);
        assertEquals(1000f, r.maxNits, 0f);
        assertEquals(200f, r.maxAverageNits, 0f);
        assertEquals(0.01f, r.minNits, 0f);
        assertEquals(300f, HdrBrightness.compute(0f, -1f, -1f, 0f, 10).maxNits, 0f);
        assertEquals(4000f, HdrBrightness.compute(0f, -1f, -1f, 0f, 9000).maxNits, 0f);
        assertEquals(1000f, HdrBrightness.compute(0f, -1f, -1f, 0f, 9000).maxAverageNits, 0f);
        assertEquals(0f, HdrBrightness.compute(0f, -1f, -1f, 0f, 300).minNits, 0f);
        assertEquals(100f, HdrBrightness.compute(0f, -1f, -1f, 0f, 300).maxAverageNits, 0f);
    }

    @Test
    public void queryUsesADecimalPointWhateverTheLocale() {
        Locale old = Locale.getDefault();
        try {
            Locale.setDefault(Locale.GERMANY);
            assertEquals("&minBrightness=0.001&maxBrightness=1000.0&maxAverageBrightness=600.0",
                    HdrBrightness.compute(1000f, 0.001f, 600f, 0f, 0).toQuery());
        } finally {
            Locale.setDefault(old);
        }
    }
}
