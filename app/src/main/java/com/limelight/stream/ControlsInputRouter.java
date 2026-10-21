package com.limelight.stream;

import android.view.KeyEvent;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.limelight.nvstream.input.ControllerPacket;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Where the pad and the keys go while the in-stream controls are open: to the open surface, as
 * {@link ControlsSurface.Nav}, and never to the host.
 *
 * <h3>Why one router</h3>
 *
 * The side panel lives inside the game's window and the companion panel's window takes no focus,
 * so neither gets the keys the way a Dialog would: the stream's own handlers see every press
 * first and would send it to the PC. {@code Game} asks this before anything else in its key and
 * motion paths and, while a surface is open, hands the event here and stops. One place turns
 * keys, hats and sticks into intent, so every surface is driven the same way and none of them
 * has to know where the input came from.
 *
 * <h3>Key-ups</h3>
 *
 * A key pressed into the controls is released into them too, even when the press itself closed
 * them (A on a leaf, B on the first page): the host never saw it go down, so it must not see it
 * come up.
 *
 * <pre>
 *   down (open)     taken, mapped to a Nav, remembered
 *   up              taken if remembered, or while open
 *   down (closed)   not taken, and forgotten, so a lost up never eats a later one
 * </pre>
 *
 * <h3>Hat and stick</h3>
 *
 * Edge-detected and four-way: a push moves once, whatever the stick then does until it is let
 * back to centre or turned to another direction, and a diagonal counts as its stronger axis.
 *
 * <h3>Pads the USB driver reads</h3>
 *
 * Those never raise a KeyEvent or a MotionEvent: their whole state arrives at once, on the pad's
 * own input thread, and goes straight to the host. {@link PadReports} is the same rules for
 * them, run where the report lands, so they drive the controls and leak nothing either.
 *
 * <p>Main thread only, all of it but {@link PadReports}; the methods take plain numbers so the
 * rules are tested off the device.
 */
public final class ControlsInputRouter {

    /** What the router drives: whichever surface is open, through the in-stream menu. */
    public interface Target {
        boolean isOpen();

        /** Returns whether an open surface took it. */
        boolean onNav(@NonNull ControlsSurface.Nav nav);
    }

    /** How far a stick must lean, of its full travel, to count as a push. */
    static final float STICK_THRESHOLD = 0.5f;

    private final Target target;
    // The keys whose press went to the controls, so their release does too.
    private final Set<Integer> takenDowns = new HashSet<>();
    // The direction the hat or stick last pointed while the controls were open; null is centre.
    @Nullable
    private ControlsSurface.Nav lastAxisNav;

    public ControlsInputRouter(@NonNull Target target) {
        this.target = target;
    }

    /** A key-down; returns whether it was taken, in which case nothing else may see it. */
    public boolean onKeyDown(int keyCode, int repeatCount) {
        if (isVolumeKey(keyCode)) {
            return false;
        }
        if (!target.isOpen()) {
            takenDowns.remove(keyCode);
            return false;
        }
        takenDowns.add(keyCode);

        ControlsSurface.Nav nav = navForKey(keyCode);
        // A held d-pad walks on, as it would anywhere else; a held A or B acts once.
        if (nav != null && (repeatCount == 0 || isDirection(nav))) {
            target.onNav(nav);
        }
        return true;
    }

    /** A key-up; returns whether it was taken. */
    public boolean onKeyUp(int keyCode) {
        if (isVolumeKey(keyCode)) {
            return false;
        }
        if (takenDowns.remove(keyCode)) {
            return true;
        }
        return target.isOpen();
    }

    /**
     * A joystick motion event's hat and left stick; returns whether it was taken. Each axis is
     * -1..1, positive right and down, as Android reports them.
     */
    public boolean onAxes(float hatX, float hatY, float stickX, float stickY) {
        if (!target.isOpen()) {
            lastAxisNav = null;
            return false;
        }

        ControlsSurface.Nav nav = axisNav(hatX, hatY, stickX, stickY);
        if (nav != lastAxisNav) {
            lastAxisNav = nav;
            if (nav != null) {
                target.onNav(nav);
            }
        }
        return true;
    }

    /**
     * What a key means to the controls; null for a key that means nothing there (it is still
     * taken while they are open, the volume keys aside).
     */
    @Nullable
    static ControlsSurface.Nav navForKey(int keyCode) {
        switch (keyCode) {
            case KeyEvent.KEYCODE_DPAD_UP:
                return ControlsSurface.Nav.UP;
            case KeyEvent.KEYCODE_DPAD_DOWN:
                return ControlsSurface.Nav.DOWN;
            case KeyEvent.KEYCODE_DPAD_LEFT:
                return ControlsSurface.Nav.LEFT;
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                return ControlsSurface.Nav.RIGHT;
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_BUTTON_A:
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_NUMPAD_ENTER:
                return ControlsSurface.Nav.ACTIVATE;
            // B steps back a page, as Back does on every Android screen; Escape is a keyboard's B.
            case KeyEvent.KEYCODE_BUTTON_B:
            case KeyEvent.KEYCODE_BACK:
            case KeyEvent.KEYCODE_ESCAPE:
                return ControlsSurface.Nav.BACK;
            // The keys that open the controls shut them again, from any page.
            case KeyEvent.KEYCODE_BUTTON_START:
            case KeyEvent.KEYCODE_MENU:
                return ControlsSurface.Nav.CLOSE;
            default:
                return null;
        }
    }

