package com.limelight.companion;

import androidx.annotation.DrawableRes;
import androidx.annotation.Nullable;

import com.limelight.stream.StreamAction;

/**
 * One action in the in-game menu when it is drawn on the companion panel. A label, how it is
 * drawn and what to do — the panel supplies the row and the selection; the caller supplies these.
 */
public class CompanionMenuItem {
    public final CharSequence label;
    /** 0 for none. */
    @DrawableRes
    public final int iconRes;
    /** Where the entry sits among the stream controls; the list draws all groups alike for now. */
    public final StreamAction.Group group;
    /** Whether a toggle is on; null for an entry that is not a toggle. */
    @Nullable
    public final Boolean checked;
    public final Runnable action;

    public CompanionMenuItem(CharSequence label, @DrawableRes int iconRes, StreamAction.Group group,
                             @Nullable Boolean checked, Runnable action) {
        this.label = label;
        this.iconRes = iconRes;
        this.group = group;
        this.checked = checked;
        this.action = action;
    }
}
