package com.limelight.settings;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.preference.PreferenceManager;
import androidx.test.core.app.ApplicationProvider;

import com.limelight.preferences.PreferenceConfiguration;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * Defaults come from the XML as the preferences apply them, read without touching the stored
 * settings, and the bitrate's follows the resolution and frame rate.
 */
@Config(sdk = {33})
@RunWith(RobolectricTestRunner.class)
public class SettingsDefaultsTest {

    private final Context context = ApplicationProvider.getApplicationContext();

    @Test
    public void listDefaultIsTheXmlOne() {
        SettingsDefaults defaults = SettingsDefaults.read(context);
        assertEquals(PreferenceConfiguration.DEFAULT_RESOLUTION, defaults.asMap().get("list_resolution"));
        assertEquals("latency", defaults.asMap().get("frame_pacing"));
        assertEquals(Boolean.TRUE, defaults.asMap().get("checkbox_enable_rumble"));
    }

    @Test
    public void seekBarDefaultIsRead() {
        SettingsDefaults defaults = SettingsDefaults.read(context);
        assertEquals(5, defaults.asMap().get("seekbar_deadzone"));
        assertEquals(100, defaults.asMap().get("seekbar_vibrate_fallback_strength"));
    }

    @Test
    public void actionsHaveNoDefaultAndNeverChange() {
        SettingsDefaults defaults = SettingsDefaults.read(context);
        assertNull(defaults.defaultFor("pref_help", null));
        assertFalse(defaults.isChanged("pref_help", "anything", null));
    }

    @Test
    public void readingLeavesTheStoredSettingsAlone() {
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
        prefs.edit().clear().commit();
        SettingsDefaults.read(context);
        assertTrue(prefs.getAll().isEmpty());
    }

    @Test
    public void bitrateIsChangedOnlyAwayFromTheResolutionsDefault() {
        SettingsDefaults defaults = SettingsDefaults.read(context);
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
        prefs.edit()
                .putString(PreferenceConfiguration.RESOLUTION_PREF_STRING, "1920x1080")
                .putString(PreferenceConfiguration.FPS_PREF_STRING, "120")
                .commit();
        int expected = PreferenceConfiguration.getDefaultBitrate("1920x1080", "120");

        assertFalse(defaults.isChanged(PreferenceConfiguration.BITRATE_PREF_STRING, expected, prefs));
        assertTrue(defaults.isChanged(PreferenceConfiguration.BITRATE_PREF_STRING, expected + 1000, prefs));
        // The 720p default would be a change at 1080p.
        assertTrue(defaults.isChanged(PreferenceConfiguration.BITRATE_PREF_STRING,
                PreferenceConfiguration.getDefaultBitrate("1280x720", "60"), prefs));
    }

    @Test
    public void meteredBitrateDefaultIsAutomatic() {
        SettingsDefaults defaults = SettingsDefaults.read(context);
        assertFalse(defaults.isChanged("seekbar_metered_bitrate_kbps", 0, null));
        assertTrue(defaults.isChanged("seekbar_metered_bitrate_kbps", 5000, null));
    }
}
