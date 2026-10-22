package com.limelight.utils;

import com.limelight.nvstream.jni.MoonBridge;

/**
 * The capture tool on the host PC that the in-game menu's Screenshot, "Save last 30 s" and
 * recording entries drive.
 *
 * <p>A host that carries capture actions out itself (Scirocco/Vibepollo, advertising
 * {@link MoonBridge#LI_FF_CAPTURE_ACTIONS}) is told which action, and presses the shortcut of the
 * tool configured on it; this preference is not used then. See {@link #route}.
 *
 * <p>With any other host Zefiro presses the tool's own keyboard shortcuts over the stream, as you
 * would at the PC, the same way CouchPilot's CaptureService does:
 *
 * <pre>
 *                 Game Bar           NVIDIA (GeForce Experience, NVIDIA app)
 *   screenshot    Win+Alt+PrtScn     Alt+F1
 *   record        Win+Alt+R          Alt+F9
 *   replay        Win+Alt+G          Alt+F10
 * </pre>
 *
 * Recording is a toggle in both tools, and neither says whether it is recording, so the menu
 * offers one "start/stop" entry rather than pretending to know. The codes are Windows virtual-key
 * codes, the ones the stream's keyboard path sends.
 */
public enum CaptureTool {
    GAME_BAR("gamebar"),
    NVIDIA("nvidia"),
    OFF("off");

    public enum Action {
        SCREENSHOT(MoonBridge.LI_CAPTURE_SCREENSHOT),
        SAVE_REPLAY(MoonBridge.LI_CAPTURE_SAVE_REPLAY),
        TOGGLE_RECORDING(MoonBridge.LI_CAPTURE_TOGGLE_RECORDING);

        /** The action's value on the wire, for {@link MoonBridge#sendCaptureAction(int)}. */
        public final int wireValue;

        Action(int wireValue) {
            this.wireValue = wireValue;
        }
    }

    /** Who carries a capture out for a stream. */
    public enum Route {
        /** The host, with the tool configured on it. */
        HOST,
        /** Zefiro, pressing this preference's shortcuts. */
        KEYS,
        /** Nobody: the menu offers no captures. */
        NONE
    }

    private static final short VK_LWIN = 0x5B;
    private static final short VK_LMENU = 0xA4;
    // VK_SNAPSHOT. KeyboardTranslator's VK_PRINTSCREEN (154) is Java's constant, not Windows'.
    private static final short VK_SNAPSHOT = 0x2C;
    private static final short VK_G = 0x47;
    private static final short VK_R = 0x52;
    private static final short VK_F1 = 0x70;
    private static final short VK_F9 = 0x78;
    private static final short VK_F10 = 0x79;

    private final String preferenceValue;

    CaptureTool(String preferenceValue) {
        this.preferenceValue = preferenceValue;
    }

    public String getPreferenceValue() {
        return preferenceValue;
    }

    /** The tool a stored preference names; Game Bar, the one every Windows PC has, otherwise. */
    public static CaptureTool fromPreference(String value) {
        for (CaptureTool tool : values()) {
            if (tool.preferenceValue.equals(value)) {
                return tool;
            }
        }
        return GAME_BAR;
    }

    /** Whether the menu should offer captures at all, with a host that leaves them to Zefiro. */
    public boolean isEnabled() {
        return this != OFF;
    }

    /**
     * Who carries captures out with a host that advertised {@code hostFeatureFlags}. A host that
     * handles them decides, including turning them off for everyone; this preference only
     * matters with one that doesn't.
     */
    public Route route(int hostFeatureFlags) {
        if ((hostFeatureFlags & MoonBridge.LI_FF_CAPTURE_ACTIONS) != 0) {
            return (hostFeatureFlags & MoonBridge.LI_FF_CAPTURE_AVAILABLE) != 0 ? Route.HOST : Route.NONE;
        }
        return isEnabled() ? Route.KEYS : Route.NONE;
    }

    /**
     * The keys to press, modifiers first, in the order they go down; they come up in reverse.
     * Null when the tool is off.
     */
    public short[] keysFor(Action action) {
        switch (this) {
            case GAME_BAR:
                switch (action) {
                    case SCREENSHOT:
                        return new short[]{VK_LWIN, VK_LMENU, VK_SNAPSHOT};
                    case SAVE_REPLAY:
                        return new short[]{VK_LWIN, VK_LMENU, VK_G};
                    case TOGGLE_RECORDING:
                        return new short[]{VK_LWIN, VK_LMENU, VK_R};
                }
                break;
            case NVIDIA:
                switch (action) {
                    case SCREENSHOT:
                        return new short[]{VK_LMENU, VK_F1};
                    case SAVE_REPLAY:
                        return new short[]{VK_LMENU, VK_F10};
                    case TOGGLE_RECORDING:
                        return new short[]{VK_LMENU, VK_F9};
                }
                break;
            default:
                break;
        }
        return null;
    }
}
