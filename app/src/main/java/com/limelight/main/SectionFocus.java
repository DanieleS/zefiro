package com.limelight.main;

import android.view.View;

import androidx.annotation.Nullable;

/**
 * What a section tells the focus gate ({@link MainRootLayout}) about focus inside it.
 *
 * <p>The d-pad leaves a section for the navigation and comes back; the section, not the gate,
 * knows which of its views the user was on (the tile on a shelf, the period selector in Stats),
 * so it answers for where focus returns.
 */
public interface SectionFocus {
    /**
     * Where focus goes when it comes back into this section from the navigation: the view that
     * last held it, or the section's first stop when nothing has yet. Main thread only.
     *
     * @return a focusable, shown view inside the section, or null when the section has none
     */
    @Nullable
    View restoreFocusTarget();

    /**
     * Places focus in the section after it has been switched to, in the section's own order.
     * Posted by the navigator once the fragment is shown; main thread only.
     */
    void requestInitialFocus();
}
