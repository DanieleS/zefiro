package com.limelight.companion;

import android.view.Display;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class TelemetryGateTest {

    @Test
    public void startsOnlyWithBothTheSettingAndASecondDisplay() {
        assertTrue(TelemetryGate.shouldStart(true, 1));
        // Display 0 is a real display id; only INVALID_DISPLAY means "none".
        assertTrue(TelemetryGate.shouldStart(true, Display.DEFAULT_DISPLAY));
    }

    @Test
    public void doesNotStartOnAPhone() {
        assertFalse(TelemetryGate.shouldStart(true, Display.INVALID_DISPLAY));
    }

    @Test
    public void doesNotStartWhenThePanelIsTurnedOff() {
        assertFalse(TelemetryGate.shouldStart(false, 1));
        assertFalse(TelemetryGate.shouldStart(false, Display.INVALID_DISPLAY));
    }
}
