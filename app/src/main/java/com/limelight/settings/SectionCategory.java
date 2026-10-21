package com.limelight.settings;

import android.content.Context;
import android.view.View;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.StringRes;
import androidx.preference.Preference;
import androidx.preference.PreferenceCategory;
import androidx.preference.PreferenceGroup;
import androidx.preference.PreferenceViewHolder;

import com.limelight.R;

/**
 * A section of the settings list ("VIDEO 34"), made in code by {@link SettingsRegrouper} rather
 * than inflated, since the XML's categories are not the screen's sections.
 *
 * <p>The count is of the rows a user can see: device code removes some preferences and hides
 * others, and a hidden row changing back to shown makes the adapter rebind this header, so the
 * number never disagrees with the rows under it. Rows keep the order they are added in, which
 * is the catalogue's; nothing collapses behind a "more".
 *
 * <p>The first section on screen sits close under the header: the gap above a section parts it
 * from the rows before it, and the first has none. It then lines up with the Settings section's
 * sticky label, which covers it at the list's top with the same words.
 */
public class SectionCategory extends PreferenceCategory {

    /** Prefix of a section header's key; preference keys never start with it. */
    public static final String KEY_PREFIX = "section_";

    public SectionCategory(@NonNull Context context, @NonNull String id, @StringRes int title) {
        super(context);
        setKey(KEY_PREFIX + id);
        setTitle(title);
        setLayoutResource(R.layout.preference_section_header);
        setIconSpaceReserved(false);
        setPersistent(false);
        setOrderingAsAdded(true);
    }

    /** The rows under this header a user can see. */
    public int getVisibleCount() {
        int count = 0;
        for (int i = 0; i < getPreferenceCount(); i++) {
            if (getPreference(i).isVisible()) {
                count++;
            }
        }
        return count;
    }

    @Override
    public void onBindViewHolder(@NonNull PreferenceViewHolder holder) {
        super.onBindViewHolder(holder);
        TextView count = (TextView) holder.findViewById(R.id.settingsSectionCount);
        if (count != null) {
            count.setText(String.valueOf(getVisibleCount()));
        }
        View row = holder.itemView;
        int top = row.getResources().getDimensionPixelSize(isFirstShown()
                ? R.dimen.settings_sticky_padding_vertical : R.dimen.settings_category_top);
        if (row.getPaddingTop() != top) {
            row.setPaddingRelative(row.getPaddingStart(), top, row.getPaddingEnd(),
                    row.getPaddingBottom());
        }
    }

    // No section before this one is shown.
    private boolean isFirstShown() {
        PreferenceGroup parent = getParent();
        if (parent == null) {
            return true;
        }
        for (int i = 0; i < parent.getPreferenceCount(); i++) {
            Preference p = parent.getPreference(i);
            if (p == this) {
                return true;
            }
            if (p.isVisible()) {
                return false;
            }
        }
        return true;
    }
}
