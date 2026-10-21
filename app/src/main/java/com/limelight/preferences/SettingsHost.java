package com.limelight.preferences;

/**
 * Whatever hosts a {@link StreamPreferencesFragment}: the parent fragment, or else the activity.
 *
 * <p>Some changes reshape the list itself (unlock FPS adds and removes frame rates, a custom
 * resolution or refresh rate adds an entry), and a preference list cannot rebuild itself in
 * place, so the fragment asks its host for a fresh one. The fragment has already waited for the
 * change to be stored and checked that it is still attached.
 */
public interface SettingsHost {

    /** Replaces the hosted list with a new one built from the stored settings. Main thread. */
    void reloadPreferences();
}
