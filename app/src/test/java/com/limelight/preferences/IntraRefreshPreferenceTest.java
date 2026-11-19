package com.limelight.preferences;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

import com.limelight.nvstream.jni.MoonBridge;

import org.junit.Test;

public class IntraRefreshPreferenceTest {
    @Test
    public void offByDefaultWithStableKey() {
        assertEquals("checkbox_intra_refresh", PreferenceConfiguration.INTRA_REFRESH_PREF_STRING);
        assertFalse(PreferenceConfiguration.DEFAULT_INTRA_REFRESH);
        assertFalse(new PreferenceConfiguration().intraRefresh);
    }

    @Test
    public void capabilityBitMatchesProtocol() {
        assertEquals(0x80, MoonBridge.CAPABILITY_INTRA_REFRESH);
    }
}
