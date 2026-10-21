package com.limelight.binding.input;

import android.app.Activity;
import android.content.ComponentName;
import android.os.Looper;
import android.util.SparseArray;

import com.limelight.binding.input.driver.AbstractController;
import com.limelight.nvstream.NvConnection;
import com.limelight.preferences.PreferenceConfiguration;
import com.limelight.shadows.ShadowMoonBridge;
import com.limelight.ui.GameGestures;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.LooperMode;

import java.lang.reflect.Field;
import java.time.Duration;

import static org.mockito.ArgumentMatchers.anyShort;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.robolectric.Shadows.shadowOf;

/**
 * A USB-driver pad in mouse emulation keeps its live sticks while the in-stream controls are
 * open, so the next close can pick them up. The emulated mouse must not move or scroll the
 * host's pointer from them until then, and must follow them again right after.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {33}, shadows = {ShadowMoonBridge.class})
@LooperMode(LooperMode.Mode.PAUSED)
public class MouseEmulationControlsTest {
    private static final int PAD_ID = 7;

    private NvConnection conn;
    private GameGestures gestures;
    private ControllerHandler handler;
    private ControllerHandler.GenericControllerContext pad;

    @Before
    public void setUp() throws Exception {
        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        // The SHIELD controller service is not there to bind to
        shadowOf(activity.getApplication()).declareComponentUnbindable(new ComponentName(
                "com.nvidia.blakepairing", "com.nvidia.blakepairing.AccessoryService"));
        conn = mock(NvConnection.class);
        gestures = mock(GameGestures.class);
        PreferenceConfiguration prefs = PreferenceConfiguration.readPreferences(activity);
        // Moves with the left stick, scrolls with the right one
        prefs.analogStickForScrolling = PreferenceConfiguration.AnalogStickForScrolling.RIGHT;
        handler = new ControllerHandler(activity, conn, gestures, prefs);

        AbstractController device = mock(AbstractController.class);
        when(device.getControllerId()).thenReturn(PAD_ID);
        handler.deviceAdded(device);
        pad = usbContext(handler, PAD_ID);
        pad.toggleMouseEmulation();
    }

    @After
    public void tearDown() {
        if (pad != null) {
            pad.destroy();
        }
    }

    @Test
    public void sticksHeldWhileOpen_neverReachTheHost() {
        when(gestures.isControlsOpen()).thenReturn(true);
        handler.reportControllerState(PAD_ID, 0, 1f, 0f, 0f, 1f, 0f, 0f);

        idle(500);

        verify(conn, never()).sendMouseMove(anyShort(), anyShort());
        verify(conn, never()).sendMouseHighResScroll(anyShort());
        verify(conn, never()).sendMouseHighResHScroll(anyShort());
    }

    @Test
    public void afterTheClose_theStickHeldThroughItMovesTheMouse() {
        when(gestures.isControlsOpen()).thenReturn(true);
        handler.reportControllerState(PAD_ID, 0, 1f, 0f, 0f, 1f, 0f, 0f);
        idle(200);

        when(gestures.isControlsOpen()).thenReturn(false);
        idle(100);

        verify(conn, atLeastOnce()).sendMouseMove(anyShort(), anyShort());
        verify(conn, atLeastOnce()).sendMouseHighResScroll(anyShort());
    }

    @Test
    public void reopening_stopsTheMouseAgain() {
        handler.reportControllerState(PAD_ID, 0, 1f, 0f, 0f, 0f, 0f, 0f);
        idle(100);
        verify(conn, atLeastOnce()).sendMouseMove(anyShort(), anyShort());

        when(gestures.isControlsOpen()).thenReturn(true);
        clearInvocations(conn);
        idle(500);

        verify(conn, never()).sendMouseMove(anyShort(), anyShort());
    }

    private static void idle(long ms) {
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms));
    }

    @SuppressWarnings("unchecked")
    private static ControllerHandler.GenericControllerContext usbContext(ControllerHandler handler, int id)
            throws Exception {
        Field field = ControllerHandler.class.getDeclaredField("usbDeviceContexts");
        field.setAccessible(true);
        SparseArray<? extends ControllerHandler.GenericControllerContext> contexts =
                (SparseArray<? extends ControllerHandler.GenericControllerContext>) field.get(handler);
        return contexts.get(id);
    }
}
