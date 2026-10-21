package com.limelight.companion;

import androidx.annotation.DrawableRes;
import androidx.annotation.Nullable;

import com.limelight.stream.StreamAction;

/**
 * One action in the in-game controls when they are drawn on the companion panel. A label, how it
 * is drawn and what to do — the panel supplies the sheet and the selection; the caller supplies
 * these.
 */
public class CompanionMenuItem {
    public final CharSequence label;
    /** 0 for none. */
    @DrawableRes
    public final int iconRes;
    /** Where the entry sits on the sheet: the tile row, a list row, or the danger row. */
    public final StreamAction.Group group;
    /** Whether a toggle is on; null for an entry that is not a toggle. */
    @Nullable
    public final Boolean checked;
    /** Whether choosing it shows a page rather than doing something: the row ends in a chevron. */
    public final boolean opensPage;
    /** Whether it ends the game (Quit game): drawn in the danger hue, apart from Disconnect. */
    public final boolean warn;
    public final Runnable action;

    public CompanionMenuItem(CharSequence label, @DrawableRes int iconRes, StreamAction.Group group,
                             @Nullable Boolean checked, boolean opensPage, boolean warn,
                             Runnable action) {
        this.label = label;
        this.iconRes = iconRes;
        this.group = group;
        this.checked = checked;
        this.opensPage = opensPage;
        this.warn = warn;
        this.action = action;
    }
}
