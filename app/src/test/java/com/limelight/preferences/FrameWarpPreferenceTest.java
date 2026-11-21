package com.limelight.preferences;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

import org.junit.Test;

public class FrameWarpPreferenceTest {
    @Test
    public void frameWarpIsOffByDefault() {
        assertFalse(PreferenceConfiguration.DEFAULT_FRAME_WARP);
        assertEquals("checkbox_frame_warp", PreferenceConfiguration.FRAME_WARP_PREF_STRING);
        assertFalse(new PreferenceConfiguration().frameWarp);
    }
}
