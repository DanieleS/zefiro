package com.limelight.settings;

import android.os.Looper;

import com.limelight.MainScreenTestEnvironment;
import com.limelight.R;
import com.limelight.preferences.StreamPreferencesFragment;
import com.limelight.preferences.StreamSettings;

import org.robolectric.Robolectric;
import org.robolectric.Shadows;

import static org.junit.Assert.assertNotNull;

/**
 * The stream settings list as a user opens it: {@link StreamSettings} started, its
 * {@link StreamPreferencesFragment} built with every device removal and listener, and regrouped.
 */
final class SettingsListFixture {

    private SettingsListFixture() {
    }

    static StreamPreferencesFragment open() {
        MainScreenTestEnvironment.prepare();
        StreamSettings settings = Robolectric.buildActivity(StreamSettings.class).setup().get();
        idle();
        StreamPreferencesFragment fragment = (StreamPreferencesFragment)
                settings.getSupportFragmentManager().findFragmentById(R.id.stream_settings);
        assertNotNull("The settings list should be up", fragment);
        return fragment;
    }

    static void idle() {
        Shadows.shadowOf(Looper.getMainLooper()).idle();
    }
}
