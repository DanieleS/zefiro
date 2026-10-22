package com.limelight.ui;

import android.app.Application;
import android.content.Context;
import android.hardware.input.InputManager;
import android.os.Handler;
import android.os.Looper;
import android.view.InputDevice;
import android.view.MotionEvent;

import androidx.annotation.NonNull;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;

/**
 * Whether a gamepad is connected: the one rule for showing pad hints and button glyphs, on every
 * screen and every device. The Thor's built-in pad counts, and so does a pad plugged into a phone;
 * a touch-only phone has none, so it shows no glyphs.
 *
 * <p>Kept current by an input device listener from {@link #install}, so a pad connected or
 * disconnected while a screen is up shows or hides its glyphs at once: observe {@link #connected}.
 *
 * Main thread only.
 */
public final class GamepadPresence {

    private static final MutableLiveData<Boolean> connected = new MutableLiveData<>(false);
    private static InputManager inputManager;

    private GamepadPresence() {
    }

    public static void install(@NonNull Application application) {
        inputManager = (InputManager) application.getSystemService(Context.INPUT_SERVICE);
        inputManager.registerInputDeviceListener(new InputManager.InputDeviceListener() {
            @Override
            public void onInputDeviceAdded(int deviceId) {
                refresh();
            }

            @Override
            public void onInputDeviceRemoved(int deviceId) {
                refresh();
            }

            @Override
            public void onInputDeviceChanged(int deviceId) {
                refresh();
            }
        }, new Handler(Looper.getMainLooper()));
        refresh();
    }

    /** Whether a pad is connected now. */
    public static boolean isConnected() {
        return Boolean.TRUE.equals(connected.getValue());
    }

    /** Whether a pad is connected, updated as pads come and go. */
    @NonNull
    public static LiveData<Boolean> connected() {
        return connected;
    }

    private static void refresh() {
        boolean now = scan();
        if (now != isConnected()) {
            connected.setValue(now);
        }
    }

    // A real device with gamepad buttons, or a joystick with sticks. Virtual devices (the
    // on-screen controller, injected input) and keyboards are not pads.
    private static boolean scan() {
        for (int id : inputManager.getInputDeviceIds()) {
            InputDevice device = inputManager.getInputDevice(id);
            if (device == null || device.isVirtual()) {
                continue;
            }
            int sources = device.getSources();
            if ((sources & InputDevice.SOURCE_GAMEPAD) == InputDevice.SOURCE_GAMEPAD) {
                return true;
            }
            if ((sources & InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK
                    && device.getMotionRange(MotionEvent.AXIS_X) != null
                    && device.getMotionRange(MotionEvent.AXIS_Y) != null) {
                return true;
            }
        }
        return false;
    }
}
