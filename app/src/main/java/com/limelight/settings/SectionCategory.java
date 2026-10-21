package com.limelight.settings;

import android.content.Context;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.StringRes;
import androidx.preference.PreferenceCategory;
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
    }
}
