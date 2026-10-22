package com.limelight;

import android.app.Application;
import android.widget.Toast;

import com.limelight.binding.video.PyroWaveSupport;
import com.limelight.companion.CompanionDisplayManager;
import com.limelight.grid.assets.MemoryAssetLoader;
import com.limelight.preferences.PreferenceConfiguration;
import com.limelight.profiles.ProfilesManager;
import com.limelight.ui.GamepadPresence;

public class ArtemisApplication extends Application {
    @Override
    public void onCreate() {
        super.onCreate();
        ProfilesManager profilesManager = ProfilesManager.getInstance();
        if (!profilesManager.load(this)) {
            Toast.makeText(this, R.string.profile_manager_failed_to_load, Toast.LENGTH_LONG).show();
        }

        CompanionDisplayManager.install(this);
        GamepadPresence.install(this);

        // The PyroWave probe creates a Vulkan device: run it now, off the UI thread, so that
        // starting a stream finds the result cached
        if (PreferenceConfiguration.isPyroWaveEnabled(this)) {
            PyroWaveSupport.probeInBackground(this);
        }
    }

    // The decoded covers are a process-wide cache that no screen empties when it stops, so they
    // are given back here, where the system's request arrives whichever screen is up (the PC
    // list, a stream) and even when the library screen that filled them is gone.
    @Override
    public void onTrimMemory(int level) {
        super.onTrimMemory(level);
        new MemoryAssetLoader().trimMemory(level);
    }
}