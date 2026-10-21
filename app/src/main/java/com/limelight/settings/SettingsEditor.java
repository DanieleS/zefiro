package com.limelight.settings;

import android.content.SharedPreferences;
import android.text.TextUtils;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.preference.EditTextPreference;
import androidx.preference.ListPreference;
import androidx.preference.Preference;
import androidx.preference.TwoStatePreference;

import com.limelight.preferences.SeekBarPreference;

/**
 * The only way the new settings UI changes a value without a preference's own dialog: Left and
 * Right on a row, a choice touched on the companion panel, a reset.
 *
 * <p>Every change asks the preference's change listener first and stores the value only if the
 * listener accepts it, exactly as androidx's list, switch and text dialogs do. That is what keeps
 * the side effects the settings rely on: the resolution and FPS listeners resetting the bitrate
 * and warning about a native mode, the PyroWave probe, unlock FPS rebuilding the list, the custom
 * values' validation. {@link SeekBarPreference} keeps its dialog's order instead (stored, then
 * told), through {@link SeekBarPreference#step(int)}.
 *
 * <p>Each method returns false when nothing moved: at the end of a list or range, a refusal, a
 * disabled row, a value already in place. The caller then lets the key go on, so Left at a
 * list's first entry still reaches the rail. Main thread only.
 */
public final class SettingsEditor {

    private SettingsEditor() {
    }

    /** The neighbouring entry, without wrapping; from no value, the first or last entry. */
    public static boolean cycle(@NonNull ListPreference preference, int dir) {
        CharSequence[] values = preference.getEntryValues();
        if (dir == 0 || !preference.isEnabled() || values == null || values.length == 0) {
            return false;
        }
        int index = preference.findIndexOfValue(preference.getValue());
        int next;
        if (index < 0) {
            next = dir > 0 ? 0 : values.length - 1;
        } else {
            next = index + Integer.signum(dir);
        }
        if (next < 0 || next >= values.length) {
            return false;
        }
        return commitList(preference, values[next].toString());
    }

    /** Selects an entry by value, as a tap on it in the list dialog would. */
    public static boolean setChoice(@NonNull ListPreference preference, @NonNull String value) {
        if (!preference.isEnabled() || preference.findIndexOfValue(value) < 0
                || TextUtils.equals(value, preference.getValue())) {
            return false;
        }
        return commitList(preference, value);
    }

    /** Turns a switch on or off; false if it already was. */
    public static boolean set(@NonNull TwoStatePreference preference, boolean checked) {
        if (!preference.isEnabled() || preference.isChecked() == checked) {
            return false;
        }
        if (!preference.callChangeListener(checked)) {
            return false;
        }
        preference.setChecked(checked);
        return true;
    }

    /** One step along a slider, by its d-pad step. */
    public static boolean step(@NonNull SeekBarPreference preference, int dir) {
        return preference.step(dir);
    }

    /**
     * Puts the default back. False when the preference has no default, is already at it, is
     * disabled, or its listener refuses.
     */
    public static boolean reset(@NonNull Preference preference, @NonNull SettingsDefaults defaults,
                                @Nullable SharedPreferences prefs) {
        String key = preference.getKey();
        if (key == null || !preference.isEnabled() || !defaults.isChanged(preference, prefs)) {
            return false;
        }
        Object def = defaults.defaultFor(key, prefs);
        if (preference instanceof ListPreference && def instanceof String) {
            return setChoice((ListPreference) preference, (String) def);
        }
        if (preference instanceof TwoStatePreference && def instanceof Boolean) {
            return set((TwoStatePreference) preference, (Boolean) def);
        }
        if (preference instanceof SeekBarPreference && def instanceof Integer) {
            ((SeekBarPreference) preference).commitValue((Integer) def);
            return true;
        }
        if (preference instanceof EditTextPreference && def instanceof String) {
            EditTextPreference text = (EditTextPreference) preference;
            if (!text.callChangeListener(def)) {
                return false;
            }
            text.setText((String) def);
            return true;
        }
        return false;
    }

    private static boolean commitList(ListPreference preference, String value) {
        if (!preference.callChangeListener(value)) {
            return false;
        }
        preference.setValue(value);
        return true;
    }
}
