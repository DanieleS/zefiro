package com.limelight.settings;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.preference.Preference;
import androidx.preference.PreferenceGroup;
import androidx.preference.PreferenceScreen;

import com.limelight.LimeLog;
import com.limelight.R;

import java.util.ArrayList;
import java.util.List;

/**
 * Moves the preferences of a fully built settings screen out of the XML's categories and into
 * the {@link SettingsCatalog}'s sections, in the catalogue's order.
 *
 * <p>It runs last, after the device code has removed what this device cannot do and set every
 * listener, so it moves the same {@link Preference} objects the device code configured: their
 * change and click listeners, trimmed entry lists and summaries go with them. A moved preference
 * is detached and re-attached, and androidx registers its dependency again by key on attach, so
 * a dependency across sections ({@code checkbox_remember_mouse_mode} on
 * {@code checkbox_mouse_emulation}) keeps enabling and disabling its dependent.
 *
 * <pre>
 *   XML categories (device removals done)        screen as shown
 *   category_video_settings ─┐                   section_video       (catalogue order)
 *   category_audio ...       ├── move by key ──▶ section_audio
 *   category_advanced ...   ─┘                   ...
 *                                                section_other       (unknown keys, logged)
 * </pre>
 *
 * <p>Sections are created only when they receive a row, so one the device emptied (no
 * touchscreen, for instance) does not appear. The emptied XML categories are removed, and with
 * them their {@code initialExpandedChildrenCount}: the new sections never collapse.
 */
public final class SettingsRegrouper {

    private SettingsRegrouper() {
    }

    /** Main thread, on a screen attached to its preference manager. */
    public static void apply(@NonNull PreferenceScreen screen, @NonNull SettingsCatalog catalog) {
        Context context = screen.getContext();

        // What the XML built, before any section is added to the screen.
        List<Preference> xmlChildren = new ArrayList<>();
        for (int i = 0; i < screen.getPreferenceCount(); i++) {
            xmlChildren.add(screen.getPreference(i));
        }

        for (SettingsCatalog.Section section : catalog.sections()) {
            SectionCategory category = null;
            int order = 0;
            for (String key : section.keys()) {
                Preference preference = screen.findPreference(key);
                if (preference == null) {
                    // Removed by the device code: this device cannot use it.
                    continue;
                }
                if (category == null) {
                    category = new SectionCategory(context, section.id, section.title);
                    screen.addPreference(category);
                }
                move(screen, preference, category, order++);
            }
        }

        // Whatever the catalogue does not place goes last rather than vanishing.
        SectionCategory other = null;
        int order = 0;
        for (Preference child : xmlChildren) {
            List<Preference> strays = new ArrayList<>();
            if (child instanceof PreferenceGroup) {
                PreferenceGroup group = (PreferenceGroup) child;
                for (int i = 0; i < group.getPreferenceCount(); i++) {
                    strays.add(group.getPreference(i));
                }
            } else if (!catalog.contains(child.getKey())) {
                strays.add(child);
            }
            for (Preference stray : strays) {
                LimeLog.warning("Setting " + stray.getKey() + " has no place in SettingsCatalog");
                if (other == null) {
                    other = new SectionCategory(context, "other", R.string.settings_section_other);
                    screen.addPreference(other);
                }
                move(screen, stray, other, order++);
            }
            if (child instanceof PreferenceGroup) {
                // Emptied by now; its collapsing goes with it.
                screen.removePreference(child);
            }
        }
    }

    private static void move(PreferenceScreen screen, Preference preference,
                             PreferenceGroup target, int order) {
        PreferenceGroup parent = preference.getParent();
        if (parent != null) {
            parent.removePreference(preference);
        }
        // A preference keeps the order its XML category gave it; without a new one, rows from
        // different categories would interleave by their old positions.
        preference.setOrder(order);

        // Re-attaching looks the dependency up again, and throws if it is gone. Device code only
        // removes a dependency together with its dependents, but should that ever change, the row
        // keeps the enabled state it has rather than taking the list down.
        String dependency = preference.getDependency();
        if (dependency != null && screen.findPreference(dependency) == null) {
            LimeLog.warning("Setting " + preference.getKey() + " depends on missing " + dependency);
            preference.setDependency(null);
        }

        target.addPreference(preference);
    }
}
