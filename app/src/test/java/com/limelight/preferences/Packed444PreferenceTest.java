package com.limelight.preferences;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

import org.junit.Test;

public class Packed444PreferenceTest {
    @Test
    public void experimentalFullChromaIsOffByDefault() {
        assertFalse(PreferenceConfiguration.DEFAULT_PACKED_444);
        assertEquals("checkbox_packed_444", PreferenceConfiguration.PACKED_444_PREF_STRING);
        assertFalse(new PreferenceConfiguration().experimentalPacked444);
    }
}
