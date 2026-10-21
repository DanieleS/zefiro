package com.limelight.profiles;

import android.content.Intent;
import android.os.Looper;

import androidx.fragment.app.Fragment;
import androidx.preference.Preference;

import com.google.android.material.bottomnavigation.BottomNavigationView;
import com.limelight.MainScreenTestEnvironment;
import com.limelight.ProfilesActivity;
import com.limelight.R;
import com.limelight.TestLogSuppressor;
import com.limelight.main.MainActivity;
import com.limelight.main.Section;
import com.limelight.preferences.StreamPreferencesFragment;
import com.limelight.settings.SettingsSectionFragment;

import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;

import static org.junit.Assert.*;

/**
 * Profiles are reached from the main screen two ways, by window: the avatar at the rail's foot in
 * a wide one, and the Profiles entry in the Settings section in a compact one, whose bottom bar
 * has no room for the avatar.
 */
@Config(sdk = {33}, shadows = {com.limelight.shadows.ShadowMoonBridge.class, com.limelight.shadows.ShadowGameManager.class})
@RunWith(RobolectricTestRunner.class)
public class ProfilesNavigationTest {

    @BeforeClass
    public static void suppressInvalidIdLogs() {
        TestLogSuppressor.install();
    }

    @Before
    public void prepareEnvironment() {
        MainScreenTestEnvironment.prepare();
    }

    @Test
    @Config(qualifiers = "w900dp-h500dp-land")
    public void wideWindow_railAvatarLaunchesProfilesActivity() {
        MainActivity activity = Robolectric.buildActivity(MainActivity.class).setup().get();
        Shadows.shadowOf(Looper.getMainLooper()).idle();

        activity.findViewById(R.id.navAvatar).performClick();

        Intent next = Shadows.shadowOf(activity).getNextStartedActivity();
        assertNotNull("ProfilesActivity should be launched", next);
        assertEquals(ProfilesActivity.class.getName(), next.getComponent().getClassName());
    }

    @Test
    @Config(qualifiers = "w400dp-h800dp-port")
    public void compactWindow_settingsPreferenceLaunchesProfilesActivity() {
        MainActivity activity = Robolectric.buildActivity(MainActivity.class).setup().get();
        Shadows.shadowOf(Looper.getMainLooper()).idle();
        BottomNavigationView bottom = activity.findViewById(R.id.navBottom);

        bottom.findViewById(R.id.nav_settings).performClick();
        Shadows.shadowOf(Looper.getMainLooper()).idle();
        Fragment section = activity.getSupportFragmentManager()
                .findFragmentByTag(Section.SETTINGS.tag());
        assertNotNull("Settings should show its section", section);
        StreamPreferencesFragment fragment = (StreamPreferencesFragment)
                section.getChildFragmentManager().findFragmentByTag(SettingsSectionFragment.TAG_LIST);
        assertNotNull("The settings list should be up", fragment);
        Preference profiles = fragment.findPreference("pref_profiles");
        assertNotNull("Settings should offer Profiles", profiles);
        assertTrue(profiles.isVisible());

        profiles.getOnPreferenceClickListener().onPreferenceClick(profiles);

        Intent next = Shadows.shadowOf(activity).getNextStartedActivity();
        assertNotNull("ProfilesActivity should be launched from Settings", next);
        assertEquals(ProfilesActivity.class.getName(), next.getComponent().getClassName());
    }

    @Test
    public void profilesActivity_startsWithoutCrash() {
        ProfilesActivity activity = Robolectric.buildActivity(ProfilesActivity.class).setup().get();
        assertNotNull(activity);
    }
}
