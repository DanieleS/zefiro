package com.limelight.settings;

import android.content.SharedPreferences;

import androidx.preference.ListPreference;
import androidx.preference.TwoStatePreference;

import com.limelight.TestLogSuppressor;
import com.limelight.preferences.PreferenceConfiguration;
import com.limelight.preferences.SeekBarPreference;
import com.limelight.preferences.StreamPreferencesFragment;

import org.junit.BeforeClass;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * Changes made without a dialog go through the same listeners the dialogs do, against the real
 * list with its real listeners.
 */
@Config(sdk = {33}, shadows = {com.limelight.shadows.ShadowMoonBridge.class,
        com.limelight.shadows.ShadowGameManager.class})
@RunWith(RobolectricTestRunner.class)
public class SettingsEditorTest {

    @BeforeClass
    public static void suppressInvalidIdLogs() {
        TestLogSuppressor.install();
    }

    @Test
    public void cyclingTheResolutionResetsTheBitrateThroughItsListener() {
        StreamPreferencesFragment fragment = SettingsListFixture.open();
        SharedPreferences prefs = fragment.getPreferenceManager().getSharedPreferences();
        ListPreference resolution = fragment.findPreference(PreferenceConfiguration.RESOLUTION_PREF_STRING);
        assertNotNull(resolution);
        prefs.edit().putInt(PreferenceConfiguration.BITRATE_PREF_STRING, 12345).commit();

        int index = resolution.findIndexOfValue(resolution.getValue());
        int dir = index < resolution.getEntryValues().length - 1 ? 1 : -1;
        String before = resolution.getValue();

        assertTrue(SettingsEditor.cycle(resolution, dir));
        SettingsListFixture.idle();

        assertNotEquals(before, resolution.getValue());
        assertEquals(resolution.getValue(),
                prefs.getString(PreferenceConfiguration.RESOLUTION_PREF_STRING, null));
        String fps = prefs.getString(PreferenceConfiguration.FPS_PREF_STRING, PreferenceConfiguration.DEFAULT_FPS);
        assertEquals(PreferenceConfiguration.getDefaultBitrate(resolution.getValue(), fps),
                prefs.getInt(PreferenceConfiguration.BITRATE_PREF_STRING, 0));
    }

    @Test
    public void aRefusedChangeLeavesTheValue() {
        StreamPreferencesFragment fragment = SettingsListFixture.open();
        ListPreference pacing = fragment.findPreference("frame_pacing");
        assertNotNull(pacing);
        pacing.setValue("balanced");
        pacing.setOnPreferenceChangeListener((preference, newValue) -> false);

        assertFalse(SettingsEditor.cycle(pacing, 1));
        assertFalse(SettingsEditor.setChoice(pacing, "smoothness"));
        assertEquals("balanced", pacing.getValue());

        TwoStatePreference rumble = fragment.findPreference("checkbox_enable_rumble");
        assertNotNull(rumble);
        boolean was = rumble.isChecked();
        rumble.setOnPreferenceChangeListener((preference, newValue) -> false);
        assertFalse(SettingsEditor.set(rumble, !was));
        assertEquals(was, rumble.isChecked());
    }

    @Test
    public void cyclingStopsAtTheEndsWithoutWrapping() {
        StreamPreferencesFragment fragment = SettingsListFixture.open();
        ListPreference pacing = fragment.findPreference("frame_pacing");
        assertNotNull(pacing);
        CharSequence[] values = pacing.getEntryValues();

        pacing.setValue(values[values.length - 1].toString());
        assertFalse(SettingsEditor.cycle(pacing, 1));
        assertEquals(values[values.length - 1].toString(), pacing.getValue());

        pacing.setValue(values[0].toString());
        assertFalse(SettingsEditor.cycle(pacing, -1));
        assertTrue(SettingsEditor.cycle(pacing, 1));
        assertEquals(values[1].toString(), pacing.getValue());
    }

    @Test
    public void seekBarStepsAndClamps() {
        StreamPreferencesFragment fragment = SettingsListFixture.open();
        SeekBarPreference deadzone = fragment.findPreference("seekbar_deadzone");
        assertNotNull(deadzone);
        assertEquals(5, deadzone.getValue());

        assertTrue(SettingsEditor.step(deadzone, 1));
        assertEquals(6, deadzone.getValue());

        deadzone.commitValue(20);
        assertFalse("Already at the top", SettingsEditor.step(deadzone, 1));
        assertEquals(20, deadzone.getValue());

        deadzone.commitValue(-20);
        assertFalse("Already at the bottom", SettingsEditor.step(deadzone, -1));
        assertEquals(-20, deadzone.getValue());
    }

    @Test
    public void resetPutsTheDefaultBack() {
        StreamPreferencesFragment fragment = SettingsListFixture.open();
        SharedPreferences prefs = fragment.getPreferenceManager().getSharedPreferences();
        SettingsDefaults defaults = SettingsDefaults.read(fragment.requireContext());

        ListPreference pacing = fragment.findPreference("frame_pacing");
        assertNotNull(pacing);
        pacing.setValue("smoothness");
        assertTrue(defaults.isChanged(pacing, prefs));
        assertTrue(SettingsEditor.reset(pacing, defaults, prefs));
        assertEquals("latency", pacing.getValue());
        assertFalse("Nothing to reset at the default", SettingsEditor.reset(pacing, defaults, prefs));

        SeekBarPreference deadzone = fragment.findPreference("seekbar_deadzone");
        assertNotNull(deadzone);
        deadzone.commitValue(12);
        assertTrue(SettingsEditor.reset(deadzone, defaults, prefs));
        assertEquals(5, deadzone.getValue());
    }
}
