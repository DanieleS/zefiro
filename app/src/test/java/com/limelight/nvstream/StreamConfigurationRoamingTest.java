package com.limelight.nvstream;

import static org.junit.Assert.assertEquals;

import com.limelight.nvstream.jni.MoonBridge;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

// StreamConfiguration touches MoonBridge, whose static initializer loads the native library
@Config(sdk = {33}, shadows = {com.limelight.shadows.ShadowMoonBridge.class})
@RunWith(RobolectricTestRunner.class)
public class StreamConfigurationRoamingTest {
    private static int flags(boolean enabled, boolean allowMetered) {
        return new StreamConfiguration.Builder().setClientRoaming(enabled, allowMetered).build().getClientRoaming();
    }

    @Test public void offByDefaultAndWhenDisabled() {
        assertEquals(0, new StreamConfiguration.Builder().build().getClientRoaming());
        assertEquals(0, flags(false, false));
        // Mobile data without roaming means nothing
        assertEquals(0, flags(false, true));
    }

    @Test public void mobileDataIsAnExtraFlag() {
        assertEquals(MoonBridge.ROAMING_ENABLED, flags(true, false));
        assertEquals(MoonBridge.ROAMING_ENABLED | MoonBridge.ROAMING_ALLOW_METERED, flags(true, true));
    }
}
