package com.limelight.companion;

import android.app.Activity;
import android.content.Context;
import android.hardware.display.DisplayManager;
import android.view.Display;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowDisplay;
import org.robolectric.shadows.ShadowDisplayManager;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.robolectric.Shadows.shadowOf;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = {33}, shadows = {com.limelight.shadows.ShadowMoonBridge.class})
public class DisplayTargetsTest {

    /**
     * The telemetry gate asks whether the device has a companion panel, and that answer must not
     * flip with the panel's power state: a bottom screen that is off at connect can come back on
     * mid-stream, and telemetry is only started once.
     */
    @Test
    public void hardwareProbeIgnoresPowerStateButDrawingProbeDoesNot() {
        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        int companionId = ShadowDisplayManager.addDisplay("w400dp-h300dp");

        DisplayManager dm = (DisplayManager) activity.getSystemService(Context.DISPLAY_SERVICE);
        Display companion = dm.getDisplay(companionId);
        ShadowDisplay shadow = shadowOf(companion);

        shadow.setState(Display.STATE_ON);
        assertEquals(companionId, DisplayTargets.findCompanionDisplayId(activity));
        assertEquals(companionId, DisplayTargets.findCompanionHardwareId(activity));

        shadow.setState(Display.STATE_OFF);
        assertEquals(Display.INVALID_DISPLAY, DisplayTargets.findCompanionDisplayId(activity));
        assertEquals(companionId, DisplayTargets.findCompanionHardwareId(activity));

        shadow.setState(Display.STATE_DOZE);
        assertNotEquals(Display.INVALID_DISPLAY, DisplayTargets.findCompanionHardwareId(activity));
    }

    @Test
    public void hardwareProbeFindsNothingOnASingleScreenDevice() {
        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        assertEquals(Display.INVALID_DISPLAY, DisplayTargets.findCompanionHardwareId(activity));
    }
}
