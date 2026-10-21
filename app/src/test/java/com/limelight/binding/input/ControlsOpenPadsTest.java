package com.limelight.binding.input;

import android.app.Activity;
import android.content.ComponentName;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.util.SparseArray;

import com.limelight.binding.input.driver.AbstractController;
import com.limelight.nvstream.NvConnection;
import com.limelight.nvstream.input.ControllerPacket;
import com.limelight.nvstream.jni.MoonBridge;
import com.limelight.preferences.PreferenceConfiguration;
import com.limelight.shadows.ShadowMoonBridge;
import com.limelight.ui.GameGestures;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.LooperMode;
import org.robolectric.shadows.SensorEventBuilder;
import org.robolectric.shadows.ShadowSensor;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

import static org.mockito.ArgumentMatchers.anyByte;
import static org.mockito.ArgumentMatchers.anyFloat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyShort;
import static org.mockito.ArgumentMatchers.byteThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.floatThat;
import static org.mockito.ArgumentMatchers.intThat;
import static org.mockito.ArgumentMatchers.shortThat;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.robolectric.Shadows.shadowOf;

/**
 * While the in-stream controls are open no pad input reaches the host, whichever thread or path
 * it takes: a USB-driver pad's report, the on-screen controller, the motion sensors. What the
 * pads hold is kept for the close, which sends it.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {33}, shadows = {ShadowMoonBridge.class})
@LooperMode(LooperMode.Mode.PAUSED)
public class ControlsOpenPadsTest {
    private static final int PAD_ID = 7;

    private NvConnection conn;
    private GameGestures gestures;
    private ControllerHandler handler;

    @Before
    public void setUp() {
        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        // The SHIELD controller service is not there to bind to
        shadowOf(activity.getApplication()).declareComponentUnbindable(new ComponentName(
                "com.nvidia.blakepairing", "com.nvidia.blakepairing.AccessoryService"));
        conn = mock(NvConnection.class);
        gestures = mock(GameGestures.class);
        PreferenceConfiguration prefs = PreferenceConfiguration.readPreferences(activity);
        handler = new ControllerHandler(activity, conn, gestures, prefs);

        AbstractController device = mock(AbstractController.class);
        when(device.getControllerId()).thenReturn(PAD_ID);
        handler.deviceAdded(device);
        // A first report at rest gets the pad its controller number
        handler.reportControllerState(PAD_ID, 0, 0f, 0f, 0f, 0f, 0f, 0f);
        clearInvocations(conn);
    }

    @Test
    public void theOpen_sendsANeutralPacket_evenForAPadThatLooksIdle() {
        when(gestures.isControlsOpen()).thenReturn(true);

        handler.sendNeutralState();

        verifyNeutralPacket(atLeastOnce());
        verifyNoLivePacket();
    }

    @Test
    public void heldWhileOpen_keptUnsent_thenSentOnTheClose() {
        when(gestures.isControlsOpen()).thenReturn(true);
        handler.reportControllerState(PAD_ID, ControllerPacket.A_FLAG, 1f, 0f, 0f, 0f, 0f, 1f);
        verify(conn, never()).sendControllerInput(anyShort(), anyShort(), anyInt(),
                anyByte(), anyByte(), anyShort(), anyShort(), anyShort(), anyShort());

        // Whatever else sends a packet while open, it carries none of what the pad holds
        handler.resendPads();
        verifyNeutralPacket(atLeastOnce());
        verifyNoLivePacket();

        when(gestures.isControlsOpen()).thenReturn(false);
        clearInvocations(conn);
        handler.resendPads();

        // The stick and trigger held through the close; A was pressed into the controls
        verify(conn).sendControllerInput(anyShort(), anyShort(), eq(0),
                eq((byte) 0), byteThat(b -> b != 0),
                shortThat(x -> x > 0), anyShort(), anyShort(), anyShort());
    }

    @Test
    public void aReportThatPassedItsCheckJustBeforeTheOpen_landsNeutral() {
        // The pad's thread reads "closed", then the main thread opens before the packet goes
        when(gestures.isControlsOpen()).thenReturn(false, true);

        handler.reportControllerState(PAD_ID, ControllerPacket.A_FLAG, 1f, 0f, 0f, 0f, 0f, 1f);

        verifyNeutralPacket(times(1));
        verifyNoLivePacket();
    }

    @Test
    public void theOnScreenController_sendsNothingWhileOpen() {
        when(gestures.isControlsOpen()).thenReturn(true);
        handler.reportOscState(ControllerPacket.A_FLAG, (short) 0x4000, (short) 0, (short) 0, (short) 0,
                (byte) 0, (byte) 0);
        verify(conn, never()).sendControllerInput(anyShort(), anyShort(), anyInt(),
                anyByte(), anyByte(), anyShort(), anyShort(), anyShort(), anyShort());

        when(gestures.isControlsOpen()).thenReturn(false);
        handler.reportOscState(ControllerPacket.A_FLAG, (short) 0x4000, (short) 0, (short) 0, (short) 0,
                (byte) 0, (byte) 0);
        verify(conn, atLeastOnce()).sendControllerInput(anyShort(), anyShort(),
                intThat(map -> (map & ControllerPacket.A_FLAG) != 0),
                anyByte(), anyByte(), anyShort(), anyShort(), anyShort(), anyShort());
    }

    @Test
    public void motionSensors_sendNothingWhileOpen_andTheFirstReadingAfterGoesOut() throws Exception {
        SensorEventListener listener = sensorListener(MoonBridge.LI_MOTION_TYPE_GYRO);
        SensorEvent reading = SensorEventBuilder.newBuilder()
                .setSensor(ShadowSensor.newInstance(Sensor.TYPE_GYROSCOPE))
                .setValues(new float[] {0.1f, 0.2f, 0.3f})
                .build();

        listener.onSensorChanged(reading);
        verify(conn, times(1)).sendControllerMotionEvent(anyByte(), anyByte(), anyFloat(), anyFloat(), anyFloat());

        when(gestures.isControlsOpen()).thenReturn(true);
        listener.onSensorChanged(reading);
        verify(conn, times(1)).sendControllerMotionEvent(anyByte(), anyByte(), anyFloat(), anyFloat(), anyFloat());

        // The same reading as before the open still goes out: the host may have been told
        // otherwise since, and the pad may not move again for a while
        when(gestures.isControlsOpen()).thenReturn(false);
        listener.onSensorChanged(reading);
        verify(conn, times(2)).sendControllerMotionEvent(anyByte(), anyByte(), anyFloat(), anyFloat(), anyFloat());
    }

    @Test
    public void theOpen_stillsAUsbPadsGyro_andDropsItsReadingsTillTheClose() {
        handler.reportControllerMotion(PAD_ID, MoonBridge.LI_MOTION_TYPE_GYRO, 30f, 0f, 0f);
        clearInvocations(conn);

        when(gestures.isControlsOpen()).thenReturn(true);
        handler.sendNeutralState();
        handler.reportControllerMotion(PAD_ID, MoonBridge.LI_MOTION_TYPE_GYRO, 40f, 0f, 0f);

        verify(conn, times(1)).sendControllerMotionEvent(anyByte(),
                eq(MoonBridge.LI_MOTION_TYPE_GYRO), eq(0f), eq(0f), eq(0f));
        verify(conn, never()).sendControllerMotionEvent(anyByte(), anyByte(),
                floatThat(v -> v != 0f), anyFloat(), anyFloat());

        when(gestures.isControlsOpen()).thenReturn(false);
        handler.reportControllerMotion(PAD_ID, MoonBridge.LI_MOTION_TYPE_GYRO, 40f, 0f, 0f);
        verify(conn).sendControllerMotionEvent(anyByte(),
                eq(MoonBridge.LI_MOTION_TYPE_GYRO), eq(40f), eq(0f), eq(0f));
    }

    @Test
    public void theOpen_stillsAPadsSensorGyro() throws Exception {
        usbPad().gyroListener = sensorListener(MoonBridge.LI_MOTION_TYPE_GYRO);

        when(gestures.isControlsOpen()).thenReturn(true);
        handler.sendNeutralState();

        verify(conn).sendControllerMotionEvent(anyByte(),
                eq(MoonBridge.LI_MOTION_TYPE_GYRO), eq(0f), eq(0f), eq(0f));
    }

    @Test
    public void theOpen_sendsNoMotion_forAPadWithoutAGyro() {
        // An accelerometer reading is left as it is: it holds the pad's attitude
        handler.reportControllerMotion(PAD_ID, MoonBridge.LI_MOTION_TYPE_ACCEL, 0f, 9.8f, 0f);
        clearInvocations(conn);

        when(gestures.isControlsOpen()).thenReturn(true);
        handler.sendNeutralState();

        verify(conn, never()).sendControllerMotionEvent(anyByte(), anyByte(), anyFloat(), anyFloat(), anyFloat());
    }

    @Test
    public void theOnScreenController_heldStillThroughTheClose_isSentByTheClose() {
        handler.reportOscState(0, (short) 0, (short) 0, (short) 0, (short) 0, (byte) 0, (byte) 0);
        when(gestures.isControlsOpen()).thenReturn(true);
        handler.sendNeutralState();
        // The finger on the stick moves while open, then holds still
        handler.reportOscState(0, (short) 0x4000, (short) 0, (short) 0, (short) 0, (byte) 0, (byte) 0);
        verifyNoLivePacket();

        when(gestures.isControlsOpen()).thenReturn(false);
        clearInvocations(conn);
        handler.resendPads();

        verify(conn, atLeastOnce()).sendControllerInput(anyShort(), anyShort(), anyInt(),
                anyByte(), anyByte(), eq((short) 0x4000), anyShort(), anyShort(), anyShort());
    }

    @Test
    public void theOnScreenController_firstTouchedWhileOpen_isSentByTheClose() {
        when(gestures.isControlsOpen()).thenReturn(true);
        handler.reportOscState(0, (short) 0, (short) 0, (short) 0, (short) 0, (byte) 0, (byte) 0x7F);

        when(gestures.isControlsOpen()).thenReturn(false);
        handler.resendPads();

        verify(conn, atLeastOnce()).sendControllerInput(anyShort(), anyShort(), anyInt(),
                anyByte(), eq((byte) 0x7F), anyShort(), anyShort(), anyShort(), anyShort());
    }

    @Test
    public void withNoPadInUse_theOpenAndTheCloseSendNoPacket() {
        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        shadowOf(activity.getApplication()).declareComponentUnbindable(new ComponentName(
                "com.nvidia.blakepairing", "com.nvidia.blakepairing.AccessoryService"));
        NvConnection padlessConn = mock(NvConnection.class);
        ControllerHandler padless = new ControllerHandler(activity, padlessConn, gestures,
                PreferenceConfiguration.readPreferences(activity));

        // A packet would bring a pad up at the host for someone playing with keyboard and mouse
        when(gestures.isControlsOpen()).thenReturn(true);
        padless.sendNeutralState();
        when(gestures.isControlsOpen()).thenReturn(false);
        padless.resendPads();

        verify(padlessConn, never()).sendControllerInput(anyShort(), anyShort(), anyInt(),
                anyByte(), anyByte(), anyShort(), anyShort(), anyShort(), anyShort());
        verify(padlessConn, never()).sendControllerMotionEvent(anyByte(), anyByte(),
                anyFloat(), anyFloat(), anyFloat());
    }

    @SuppressWarnings("unchecked")
    private ControllerHandler.InputDeviceContext usbPad() throws Exception {
        Field field = ControllerHandler.class.getDeclaredField("usbDeviceContexts");
        field.setAccessible(true);
        return ((SparseArray<? extends ControllerHandler.InputDeviceContext>) field.get(handler)).get(PAD_ID);
    }

    private SensorEventListener sensorListener(byte motionType) throws Exception {
        Method create = ControllerHandler.class.getDeclaredMethod("createSensorListener",
                short.class, byte.class, boolean.class);
        create.setAccessible(true);
        return (SensorEventListener) create.invoke(handler, (short) 0, motionType, false);
    }

    private void verifyNeutralPacket(org.mockito.verification.VerificationMode mode) {
        verify(conn, mode).sendControllerInput(anyShort(), anyShort(), eq(0),
                eq((byte) 0), eq((byte) 0), eq((short) 0), eq((short) 0), eq((short) 0), eq((short) 0));
    }

    private void verifyNoLivePacket() {
        verify(conn, never()).sendControllerInput(anyShort(), anyShort(), intThat(map -> map != 0),
                anyByte(), anyByte(), anyShort(), anyShort(), anyShort(), anyShort());
        verify(conn, never()).sendControllerInput(anyShort(), anyShort(), anyInt(),
                anyByte(), byteThat(b -> b != 0), anyShort(), anyShort(), anyShort(), anyShort());
        verify(conn, never()).sendControllerInput(anyShort(), anyShort(), anyInt(),
                anyByte(), anyByte(), shortThat(x -> x != 0), anyShort(), anyShort(), anyShort());
    }
}
