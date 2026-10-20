package com.limelight.main;

import android.view.KeyEvent;

/**
 * A section that binds gamepad buttons of its own (X on a PC card, Y for a game's actions).
 *
 * <p>The main screen is one activity, so every key reaches it whichever section is showing.
 * {@code MainActivity.dispatchKeyEvent} offers each key-down to the visible section first and
 * only to it; a hidden section never sees a key. The shell itself binds none of these buttons,
 * and in particular L1/R1 never switch sections, because Stats uses them for its periods.
 */
public interface SectionKeys {
    /**
     * Main thread only. Called with key-downs only; when it returns true the activity swallows
     * the matching key-up as well, so a focused view never acts on half a press.
     *
     * @return true when the section handled the key
     */
    boolean onSectionKey(KeyEvent event);
}
