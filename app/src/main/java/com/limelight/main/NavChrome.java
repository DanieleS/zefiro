package com.limelight.main;

import android.view.MenuItem;
import android.view.View;

import androidx.annotation.NonNull;

import com.google.android.material.bottomnavigation.BottomNavigationView;
import com.google.android.material.navigation.NavigationBarView;
import com.google.android.material.navigationrail.NavigationRailView;
import com.limelight.R;

/**
 * The main screen's two navigation widgets, kept as one.
 *
 * <p>The rail (with the profile avatar under it) and the bottom bar are both inflated, and only
 * one is shown, chosen by {@link #apply}. A short window (a phone in landscape) leaves the avatar
 * out, as the landscape mock does: Profiles is in Settings there, as it is for the bottom bar. Since either can become the visible one on
 * the next rotation, the hidden one is kept in step: the checked section and whether Stats is
 * offered are always written to both.
 *
 * <h3>Selection</h3>
 *
 * Selection follows a click (or the d-pad's confirm), never focus: moving along the rail does not
 * switch sections. A click on a section reports it through {@link Callbacks#onSectionChosen} and
 * checks the item; Settings reports {@link Callbacks#onSettingsChosen} and is refused, so the
 * check stays on the section that is on screen.
 *
 * <p>Settings sits apart from the destinations at the rail's foot, as in the mocks. A rail item
 * cannot, so the rail's Settings item is hidden and a plain button under the rail stands in for
 * it; the bottom bar keeps the menu's item as its last one. Both report the same callback.
 *
 * <p>The bottom bar offers four places, as the phone mock does: Search is the rail's alone, and a
 * compact window reaches it from the See all toolbar's search button.
 *
 * <p>A click on the section already on screen goes back to its start: in the Library, from See all
 * to the home ({@link Callbacks#onSectionReselected}). It is the touch way back on a window with
 * the rail, whose See all has no back arrow.
 *
 * <pre>
 *   click on rail item ──▶ Callbacks.onSectionChosen ──▶ (activity switches the section)
 *          │
 *          └──▶ mirrored to the bottom bar, with the listener muted
 *
 *   setChecked(section) ──▶ both widgets, listener muted (back, pairing, restore)
 * </pre>
 *
 * Main thread only.
 */
final class NavChrome {

    /** What the shell does when the user picks an item. */
    interface Callbacks {
        /** The user chose a section; the widgets have not checked it yet. */
        void onSectionChosen(@NonNull Section section);

        /** The user chose Settings; the selection does not move. */
        void onSettingsChosen();

        /** The user chose the section already on screen. */
        void onSectionReselected(@NonNull Section section);
    }

    private final View railColumn;
    private final View avatar;
    private final NavigationRailView rail;
    private final BottomNavigationView bottom;
    private final Callbacks callbacks;

    private WindowClass windowClass;
    // Set while this class moves the check itself, so the widgets' listener does not report it
    // back as a user's choice.
    private boolean mirroring;

    NavChrome(View railColumn, NavigationRailView rail, View railSettings, View avatar,
              BottomNavigationView bottom, Callbacks callbacks) {
        this.railColumn = railColumn;
        this.avatar = avatar;
        this.rail = rail;
        this.bottom = bottom;
        this.callbacks = callbacks;

        rail.getMenu().findItem(R.id.nav_settings).setVisible(false);
        bottom.getMenu().findItem(R.id.nav_search).setVisible(false);
        railSettings.setOnClickListener(v -> callbacks.onSettingsChosen());

        rail.setOnItemSelectedListener(item -> onItemSelected(item, bottom));
        bottom.setOnItemSelectedListener(item -> onItemSelected(item, rail));
        // A click on the section already on screen is not a switch: it goes back to its start.
        NavigationBarView.OnItemReselectedListener again = item -> {
            Section section = Section.forMenuId(item.getItemId());
            if (section != null) {
                callbacks.onSectionReselected(section);
            }
        };
        rail.setOnItemReselectedListener(again);
        bottom.setOnItemReselectedListener(again);
    }

    /**
     * Shows the rail column or the bottom bar for this window, and the avatar unless it is a
     * short one.
     */
    void apply(@NonNull WindowClass windowClass, boolean shortWindow) {
        this.windowClass = windowClass;
        boolean wide = windowClass == WindowClass.WIDE;
        railColumn.setVisibility(wide ? View.VISIBLE : View.GONE);
        bottom.setVisibility(wide ? View.GONE : View.VISIBLE);
        avatar.setVisibility(shortWindow ? View.GONE : View.VISIBLE);
    }

    WindowClass windowClass() {
        return windowClass;
    }

    /** Checks this section in both widgets without reporting it as a choice. */
    void setChecked(@NonNull Section section) {
        mirror(rail, section.menuId);
        mirror(bottom, section.menuId);
    }

    /** Offers Stats in both widgets, or takes it away. */
    void setStatsVisible(boolean visible) {
        rail.getMenu().findItem(R.id.nav_stats).setVisible(visible);
        bottom.getMenu().findItem(R.id.nav_stats).setVisible(visible);
    }

    /** The visible widget's item for the checked section, where focus lands from the content. */
    View checkedItemView() {
        NavigationBarView visible = windowClass == WindowClass.COMPACT ? bottom : rail;
        return visible.findViewById(visible.getSelectedItemId());
    }

    private boolean onItemSelected(MenuItem item, NavigationBarView other) {
        if (mirroring) {
            return true;
        }
        int id = item.getItemId();
        if (id == R.id.nav_settings) {
            callbacks.onSettingsChosen();
            return false;
        }
        Section section = Section.forMenuId(id);
        if (section == null) {
            return false;
        }
        callbacks.onSectionChosen(section);
        // The widget that was clicked checks the item itself once this returns true; only the
        // other one needs telling.
        mirror(other, id);
        return true;
    }

    private void mirror(NavigationBarView widget, int itemId) {
        if (widget.getSelectedItemId() == itemId) {
            return;
        }
        mirroring = true;
        try {
            widget.setSelectedItemId(itemId);
        } finally {
            mirroring = false;
        }
    }
}
