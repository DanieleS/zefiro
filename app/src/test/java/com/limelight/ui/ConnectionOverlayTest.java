package com.limelight.ui;

import android.view.KeyEvent;

import com.limelight.companion.CompanionState;
import com.limelight.ui.ConnectionOverlay.KeyAction;
import com.limelight.ui.ConnectionOverlay.Phase;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * The connection scene's rules: what each key does while it is up, how its clock reads, and how
 * the native stages fold into phases and the phases into the companion panel's four steps.
 */
public class ConnectionOverlayTest {

    @Test
    public void theKeysThatMeanBackCancelTheLaunch() {
        assertEquals(KeyAction.CANCEL, ConnectionOverlay.keyAction(KeyEvent.KEYCODE_BUTTON_B));
        assertEquals(KeyAction.CANCEL, ConnectionOverlay.keyAction(KeyEvent.KEYCODE_BACK));
        assertEquals(KeyAction.CANCEL, ConnectionOverlay.keyAction(KeyEvent.KEYCODE_BUTTON_SELECT));
        assertEquals(KeyAction.CANCEL, ConnectionOverlay.keyAction(KeyEvent.KEYCODE_ESCAPE));
    }

    @Test
    public void theConfirmKeysPressCancel() {
        assertEquals(KeyAction.ACTIVATE, ConnectionOverlay.keyAction(KeyEvent.KEYCODE_BUTTON_A));
        assertEquals(KeyAction.ACTIVATE, ConnectionOverlay.keyAction(KeyEvent.KEYCODE_DPAD_CENTER));
        assertEquals(KeyAction.ACTIVATE, ConnectionOverlay.keyAction(KeyEvent.KEYCODE_ENTER));
        assertEquals(KeyAction.ACTIVATE, ConnectionOverlay.keyAction(KeyEvent.KEYCODE_NUMPAD_ENTER));
    }

    @Test
    public void everyOtherKeyGoesWhereItAlwaysWent() {
        assertEquals(KeyAction.PASS, ConnectionOverlay.keyAction(KeyEvent.KEYCODE_VOLUME_UP));
        assertEquals(KeyAction.PASS, ConnectionOverlay.keyAction(KeyEvent.KEYCODE_VOLUME_DOWN));
        assertEquals(KeyAction.PASS, ConnectionOverlay.keyAction(KeyEvent.KEYCODE_BUTTON_X));
        assertEquals(KeyAction.PASS, ConnectionOverlay.keyAction(KeyEvent.KEYCODE_BUTTON_START));
        assertEquals(KeyAction.PASS, ConnectionOverlay.keyAction(KeyEvent.KEYCODE_DPAD_UP));
        assertEquals(KeyAction.PASS, ConnectionOverlay.keyAction(KeyEvent.KEYCODE_A));
    }

    @Test
    public void theClockReadsMinutesAndSecondsThenHours() {
        assertEquals("0:00", ConnectionOverlay.formatElapsed(0));
        assertEquals("0:00", ConnectionOverlay.formatElapsed(999));
        assertEquals("0:07", ConnectionOverlay.formatElapsed(7_400));
        assertEquals("1:00", ConnectionOverlay.formatElapsed(60_000));
        assertEquals("12:40", ConnectionOverlay.formatElapsed((12 * 60 + 40) * 1000L));
        assertEquals("59:59", ConnectionOverlay.formatElapsed(3_599_999));
        assertEquals("1:02:03", ConnectionOverlay.formatElapsed((3600 + 2 * 60 + 3) * 1000L));
    }

    @Test
    public void aNegativeElapsedTimeReadsAsNothingYet() {
        assertEquals("0:00", ConnectionOverlay.formatElapsed(-5_000));
    }

    @Test
    public void theClockTicksJustAfterEachSecond() {
        assertEquals(1000, ConnectionOverlay.msToNextSecond(0));
        assertEquals(600, ConnectionOverlay.msToNextSecond(7_400));
        assertEquals(1, ConnectionOverlay.msToNextSecond(7_999));
        assertEquals(1000, ConnectionOverlay.msToNextSecond(-30));
    }

    @Test
    public void theSixPhasesFoldIntoFourSteps() {
        assertEquals(0, ConnectionOverlay.stepOf(Phase.PREPARING));
        assertEquals(0, ConnectionOverlay.stepOf(Phase.LAUNCHING));
        assertEquals(1, ConnectionOverlay.stepOf(Phase.REACHING));
        assertEquals(1, ConnectionOverlay.stepOf(Phase.NEGOTIATING));
        assertEquals(2, ConnectionOverlay.stepOf(Phase.MEDIA));
        assertEquals(3, ConnectionOverlay.stepOf(Phase.OPENING));
        // Ready is past every step: all of them done.
        assertEquals(CompanionState.Launch.STEP_COUNT, ConnectionOverlay.stepOf(Phase.READY));
    }

    @Test
    public void theStepsNeverGoBackAsThePhasesMoveOn() {
        int last = -1;
        for (Phase phase : Phase.values()) {
            int step = ConnectionOverlay.stepOf(phase);
            assertTrue("step of " + phase, step >= last);
            last = step;
        }
    }

    @Test
    public void theNativeStagesFoldIntoPhases() {
        assertEquals(Phase.LAUNCHING, ConnectionOverlay.phaseFor(null));
        // The app-launch step arrives as the game's own name.
        assertEquals(Phase.LAUNCHING, ConnectionOverlay.phaseFor("Hades II"));
        assertEquals(Phase.REACHING, ConnectionOverlay.phaseFor("name resolution"));
        assertEquals(Phase.NEGOTIATING, ConnectionOverlay.phaseFor("RTSP handshake"));
        assertEquals(Phase.MEDIA, ConnectionOverlay.phaseFor("video stream initialization"));
        assertEquals(Phase.OPENING, ConnectionOverlay.phaseFor("input stream establishment"));
    }
}
