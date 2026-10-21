package com.limelight.settings;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.res.Resources;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.limelight.LimeLog;

/**
 * What each entry of a list setting does, in a line, for the companion panel's choices
 * ({@link SettingsChoicesPage}): "Waits for the screen refresh. Smoother, a little slower."
 *
 * <p>The texts are optional {@code string-array}s in {@code strings.xml}, named after the
 * preference's key, {@code settings_choices_<key>}, one item per entry in the order of the XML's
 * {@code android:entries}. They are looked up by name, so a description for another list is a new
 * array and no code. A list without one shows its labels alone.
 *
 * <p>An array is used only when it has exactly as many items as the live list has entries.
 * Device code reshapes some lists at run time (the resolutions and frame rates the display
 * offers); for those the positions no longer line up with the XML, and a description against the
 * wrong entry would be worse than none.
 */
final class SettingsChoiceHints {

    static final String PREFIX = "settings_choices_";

    private SettingsChoiceHints() {
    }

    /**
     * One description per entry, or null when the key has none, or none that fits.
     *
     * @param count the live list's entries
     */
    @SuppressLint("DiscouragedApi") // by name, so an array for another key needs no code
    @Nullable
    static String[] descriptions(@NonNull Context context, @NonNull String key, int count) {
        if (key.isEmpty() || count <= 0) {
            return null;
        }
        Resources res = context.getResources();
        int id = res.getIdentifier(PREFIX + key, "array", context.getPackageName());
        if (id == 0) {
            return null;
        }
        String[] texts = res.getStringArray(id);
        if (texts.length != count) {
            LimeLog.warning("SettingsChoiceHints: " + key + " has " + texts.length
                    + " descriptions for " + count + " entries; not shown");
            return null;
        }
        return texts;
    }
}
