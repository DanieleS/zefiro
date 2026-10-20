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
 * one is shown, chosen by {@link #apply(WindowClass)}. Since either can become the visible one on
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
    }

    private final View railColumn;
    private final NavigationRailView rail;
    private final BottomNavigationView bottom;
    private final Callbacks callbacks;

    private WindowClass windowClass;
    // Set while this class moves the check itself, so the widgets' listener does not report it
    // back as a user's choice.
    private boolean mirroring;

    NavChrome(View railColumn, NavigationRailView rail, View railSettings,
              BottomNavigationView bottom, Callbacks callbacks) {
        this.railColumn = railColumn;
        this.rail = rail;
        this.bottom = bottom;
        this.callbacks = callbacks;

        rail.getMenu().findItem(R.id.nav_settings).setVisible(false);
        railSettings.setOnClickListener(v -> callbacks.onSettingsChosen());

        rail.setOnItemSelectedListener(item -> onItemSelected(item, bottom));
        bottom.setOnItemSelectedListener(item -> onItemSelected(item, rail));
        // A click on the section already on screen is not a switch. Later the Library takes
        // this to go back to its home; for now it is simply nothing.
        NavigationBarView.OnItemReselectedListener none = item -> { };
        rail.setOnItemReselectedListener(none);
        bottom.setOnItemReselectedListener(none);
    }

    /** Shows the rail column or the bottom bar for this window. */
    void apply(@NonNull WindowClass windowClass) {
        this.windowClass = windowClass;
        boolean wide = windowClass == WindowClass.WIDE;
        railColumn.setVisibility(wide ? View.VISIBLE : View.GONE);
        bottom.setVisibility(wide ? View.GONE : View.VISIBLE);
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