    /**
     * The one direction the hat, or else the stick, points; null at centre. The hat wins when it
     * is pressed at all, since it is the deliberate one of the two.
     */
    @Nullable
    static ControlsSurface.Nav axisNav(float hatX, float hatY, float stickX, float stickY) {
        float x;
        float y;
        if (hatX != 0 || hatY != 0) {
            x = hatX;
            y = hatY;
        } else {
            x = Math.abs(stickX) >= STICK_THRESHOLD ? stickX : 0;
            y = Math.abs(stickY) >= STICK_THRESHOLD ? stickY : 0;
        }
        if (x == 0 && y == 0) {
            return null;
        }
        if (Math.abs(y) >= Math.abs(x)) {
            return y < 0 ? ControlsSurface.Nav.UP : ControlsSurface.Nav.DOWN;
        }
        return x < 0 ? ControlsSurface.Nav.LEFT : ControlsSurface.Nav.RIGHT;
    }

    /**
     * The phone's volume rocker and mute. The stream never sends them to the host (it has no
     * translation for them), so they always fell through to the system; taking them here would
     * leave the volume stuck for as long as the controls are open.
     */
    static boolean isVolumeKey(int keyCode) {
        return keyCode == KeyEvent.KEYCODE_VOLUME_UP || keyCode == KeyEvent.KEYCODE_VOLUME_DOWN
                || keyCode == KeyEvent.KEYCODE_VOLUME_MUTE;
    }

    private static boolean isDirection(ControlsSurface.Nav nav) {
        return nav == ControlsSurface.Nav.UP || nav == ControlsSurface.Nav.DOWN
                || nav == ControlsSurface.Nav.LEFT || nav == ControlsSurface.Nav.RIGHT;
    }

    /**
     * One USB-driver pad's reports, while the controls are open and after. The press of A, B or
     * Start and a push of the d-pad or left stick become a {@link ControlsSurface.Nav}, edge
     * detected like the key and axis paths above. Every button held while the controls were open
     * stays hidden from the host until it is let go, so the A that picked the last row does not
     * press A in the game a moment later (the key path's taken key-ups, for a pad that only ever
     * reports what is held).
     *
     * <p>One per pad, and only ever called on that pad's input thread.
     */
    public static final class PadReports {
        private int lastButtons;
        // The buttons the controls took; the host sees each again only once it has been let go.
        private int takenButtons;
        @Nullable
        private ControlsSurface.Nav lastAxisNav;
        // Reused for every report: a pad reports every few milliseconds.
        private final List<ControlsSurface.Nav> navs = new ArrayList<>(4);

        /**
         * A report while the controls are open: the navs it makes, in order, valid until the next
         * call. The stick is -1..1, positive right and down, like {@link #axisNav}.
         */
        @NonNull
        public List<ControlsSurface.Nav> whileOpen(int buttonFlags, float stickX, float stickY) {
            navs.clear();
            int pressed = buttonFlags & ~lastButtons;
            lastButtons = buttonFlags;
            takenButtons |= buttonFlags;

            float hatX = flag(buttonFlags, ControllerPacket.RIGHT_FLAG)
                    - flag(buttonFlags, ControllerPacket.LEFT_FLAG);
            float hatY = flag(buttonFlags, ControllerPacket.DOWN_FLAG)
                    - flag(buttonFlags, ControllerPacket.UP_FLAG);
            ControlsSurface.Nav axis = axisNav(hatX, hatY, stickX, stickY);
            if (axis != lastAxisNav) {
                lastAxisNav = axis;
                if (axis != null) {
                    navs.add(axis);
                }
            }
            if ((pressed & ControllerPacket.A_FLAG) != 0) {
                navs.add(ControlsSurface.Nav.ACTIVATE);
            }
            if ((pressed & ControllerPacket.B_FLAG) != 0) {
                navs.add(ControlsSurface.Nav.BACK);
            }
            if ((pressed & ControllerPacket.PLAY_FLAG) != 0) {
                navs.add(ControlsSurface.Nav.CLOSE);
            }
            return navs;
        }

        /**
         * A report while the controls are shut: the buttons the host may see. The edges start
         * afresh, so a button already held as the controls open is not a press in them.
         */
        public int forHost(int buttonFlags) {
            lastButtons = buttonFlags;
            lastAxisNav = null;
            takenButtons &= buttonFlags;
            return buttonFlags & ~takenButtons;
        }

        private static float flag(int buttonFlags, int flag) {
            return (buttonFlags & flag) != 0 ? 1 : 0;
        }
    }
}
