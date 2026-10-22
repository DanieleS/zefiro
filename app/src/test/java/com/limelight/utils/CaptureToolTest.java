package com.limelight.utils;

import com.limelight.nvstream.jni.MoonBridge;

import org.junit.Test;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class CaptureToolTest {
    private static final short WIN = 0x5B, ALT = 0xA4, PRTSCN = 0x2C;

    @Test
    public void gameBarShortcuts() {
        CaptureTool t = CaptureTool.GAME_BAR;
        assertArrayEquals(new short[]{WIN, ALT, PRTSCN}, t.keysFor(CaptureTool.Action.SCREENSHOT));
        assertArrayEquals(new short[]{WIN, ALT, 'R'}, t.keysFor(CaptureTool.Action.TOGGLE_RECORDING));
        assertArrayEquals(new short[]{WIN, ALT, 'G'}, t.keysFor(CaptureTool.Action.SAVE_REPLAY));
    }

    @Test
    public void nvidiaShortcuts() {
        CaptureTool t = CaptureTool.NVIDIA;
        assertArrayEquals(new short[]{ALT, 0x70}, t.keysFor(CaptureTool.Action.SCREENSHOT));
        assertArrayEquals(new short[]{ALT, 0x78}, t.keysFor(CaptureTool.Action.TOGGLE_RECORDING));
        assertArrayEquals(new short[]{ALT, 0x79}, t.keysFor(CaptureTool.Action.SAVE_REPLAY));
    }

    @Test
    public void offSendsNothing() {
        assertFalse(CaptureTool.OFF.isEnabled());
        assertNull(CaptureTool.OFF.keysFor(CaptureTool.Action.SCREENSHOT));
        assertTrue(CaptureTool.GAME_BAR.isEnabled());
    }

    @Test
    public void aHostThatHandlesCapturesDecides() {
        int handles = MoonBridge.LI_FF_CAPTURE_ACTIONS;
        int available = handles | MoonBridge.LI_FF_CAPTURE_AVAILABLE;
        for (CaptureTool t : CaptureTool.values()) {
            // Even with the preference off: the tool is chosen on the host
            assertEquals(CaptureTool.Route.HOST, t.route(available));
            // Captures off on the host hide them, whatever this client prefers
            assertEquals(CaptureTool.Route.NONE, t.route(handles));
        }
        // Other host flags don't change that
        assertEquals(CaptureTool.Route.HOST, CaptureTool.NVIDIA.route(available | 0x10003));
    }

    @Test
    public void anyOtherHostGetsThePreferencesShortcuts() {
        assertEquals(CaptureTool.Route.KEYS, CaptureTool.GAME_BAR.route(0));
        assertEquals(CaptureTool.Route.KEYS, CaptureTool.NVIDIA.route(0x03));
        assertEquals(CaptureTool.Route.NONE, CaptureTool.OFF.route(0));
        // Availability without the capability is meaningless, so it is ignored
        assertEquals(CaptureTool.Route.KEYS, CaptureTool.GAME_BAR.route(MoonBridge.LI_FF_CAPTURE_AVAILABLE));
    }

    @Test
    public void actionsAreTheWireValues() {
        // Shared with moonlight-common-c's LI_CAPTURE_* and the host; a mismatch is silent
        assertEquals(1, CaptureTool.Action.SCREENSHOT.wireValue);
        assertEquals(2, CaptureTool.Action.SAVE_REPLAY.wireValue);
        assertEquals(3, CaptureTool.Action.TOGGLE_RECORDING.wireValue);
        assertEquals(0x20000, MoonBridge.LI_FF_CAPTURE_ACTIONS);
        assertEquals(0x40000, MoonBridge.LI_FF_CAPTURE_AVAILABLE);
    }

    @Test
    public void preferenceValuesRoundTrip() {
        for (CaptureTool t : CaptureTool.values()) {
            assertEquals(t, CaptureTool.fromPreference(t.getPreferenceValue()));
        }
        assertEquals(CaptureTool.GAME_BAR, CaptureTool.fromPreference(null));
        assertEquals(CaptureTool.GAME_BAR, CaptureTool.fromPreference("obs"));
    }
}
