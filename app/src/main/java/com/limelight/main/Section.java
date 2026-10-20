package com.limelight.main;

import androidx.annotation.IdRes;
import androidx.annotation.Nullable;
import androidx.annotation.StringRes;

import com.limelight.R;

/**
 * The main screen's destinations, in the order the navigation offers them.
 *
 * <p>Settings is deliberately not one: it is an action that opens another screen, and the
 * selection never moves to it.
 */
public enum Section {
    LIBRARY(R.id.nav_library, R.string.nav_library),
    PCS(R.id.nav_pcs, R.string.nav_pcs),
    STATS(R.id.nav_stats, R.string.nav_stats),
    SEARCH(R.id.nav_search, R.string.nav_search);

    /** The item that stands for this section in {@code R.menu.main_nav}. */
    @IdRes
    public final int menuId;
    @StringRes
    public final int title;

    Section(@IdRes int menuId, @StringRes int title) {
        this.menuId = menuId;
        this.title = title;
    }

    /** The fragment tag the section's fragment is added under, so it is found again after a restore. */
    public String tag() {
        return "section:" + name();
    }

    /** The section a navigation item stands for, or null for an item that is not one (Settings). */
    @Nullable
    public static Section forMenuId(@IdRes int menuId) {
        for (Section s : values()) {
            if (s.menuId == menuId) {
                return s;
            }
        }
        return null;
    }

    /** The section a saved name stands for, or {@code fallback} when the name is null or stale. */
    public static Section fromName(@Nullable String name, Section fallback) {
        if (name != null) {
            for (Section s : values()) {
                if (s.name().equals(name)) {
                    return s;
                }
            }
        }
        return fallback;
    }
}
