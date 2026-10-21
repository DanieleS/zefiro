package com.limelight.preferences;

import static com.limelight.utils.ServerHelper.getActiveDisplay;

import android.content.res.Configuration;
import android.os.Build;
import android.os.Bundle;
import android.view.Display;

import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.appbar.MaterialToolbar;
import com.limelight.R;
import com.limelight.utils.UiHelper;

/**
 * The settings screen as an activity: a top app bar over a {@link StreamPreferencesFragment}.
 * It hosts the list through {@link SettingsHost}, and builds a new one when the display it is on
 * changes, since the resolutions and frame rates on offer come from the display.
 */
public class StreamSettings extends AppCompatActivity implements SettingsHost {
    private PreferenceConfiguration previousPrefs;
    private int previousDisplayPixelCount;

    void reloadSettings() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            Display.Mode mode = getActiveDisplay(StreamSettings.this, previousPrefs).getMode();
            previousDisplayPixelCount = mode.getPhysicalWidth() * mode.getPhysicalHeight();
        }
        getSupportFragmentManager().beginTransaction().replace(
                R.id.stream_settings, new StreamPreferencesFragment()
        ).commitAllowingStateLoss();
    }

    @Override
    public void reloadPreferences() {
        reloadSettings();
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
//        setTheme(R.style.AppTheme);
        super.onCreate(savedInstanceState);

        previousPrefs = PreferenceConfiguration.readPreferences(this);

        UiHelper.setLocale(this);

        setContentView(R.layout.activity_stream_settings);

        // The top app bar's back arrow does what the system back button does.
        MaterialToolbar toolbar = findViewById(R.id.settings_toolbar);
        toolbar.setNavigationOnClickListener(v -> onBackPressed());

//        UiHelper.notifyNewRootView(this);
    }

    @Override
    public void onAttachedToWindow() {
        super.onAttachedToWindow();

        // The list reads the Android 9 display cutout from its own view once attached.
        reloadSettings();
    }

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            Display.Mode mode = getActiveDisplay(StreamSettings.this, previousPrefs).getMode();

            // If the display's physical pixel count has changed, we consider that it's a new display
            // and we should reload our settings (which include display-dependent values).
            //
            // NB: We aren't using displayId here because that stays the same (DEFAULT_DISPLAY) when
            // switching between screens on a foldable device.
            if (mode.getPhysicalWidth() * mode.getPhysicalHeight() != previousDisplayPixelCount) {
                reloadSettings();
            }
        }
    }
}
