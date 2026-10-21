package com.limelight.settings;

import android.os.Looper;

import androidx.fragment.app.Fragment;

import com.limelight.MainScreenTestEnvironment;
import com.limelight.main.MainActivity;
import com.limelight.main.Section;
import com.limelight.preferences.StreamPreferencesFragment;

import org.robolectric.Robolectric;
import org.robolectric.Shadows;

import static org.junit.Assert.assertNotNull;

/**
 * The stream settings list as a user opens it: the main screen started, its Settings section
 * shown, and the section's {@link StreamPreferencesFragment} built with every device removal and
 * listener, and regrouped.
 */
final class SettingsListFixture {

    private SettingsListFixture() {
    }

    static StreamPreferencesFragment open() {
        return (StreamPreferencesFragment) section().getChildFragmentManager()
                .findFragmentByTag(SettingsSectionFragment.TAG_LIST);
    }

    /** The Settings section on screen, its list up. */
    static SettingsSectionFragment section() {
        MainScreenTestEnvironment.prepare();
        MainActivity activity = Robolectric.buildActivity(MainActivity.class).setup().get();
        idle();
        activity.showSection(Section.SETTINGS);
        idle();
        Fragment section = activity.getSupportFragmentManager().findFragmentByTag(Section.SETTINGS.tag());
        assertNotNull("The Settings section should be up", section);
        assertNotNull("The settings list should be up",
                section.getChildFragmentManager().findFragmentByTag(SettingsSectionFragment.TAG_LIST));
        return (SettingsSectionFragment) section;
    }

    static void idle() {
        Shadows.shadowOf(Looper.getMainLooper()).idle();
    }
}
