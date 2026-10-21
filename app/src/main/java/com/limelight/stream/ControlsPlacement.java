package com.limelight.stream;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/**
 * Which surface the in-stream controls belong on, and when open ones have to move. The one rule
 * the router (GameMenu) follows, kept apart and pure so {@code ControlsPlacementTest} pins it.
 *
 * <pre>
 *   opened over the game, companion panel showing    COMPANION  the Thor's bottom-screen sheet
 *   opened over the game, no panel                   MAIN       the side panel, over the game
 *   opened from the external display's phone         MAIN       its centred sheet, always
 * </pre>
 *
 * "Showing" is {@code CompanionDisplayManager.panelShowing()}, and never the device or the
 * window's shape: the Thor's lower screen can be switched off and on mid-stream, and then the
 * controls follow it. Open controls move with it rather than closing: on the page the user was
 * on ({@link ControlsSurface#openAt}), from the sheet to the side panel when the panel goes, and
 * back when it returns.
 */
public final class ControlsPlacement {

    public enum Surface {
        /** The side panel over the game, or the external display's centred sheet. */
        MAIN,
        /** The controls sheet on the companion panel. */
        COMPANION
    }

    private ControlsPlacement() {
    }

    /**
     * @param overGame     whether the controls are drawn by the game's own window, rather than
     *                     by the external display's phone, which always keeps its own sheet
     * @param panelShowing whether the companion panel is on screen now
     */
    @NonNull
    public static Surface surfaceFor(boolean overGame, boolean panelShowing) {
        return overGame && panelShowing ? Surface.COMPANION : Surface.MAIN;
    }

    /**
     * Where controls open on {@code open} must go now that the panel is, or is not, showing; null
     * when they stay where they are, or none are open.
     */
    @Nullable
    public static Surface moveTo(@Nullable Surface open, boolean overGame, boolean panelShowing) {
        if (open == null) {
            return null;
        }
        Surface wanted = surfaceFor(overGame, panelShowing);
        return wanted != open ? wanted : null;
    }
}
