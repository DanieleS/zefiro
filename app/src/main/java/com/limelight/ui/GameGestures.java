package com.limelight.ui;

import androidx.annotation.NonNull;

import com.limelight.binding.input.GameInputDevice;
import com.limelight.stream.ControlsSurface;

public interface GameGestures {
    void toggleKeyboard();

    default void showGameMenu(GameInputDevice device){};

    /**
     * Whether an in-stream controls surface is open, so the pads must not reach the host. Safe
     * from any thread: the USB driver's pads report on their own.
     */
    default boolean isControlsOpen() {
        return false;
    }

    /**
     * A USB-driver pad's input for the open controls. Any thread; it is carried to the main
     * thread, and dropped there if the controls have shut in the meantime.
     */
    default void onControlsNav(@NonNull ControlsSurface.Nav nav) {}
}
