package com.limelight.settings;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.preference.EditTextPreference;
import androidx.preference.ListPreference;
import androidx.preference.Preference;
import androidx.preference.PreferenceGroup;
import androidx.preference.PreferenceScreen;
import androidx.preference.TwoStatePreference;

import com.limelight.R;
import com.limelight.preferences.SeekBarPreference;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Reads the live, regrouped settings screen for the parts of the Settings section that describe
 * it rather than draw it: what search can find, which keys the card can pin, what has changed,
 * and where a row sits in its section.
 *
 * <p>Everything is read from the same {@link Preference} objects the list shows, after the device
 * code has removed what this device cannot do, so a search never offers a setting the list does
 * not have, and a row hidden at run time (Help on a Fire TV, the companion row with no companion
 * configured) is left out of both. The screen is expected in {@link SettingsRegrouper}'s shape:
 * {@link SectionCategory} children holding the rows.
 *
 * Main thread only.
 */
public final class SettingsIndex {

    private SettingsIndex() {
    }

    /** The search over every visible row, in screen order. */
    @NonNull
    public static SettingsSearch search(@NonNull PreferenceScreen screen,
                                        @NonNull SettingsCatalog catalog) {
        Context context = screen.getContext();
        List<SettingsSearch.Entry> entries = new ArrayList<>();
        int order = 0;
        for (int i = 0; i < screen.getPreferenceCount(); i++) {
            Preference child = screen.getPreference(i);
            if (!(child instanceof PreferenceGroup) || !child.isVisible()) {
                continue;
            }
            PreferenceGroup section = (PreferenceGroup) child;
            String sectionId = sectionIdOf(section);
            String sectionTitle = section.getTitle() != null ? section.getTitle().toString() : "";
            for (int j = 0; j < section.getPreferenceCount(); j++) {
                Preference p = section.getPreference(j);
                if (!p.isVisible() || p.getKey() == null || p.getTitle() == null) {
                    continue;
                }
                SettingsCatalog.Subgroup subgroup = catalog.subgroupOf(p.getKey());
                entries.add(new SettingsSearch.Entry(p.getKey(), p.getTitle().toString(),
                        p.getSummary() != null ? p.getSummary().toString() : null,
                        sectionId, sectionTitle,
                        subgroup != null ? context.getString(subgroup.title) : "",
                        kindOf(p), SettingValues.isExternal(p), order++));
            }
        }
        return new SettingsSearch(entries);
    }

    /** The sections on screen, in order: the jump chips. Ids and titles, as pairs. */
    @NonNull
    public static List<SettingsCatalog.Section> sectionsShown(@NonNull PreferenceScreen screen,
                                                              @NonNull SettingsCatalog catalog) {
        List<SettingsCatalog.Section> out = new ArrayList<>();
        for (SettingsCatalog.Section section : catalog.sections()) {
            Preference header = screen.findPreference(SectionCategory.KEY_PREFIX + section.id);
            if (header instanceof SectionCategory && header.isVisible()
                    && ((SectionCategory) header).getVisibleCount() > 0) {
                out.add(section);
            }
        }
        return out;
    }

    /** The keys of every row shown, which the pinned card keeps its pins to. */
    @NonNull
    public static Set<String> visibleKeys(@NonNull PreferenceScreen screen) {
        Set<String> keys = new LinkedHashSet<>();
        for (Preference p : rows(screen)) {
            keys.add(p.getKey());
        }
        return keys;
    }

    /** The keys of the rows shown whose value is not their default. */
    @NonNull
    public static Set<String> changedKeys(@NonNull PreferenceScreen screen,
                                          @NonNull SettingsDefaults defaults,
                                          @Nullable SharedPreferences prefs) {
        Set<String> keys = new LinkedHashSet<>();
        for (Preference p : rows(screen)) {
            if (defaults.isChanged(p, prefs)) {
                keys.add(p.getKey());
            }
        }
        return keys;
    }

    /** The section a row is in, or null for one outside every section. */
    @Nullable
    public static SectionCategory sectionOf(@NonNull Preference preference) {
        PreferenceGroup parent = preference.getParent();
        return parent instanceof SectionCategory ? (SectionCategory) parent : null;
    }

    /**
     * Where a row is among its section's visible rows: {1-based place, count}, the sticky
     * label's "8 OF 34"; null when the row is not visible in a section.
     */
    @Nullable
    public static int[] placeInSection(@NonNull Preference preference) {
        SectionCategory section = sectionOf(preference);
        if (section == null || !preference.isVisible()) {
            return null;
        }
        int place = 0;
        int count = 0;
        for (int i = 0; i < section.getPreferenceCount(); i++) {
            Preference p = section.getPreference(i);
            if (!p.isVisible()) {
                continue;
            }
            count++;
            if (p == preference) {
                place = count;
            }
        }
        return place > 0 ? new int[]{place, count} : null;
    }

    /** The first visible row of a section, where a jump to it lands; null for none. */
    @Nullable
    public static Preference firstRow(@NonNull PreferenceScreen screen, @NonNull String sectionId) {
        Preference header = screen.findPreference(SectionCategory.KEY_PREFIX + sectionId);
        if (!(header instanceof PreferenceGroup)) {
            return null;
        }
        PreferenceGroup group = (PreferenceGroup) header;
        for (int i = 0; i < group.getPreferenceCount(); i++) {
            Preference p = group.getPreference(i);
            if (p.isVisible() && p.getKey() != null) {
                return p;
            }
        }
        return null;
    }

    /** "Controllers › Rumble": where a setting lives, for a search result. */
    @NonNull
    public static String breadcrumb(@NonNull Context context, @NonNull SettingsSearch.Entry entry) {
        return entry.subgroup.isEmpty() ? entry.sectionTitle
                : context.getString(R.string.settings_breadcrumb, entry.sectionTitle, entry.subgroup);
    }

    /** The section id behind a {@link SectionCategory}'s key. */
    @NonNull
    public static String sectionIdOf(@NonNull Preference section) {
        String key = section.getKey();
        return key != null && key.startsWith(SectionCategory.KEY_PREFIX)
                ? key.substring(SectionCategory.KEY_PREFIX.length()) : (key != null ? key : "");
    }

    @NonNull
    static SettingsSearch.Kind kindOf(@NonNull Preference p) {
        if (p instanceof ListPreference) {
            return SettingsSearch.Kind.LIST;
        }
        if (p instanceof TwoStatePreference) {
            return SettingsSearch.Kind.TOGGLE;
        }
        if (p instanceof SeekBarPreference) {
            return SettingsSearch.Kind.SEEK;
        }
        if (p instanceof EditTextPreference) {
            return SettingsSearch.Kind.TEXT;
        }
        return SettingsSearch.Kind.ACTION;
    }

    private static List<Preference> rows(PreferenceScreen screen) {
        List<Preference> out = new ArrayList<>();
        for (int i = 0; i < screen.getPreferenceCount(); i++) {
            Preference child = screen.getPreference(i);
            if (!(child instanceof PreferenceGroup) || !child.isVisible()) {
                continue;
            }
            PreferenceGroup section = (PreferenceGroup) child;
            for (int j = 0; j < section.getPreferenceCount(); j++) {
                Preference p = section.getPreference(j);
                if (p.isVisible() && p.getKey() != null) {
                    out.add(p);
                }
            }
        }
        return out;
    }
}
