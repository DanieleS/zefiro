package com.limelight.profiles;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Looper;

import androidx.preference.ListPreference;
import androidx.preference.PreferenceManager;
import androidx.test.core.app.ApplicationProvider;

import com.limelight.EditProfileActivity;
import com.limelight.MainScreenTestEnvironment;
import com.limelight.R;
import com.limelight.TestLogSuppressor;
import com.limelight.settings.SettingsEditor;

import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * The profile editor builds on the stream settings list: it gets the same sections, and a change
 * made in it lands in the profile's in-memory copy, never in the stored settings.
 */
@Config(sdk = {33}, shadows = {com.limelight.shadows.ShadowMoonBridge.class,
        com.limelight.shadows.ShadowGameManager.class})
@RunWith(RobolectricTestRunner.class)
public class EditProfileSettingsTest {

    @BeforeClass
    public static void suppressLogs() {
        TestLogSuppressor.install();
    }

    @Before
    public void setUp() {
        MainScreenTestEnvironment.prepare();
        Context context = ApplicationProvider.getApplicationContext();
        ProfilesManager.instance = null;
        ProfilesManager.getInstance().load(context);
    }

    @Test
    public void aNewProfileEditsInMemoryInTheRegroupedList() {
        Context context = ApplicationProvider.getApplicationContext();
        SharedPreferences stored = PreferenceManager.getDefaultSharedPreferences(context);
        stored.edit().putString("frame_pacing", "latency").commit();

        EditProfileActivity activity = Robolectric.buildActivity(EditProfileActivity.class).setup().get();
        Shadows.shadowOf(Looper.getMainLooper()).idle();
        EditProfileActivity.ProfilePreferenceFragment fragment = (EditProfileActivity.ProfilePreferenceFragment)
                activity.getSupportFragmentManager().findFragmentById(R.id.preferences_container);
        assertNotNull(fragment);
        assertNotNull("The profile list is regrouped too", fragment.findPreference("section_video"));

        ListPreference pacing = fragment.findPreference("frame_pacing");
        assertNotNull(pacing);
        assertTrue(SettingsEditor.setChoice(pacing, "smoothness"));

        assertEquals("smoothness", activity.getInMemoryPrefs().getString("frame_pacing", null));
        assertEquals("latency", stored.getString("frame_pacing", null));
    }
}
