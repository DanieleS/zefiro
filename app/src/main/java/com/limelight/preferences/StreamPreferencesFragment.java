package com.limelight.preferences;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.media.MediaCodecInfo;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.app.Activity;
import android.os.Handler;
import android.os.Looper;
import android.os.Vibrator;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.FileProvider;
import androidx.fragment.app.DialogFragment;
import androidx.fragment.app.Fragment;
import androidx.preference.EditTextPreference;
import androidx.preference.ListPreference;
import androidx.preference.Preference;
import androidx.preference.PreferenceCategory;
import androidx.preference.PreferenceFragmentCompat;
import androidx.preference.PreferenceManager;
import androidx.preference.PreferenceScreen;
import androidx.preference.TwoStatePreference;
import androidx.recyclerview.widget.ConcatAdapter;
import androidx.recyclerview.widget.RecyclerView;

import android.text.InputFilter;
import android.text.InputType;
import android.text.TextUtils;
import android.util.DisplayMetrics;
import android.util.Log;
import android.util.Range;
import android.view.Display;
import android.view.DisplayCutout;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.widget.EditText;
import android.widget.Toast;

import com.google.gson.Gson;
import com.limelight.DebugInfoActivity;
import com.limelight.BuildConfig;
import com.limelight.GameMenu;
import com.limelight.LimeLog;
import com.limelight.ProfilesActivity;
import com.limelight.R;
import com.limelight.settings.SettingsAdapter;
import com.limelight.settings.SettingsCatalog;
import com.limelight.settings.SettingsDefaults;
import com.limelight.settings.SettingsRegrouper;
import com.limelight.binding.input.virtual_controller.keyboard.KeyBoardControllerConfigurationLoader;
import com.limelight.binding.video.MediaCodecHelper;
import com.limelight.binding.video.PyroWaveSupport;
import com.limelight.companion.CompanionDisplayManager;
import com.limelight.utils.Dialog;
import com.limelight.computers.ComputerDatabaseManager;
import com.limelight.nvstream.http.ComputerDetails;
import com.limelight.utils.CocoonSync;
import com.limelight.utils.FileUriUtils;
import com.limelight.utils.HelpLauncher;

import java.util.List;
import com.limelight.utils.PerformanceDataTracker;
import com.limelight.utils.UiHelper;
import org.json.JSONObject;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Iterator;
import java.util.Map;

/**
 * The stream settings list: {@code R.xml.preferences}, trimmed to what this device can do, with
 * every listener, dialog and intent the settings have always had.
 *
 * <p>It was the settings activity's own fragment, moved out so the main screen's Settings section
 * ({@code settings.SettingsSectionFragment}) can host it. Whatever hosts it implements
 * {@link SettingsHost} (the parent fragment first, then the activity), which is how a change that
 * needs a fresh list, unlock FPS or a custom resolution, asks for one. {@code EditProfileActivity}
 * extends it to edit a profile in memory.
 *
 * <p>Two orders exist and must not be confused. The XML's categories are the ones the device
 * removals in {@link #initializePreferences()} name ({@code category_gamepad_settings},
 * {@code category_ui_settings}, ...), so they stay as they are. What the screen shows is the
 * {@link SettingsCatalog}'s order: {@link #onPreferencesReady(PreferenceScreen)} runs after the
 * last removal and listener and regroups the surviving preferences into the catalogue's
 * sections. Code that looks a preference up by its category must therefore run before it.
 */
public class StreamPreferencesFragment extends PreferenceFragmentCompat {
    private int nativeResolutionStartIndex = Integer.MAX_VALUE;
    private boolean nativeFramerateShown = false;

    private PreferenceConfiguration prevPrefConfig;

    // HACK for Android 9
    static DisplayCutout displayCutoutP;

    // Binds each row's value and changed dot; kept to refresh a row whose value was written
    // behind its preference's back (the bitrate a resolution change resets).
    private SettingsAdapter settingsAdapter;
    private final SharedPreferences.OnSharedPreferenceChangeListener rowRefresher =
            (sharedPreferences, key) -> {
                if (settingsAdapter != null && key != null) {
                    settingsAdapter.refreshKey(key);
                }
            };

    public StreamPreferencesFragment() {
    }

    /**
     * The configuration the list is built against: the custom resolution and refresh rate it
     * offers as extra entries. The stored settings by default; a profile editor reads its own.
     */
    protected PreferenceConfiguration readPreviousConfig() {
        Context ctx = requireContext();
        return PreferenceConfiguration.readPreferences(ctx, PreferenceManager.getDefaultSharedPreferences(ctx));
    }

    protected SharedPreferences getPrefs() {
        return getPreferenceManager().getSharedPreferences();
    }

    private void setValue(String preferenceKey, String value) {
        ListPreference pref = (ListPreference) findPreference(preferenceKey);

        pref.setValue(value);
    }

    private void appendPreferenceEntry(ListPreference pref, String newEntryName, String newEntryValue) {
        CharSequence[] newEntries = Arrays.copyOf(pref.getEntries(), pref.getEntries().length + 1);
        CharSequence[] newValues = Arrays.copyOf(pref.getEntryValues(), pref.getEntryValues().length + 1);

        // Add the new option
        newEntries[newEntries.length - 1] = newEntryName;
        newValues[newValues.length - 1] = newEntryValue;

        pref.setEntries(newEntries);
        pref.setEntryValues(newValues);
    }

    private void addNativeResolutionEntry(int nativeWidth, int nativeHeight, boolean insetsRemoved, boolean portrait, boolean is_custom) {
        ListPreference pref = (ListPreference) findPreference(PreferenceConfiguration.RESOLUTION_PREF_STRING);

        String newName;

        if (insetsRemoved) {
            newName = getResources().getString(R.string.resolution_prefix_native_fullscreen);
        }
        else {
            newName = is_custom ? getResources().getString(R.string.resolution_prefix_custom) : getResources().getString(R.string.resolution_prefix_native);
        }

        if (PreferenceConfiguration.isSquarishScreen(nativeWidth, nativeHeight)) {
            if (portrait) {
                newName += " " + getResources().getString(R.string.resolution_prefix_native_portrait);
            }
            else {
                newName += " " + getResources().getString(R.string.resolution_prefix_native_landscape);
            }
        }

        newName += " ("+nativeWidth+"x"+nativeHeight+")";

        String newValue = nativeWidth+"x"+nativeHeight;

        // Check if the native resolution is already present
        for (CharSequence value : pref.getEntryValues()) {
            if (newValue.equals(value.toString())) {
                // It is present in the default list, so don't add it again
                return;
            }
        }

        if (pref.getEntryValues().length < nativeResolutionStartIndex) {
            nativeResolutionStartIndex = pref.getEntryValues().length;
        }
        appendPreferenceEntry(pref, newName, newValue);
    }

    private void addNativeResolutionEntries(int nativeWidth, int nativeHeight, boolean insetsRemoved, boolean is_custom) {
        if (PreferenceConfiguration.isSquarishScreen(nativeWidth, nativeHeight)) {
            addNativeResolutionEntry(nativeHeight, nativeWidth, insetsRemoved, true, is_custom);
        }
        addNativeResolutionEntry(nativeWidth, nativeHeight, insetsRemoved, false, is_custom);
    }

    private void addNativeFrameRateEntry(float framerate, boolean is_custom) {
        if (!is_custom) {
            framerate = Math.round(framerate);
            if (framerate == 0) {
                return;
            }
        }

        ListPreference pref = (ListPreference) findPreference(PreferenceConfiguration.FPS_PREF_STRING);
        String fpsValue = is_custom ? Float.toString(framerate) : Integer.toString(Math.round(framerate));
        String fpsName = (is_custom ? getResources().getString(R.string.resolution_prefix_custom) : getResources().getString(R.string.resolution_prefix_native)) +
                " (" + fpsValue + " " + getResources().getString(R.string.fps_suffix_fps) + ")";

        // Check if the native frame rate is already present
        for (CharSequence value : pref.getEntryValues()) {
            if (fpsValue.equals(value.toString())) {
                // It is present in the default list, so don't add it again
                nativeFramerateShown = false;
                return;
            }
        }

        appendPreferenceEntry(pref, fpsName, fpsValue);
        nativeFramerateShown = true;
    }

    private void removeValue(String preferenceKey, String value, Runnable onMatched) {
        int matchingCount = 0;

        ListPreference pref = (ListPreference) findPreference(preferenceKey);

        // Count the number of matching entries we'll be removing
        for (CharSequence seq : pref.getEntryValues()) {
            if (seq.toString().equalsIgnoreCase(value)) {
                matchingCount++;
            }
        }

        // Create the new arrays
        CharSequence[] entries = new CharSequence[pref.getEntries().length-matchingCount];
        CharSequence[] entryValues = new CharSequence[pref.getEntryValues().length-matchingCount];
        int outIndex = 0;
        for (int i = 0; i < pref.getEntryValues().length; i++) {
            if (pref.getEntryValues()[i].toString().equalsIgnoreCase(value)) {
                // Skip matching values
                continue;
            }

            entries[outIndex] = pref.getEntries()[i];
            entryValues[outIndex] = pref.getEntryValues()[i];
            outIndex++;
        }

        if (pref.getValue().equalsIgnoreCase(value)) {
            onMatched.run();
        }

        // Update the preference with the new list
        pref.setEntries(entries);
        pref.setEntryValues(entryValues);
    }

    private void resetBitrateToDefault(SharedPreferences prefs, String res, String fps) {
        if (res == null) {
            res = prefs.getString(PreferenceConfiguration.RESOLUTION_PREF_STRING, PreferenceConfiguration.DEFAULT_RESOLUTION);
        }
        if (fps == null) {
            fps = prefs.getString(PreferenceConfiguration.FPS_PREF_STRING, PreferenceConfiguration.DEFAULT_FPS);
        }

        prefs.edit()
                .putInt(PreferenceConfiguration.BITRATE_PREF_STRING,
                        PreferenceConfiguration.getDefaultBitrate(res, fps))
                .apply();
    }

    @NonNull
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        View view = super.onCreateView(inflater, container, savedInstanceState);
        UiHelper.applyStatusBarPadding(view);
        return view;
    }

    public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState, boolean unused) {
        return super.onCreateView(inflater, container, savedInstanceState);
    }

    @Override
    public void onCreatePreferences(Bundle bundle, String s) {
        prevPrefConfig = readPreviousConfig();
        initializePreferences();
    }

    @Override
    public void onViewCreated(@NonNull View view, Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        // We have to use this hack on Android 9 because we don't have Display.getCutout()
        // which was added in Android 10. The insets only exist once the view is in a window
        // (they can be null when the activity is recreated on screen rotation), so they are read
        // on attach rather than from whichever activity hosts this list.
        if (Build.VERSION.SDK_INT == Build.VERSION_CODES.P) {
            if (view.isAttachedToWindow()) {
                captureDisplayCutout(view);
            } else {
                view.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener() {
                    @Override
                    public void onViewAttachedToWindow(View v) {
                        v.removeOnAttachStateChangeListener(this);
                        captureDisplayCutout(v);
                    }

                    @Override
                    public void onViewDetachedFromWindow(View v) {
                    }
                });
            }
        }

        SharedPreferences prefs = getPreferenceManager().getSharedPreferences();
        if (prefs != null) {
            prefs.registerOnSharedPreferenceChangeListener(rowRefresher);
        }
    }

    @Override
    public void onDestroyView() {
        SharedPreferences prefs = getPreferenceManager().getSharedPreferences();
        if (prefs != null) {
            prefs.unregisterOnSharedPreferenceChangeListener(rowRefresher);
        }
        settingsAdapter = null;
        super.onDestroyView();
    }

    /**
     * Keeps the Android 9 cutout for the native resolution entries. The list was built before
     * this view had a window, so the first time a cutout turns up the list is built again to
     * offer the native resolution that leaves the cutout out; later lists already know it.
     */
    private void captureDisplayCutout(View view) {
        WindowInsets insets = view.getRootWindowInsets();
        if (insets == null) {
            return;
        }
        DisplayCutout cutout = insets.getDisplayCutout();
        boolean firstSeen = displayCutoutP == null && cutout != null;
        displayCutoutP = cutout;
        if (firstSeen) {
            reloadSettings();
        }
    }

    /**
     * A host that puts something of its own at the top of the list, scrolling with it: the
     * Settings section's header in a compact window. Found on the parent fragment.
     */
    public interface ListHeader {
        /** The adapter for the rows above the settings; stable ids, as the settings' are. */
        @NonNull
        RecyclerView.Adapter<?> listHeaderAdapter();
    }

    /**
     * The list's adapter: androidx's, plus each row's value on the right and the dot that marks
     * a value away from its default; under a {@link ListHeader}'s rows when the host has some.
     * With those, positions in the list are not the settings adapter's, which is why
     * {@code scrollToPreference} (it refuses any adapter but androidx's) is not used then: the
     * host offsets {@link SettingsAdapter#getPreferenceAdapterPosition(String)} itself.
     */
    @NonNull
    @Override
    protected RecyclerView.Adapter onCreateAdapter(@NonNull PreferenceScreen preferenceScreen) {
        settingsAdapter = new SettingsAdapter(preferenceScreen,
                SettingsDefaults.read(requireContext()), getPrefs());
        Fragment parent = getParentFragment();
        if (parent instanceof ListHeader) {
            ConcatAdapter.Config config = new ConcatAdapter.Config.Builder()
                    .setIsolateViewTypes(true)
                    .setStableIdMode(ConcatAdapter.Config.StableIdMode.ISOLATED_STABLE_IDS)
                    .build();
            return new ConcatAdapter(config, ((ListHeader) parent).listHeaderAdapter(),
                    settingsAdapter);
        }
        return settingsAdapter;
    }

    /** The settings rows' adapter, once the list has one; null before its view or after it. */
    @Nullable
    public SettingsAdapter getSettingsAdapter() {
        return settingsAdapter;
    }

    /**
     * Called once at the very end of {@link #initializePreferences()}, after every device
     * removal and listener, with the screen as the user will see it. By default it regroups the
     * screen into the catalogue's sections.
     */
    protected void onPreferencesReady(@NonNull PreferenceScreen screen) {
        SettingsRegrouper.apply(screen, SettingsCatalog.get());
    }

    @Override
    public void onResume() {
        super.onResume();
        // The companion screen can be switched on or off further up this list, or shown and
        // hidden from the library while this screen was away.
        refreshCompanionScreenEntry();
    }

    /**
     * Profiles, Help and the companion screen's show/hide: what the old PC list's top bar
     * offered, given a home here now that the main screen keeps only Settings beside its
     * sections (the rail's profile avatar stays a shortcut to Profiles in a wide window).
     */
    private void setupFormerTopBarEntries() {
        Preference profiles = findPreference("pref_profiles");
        if (profiles != null) {
            profiles.setOnPreferenceClickListener(preference -> {
                startActivity(new Intent(requireActivity(), ProfilesActivity.class));
                return true;
            });
        }

        Preference help = findPreference("pref_help");
        if (help != null) {
            // Amazon review didn't like Help because the wiki is not entirely navigable with
            // the Fire TV remote, so the old top bar hid it there; so does this list.
            if (requireActivity().getPackageManager().hasSystemFeature("amazon.hardware.fire_tv")) {
                help.setVisible(false);
            } else {
                help.setOnPreferenceClickListener(preference -> {
                    HelpLauncher.launchSetupGuide(requireActivity());
                    return true;
                });
            }
        }

        Preference companion = findPreference("pref_companion_screen");
        if (companion != null) {
            companion.setOnPreferenceClickListener(preference -> {
                // Decide from the same source the title reads: toggle() flips on the
                // remembered owner, which with no second display stays PANEL while nothing
                // shows, so a "Show" row would silently hide on every other click.
                CompanionDisplayManager.setVisible(!CompanionDisplayManager.isShowing());
                refreshCompanionScreenEntry();
                return true;
            });
        }
    }

    /**
     * Shows the companion row only while one is configured, titled by what a click does. Public
     * for a host that is shown and hidden rather than resumed (the Settings section), and that
     * hears the panel come and go while this list stays up.
     */
    public void refreshCompanionScreenEntry() {
        Preference companion = findPreference("pref_companion_screen");
        if (companion == null || getActivity() == null) {
            return;
        }
        boolean configured = CompanionDisplayManager.isConfigured(requireActivity());
        companion.setVisible(configured);
        if (configured) {
            // Read back rather than flipped by hand: the toggle applies at once, but a show
            // with no second display to put the panel on does not take.
            companion.setTitle(CompanionDisplayManager.isShowing()
                    ? R.string.action_companion_hide : R.string.action_companion_show);
        }
    }

    public void initializePreferences() {
        addPreferencesFromResource(R.xml.preferences);
        PreferenceScreen screen = getPreferenceScreen();

        AppCompatActivity activity = (AppCompatActivity) requireActivity();
        PackageManager pm = activity.getPackageManager();

        // hide on-screen controls category on non touch screen devices
        if (!pm.hasSystemFeature(PackageManager.FEATURE_TOUCHSCREEN)) {
            PreferenceCategory category = findPreference("category_onscreen_controls");
            if (category != null) {
                screen.removePreference(category);
            }
            category = findPreference("category_special_key_layout");
            if (category != null) {
                screen.removePreference(category);
            }
        }

        // Hide remote desktop mouse mode on pre-Oreo (which doesn't have pointer capture)
        // and NVIDIA SHIELD devices (which support raw mouse input in pointer capture mode)
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O ||
                getActivity().getPackageManager().hasSystemFeature("com.nvidia.feature.shield")) {
            PreferenceCategory category =
                    (PreferenceCategory) findPreference("category_input_settings");
            category.removePreference(findPreference("checkbox_absolute_mouse_mode"));
        }

        // Hide gamepad motion sensor option when running on OSes before Android 12.
        // Support for motion, LED, battery, and other extensions were introduced in S.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            PreferenceCategory category =
                    (PreferenceCategory) findPreference("category_gamepad_settings");
            category.removePreference(findPreference("checkbox_gamepad_motion_sensors"));
        }

        // Hide gamepad motion sensor fallback option if the device has no gyro or accelerometer
        if (!pm.hasSystemFeature(PackageManager.FEATURE_SENSOR_ACCELEROMETER) &&
                !activity.getPackageManager().hasSystemFeature(PackageManager.FEATURE_SENSOR_GYROSCOPE)) {
            PreferenceCategory category =
                    (PreferenceCategory) findPreference("category_gamepad_settings");
            category.removePreference(findPreference("checkbox_force_device_motion"));
            category.removePreference(findPreference("checkbox_gamepad_motion_fallback"));
        }

        // Hide USB driver options on devices without USB host support
        if (!pm.hasSystemFeature(PackageManager.FEATURE_USB_HOST)) {
            PreferenceCategory category =
                    (PreferenceCategory) findPreference("category_gamepad_settings");
            category.removePreference(findPreference("checkbox_usb_bind_all"));
            category.removePreference(findPreference("checkbox_usb_driver"));
        }

        // Remove PiP mode on devices pre-Oreo, where the feature is not available (some low RAM devices),
        // and on Fire OS where it violates the Amazon App Store guidelines for some reason.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O ||
                !pm.hasSystemFeature("android.software.picture_in_picture") ||
                pm.hasSystemFeature("com.amazon.software.fireos")) {
            PreferenceCategory category =
                    (PreferenceCategory) findPreference("category_ui_settings");
            category.removePreference(findPreference("checkbox_enable_pip"));
        }

        // PyroWave needs the arm64 renderer and a Vulkan 1.3 GPU. Only the cheap check runs
        // here; the stream does the full one (a Vulkan device and a decoder) when enabled.
        if (!PyroWaveSupport.isCandidateDevice(activity)) {
            PreferenceCategory category =
                    (PreferenceCategory) findPreference("category_advanced_settings");
            category.removePreference(findPreference("checkbox_enable_pyrowave"));
            category.removePreference(findPreference("seekbar_pyrowave_bitrate_kbps"));
            // Without them, the codec setting alone is shown before "more", as before PyroWave
            category.setInitialExpandedChildrenCount(1);
        }
        else {
            // Probe now, off the UI thread, rather than when the first stream starts
            findPreference("checkbox_enable_pyrowave").setOnPreferenceChangeListener((preference, newValue) -> {
                if (Boolean.TRUE.equals(newValue)) {
                    PyroWaveSupport.probeInBackground(activity);
                }
                return true;
            });
        }

        // Fire TV apps are not allowed to use WebViews or browsers, so hide the Help category
        /*if (getActivity().getPackageManager().hasSystemFeature("amazon.hardware.fire_tv")) {
            PreferenceCategory category =
                    (PreferenceCategory) findPreference("category_help");
            screen.removePreference(category);
        }*/
        PreferenceCategory category_gamepad_settings =
                (PreferenceCategory) findPreference("category_gamepad_settings");
        // Remove the vibration options if the device can't vibrate
        if (!((Vibrator)getActivity().getSystemService(Context.VIBRATOR_SERVICE)).hasVibrator()) {
            category_gamepad_settings.removePreference(findPreference("checkbox_vibrate_fallback"));
            category_gamepad_settings.removePreference(findPreference("seekbar_vibrate_fallback_strength"));
            // The entire OSC category may have already been removed by the touchscreen check above
            PreferenceCategory category = findPreference("category_onscreen_controls");
            if (category != null) {
                category.removePreference(findPreference("checkbox_vibrate_osc"));
            }
            category = findPreference("category_special_key_layout");
            if (category != null) {
                category.removePreference(findPreference("checkbox_vibrate_keyboard"));
            }
            category = findPreference("category_gamepad_settings");
            if (category != null) {
                category.removePreference(findPreference("checkbox_enable_device_rumble"));
            }
        }
        else if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O ||
                !((Vibrator)getActivity().getSystemService(Context.VIBRATOR_SERVICE)).hasAmplitudeControl()) {
            // Remove the vibration strength selector of the device doesn't have amplitude control
            category_gamepad_settings.removePreference(findPreference("seekbar_vibrate_fallback_strength"));
        }

        // Check custom resolution
        String customResStr = prevPrefConfig.customResolution;
        if(customResStr != null && !customResStr.isEmpty()){
            String[] resolutionSegments = customResStr.split("x");
            if(resolutionSegments.length == 2){
                try {
                    addNativeResolutionEntries(Integer.parseInt(resolutionSegments[0]), Integer.parseInt(resolutionSegments[1]), false, true);
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }
        }
        
        // Check custom refresh rate
        String customRefreshRateStr = prevPrefConfig.customRefreshRate;
        if (customRefreshRateStr != null && !customRefreshRateStr.isEmpty()) {
            try {
                float customRefreshRateValue = Float.parseFloat(customRefreshRateStr);
                if (customRefreshRateValue > 0) {
                    addNativeFrameRateEntry(customRefreshRateValue, true);
                }
            } catch (NumberFormatException e) {
                getPrefs().edit().remove(PreferenceConfiguration.CUSTOM_REFRESH_RATE_PREF_STRING).apply();
            }
        }

        Display display = activity.getWindowManager().getDefaultDisplay();
        float maxSupportedFps = display.getRefreshRate();

        // Hide non-supported resolution/FPS combinations
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            int maxSupportedResW = 0;

            // Add a native resolution with any insets included for users that don't want content
            // behind the notch of their display
            boolean hasInsets = false;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                DisplayCutout cutout;

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    // Use the much nicer Display.getCutout() API on Android 10+
                    cutout = display.getCutout();
                }
                else {
                    // Android 9 only
                    cutout = displayCutoutP;
                }

                if (cutout != null) {
                    int widthInsets = cutout.getSafeInsetLeft() + cutout.getSafeInsetRight();
                    int heightInsets = cutout.getSafeInsetBottom() + cutout.getSafeInsetTop();

                    if (widthInsets != 0 || heightInsets != 0) {
                        DisplayMetrics metrics = new DisplayMetrics();
                        display.getRealMetrics(metrics);

                        int width = Math.max(metrics.widthPixels - widthInsets, metrics.heightPixels - heightInsets);
                        int height = Math.min(metrics.widthPixels - widthInsets, metrics.heightPixels - heightInsets);

                        addNativeResolutionEntries(width, height, false, false);
                        hasInsets = true;
                    }
                }
            }

            // Always allow resolutions that are smaller or equal to the active
            // display resolution because decoders can report total non-sense to us.
            // For example, a p201 device reports:
            // AVC Decoder: OMX.amlogic.avc.decoder.awesome
            // HEVC Decoder: OMX.amlogic.hevc.decoder.awesome
            // AVC supported width range: 64 - 384
            // HEVC supported width range: 64 - 544
            for (Display.Mode candidate : display.getSupportedModes()) {
                // Some devices report their dimensions in the portrait orientation
                // where height > width. Normalize these to the conventional width > height
                // arrangement before we process them.

                int width = Math.max(candidate.getPhysicalWidth(), candidate.getPhysicalHeight());
                int height = Math.min(candidate.getPhysicalWidth(), candidate.getPhysicalHeight());

                // Some TVs report strange values here, so let's avoid native resolutions on a TV
                // unless they report greater than 4K resolutions.
                if (!activity.getPackageManager().hasSystemFeature(PackageManager.FEATURE_TELEVISION) ||
                        (width > 3840 || height > 2160)) {
                    addNativeResolutionEntries(width, height, hasInsets, false);
                }

                if ((width >= 3840 || height >= 2160) && maxSupportedResW < 3840) {
                    maxSupportedResW = 3840;
                }
                else if ((width >= 2560 || height >= 1440) && maxSupportedResW < 2560) {
                    maxSupportedResW = 2560;
                }
                else if ((width >= 1920 || height >= 1080) && maxSupportedResW < 1920) {
                    maxSupportedResW = 1920;
                }

                if (candidate.getRefreshRate() > maxSupportedFps) {
                    maxSupportedFps = candidate.getRefreshRate();
                }
            }

            // This must be called to do runtime initialization before calling functions that evaluate
            // decoder lists.
            MediaCodecHelper.initialize(getContext(), GlPreferences.readPreferences(requireContext()).glRenderer);

            MediaCodecInfo avcDecoder = MediaCodecHelper.findProbableSafeDecoder("video/avc", -1);
            MediaCodecInfo hevcDecoder = MediaCodecHelper.findProbableSafeDecoder("video/hevc", -1);

            if (avcDecoder != null) {
                Range<Integer> avcWidthRange = avcDecoder.getCapabilitiesForType("video/avc").getVideoCapabilities().getSupportedWidths();

                LimeLog.info("AVC supported width range: "+avcWidthRange.getLower()+" - "+avcWidthRange.getUpper());

                // If 720p is not reported as supported, ignore all results from this API
                if (avcWidthRange.contains(1280)) {
                    if (avcWidthRange.contains(3840) && maxSupportedResW < 3840) {
                        maxSupportedResW = 3840;
                    }
                    else if (avcWidthRange.contains(1920) && maxSupportedResW < 1920) {
                        maxSupportedResW = 1920;
                    }
                    else if (maxSupportedResW < 1280) {
                        maxSupportedResW = 1280;
                    }
                }
            }

            if (hevcDecoder != null) {
                Range<Integer> hevcWidthRange = hevcDecoder.getCapabilitiesForType("video/hevc").getVideoCapabilities().getSupportedWidths();

                LimeLog.info("HEVC supported width range: "+hevcWidthRange.getLower()+" - "+hevcWidthRange.getUpper());

                // If 720p is not reported as supported, ignore all results from this API
                if (hevcWidthRange.contains(1280)) {
                    if (hevcWidthRange.contains(3840) && maxSupportedResW < 3840) {
                        maxSupportedResW = 3840;
                    }
                    else if (hevcWidthRange.contains(1920) && maxSupportedResW < 1920) {
                        maxSupportedResW = 1920;
                    }
                    else if (maxSupportedResW < 1280) {
                        maxSupportedResW = 1280;
                    }
                }
            }

            LimeLog.info("Maximum resolution slot: "+maxSupportedResW);

            if (maxSupportedResW != 0) {
                if (maxSupportedResW < 3840) {
                    // 4K is unsupported
                    removeEntryFromListAndSetValue(PreferenceConfiguration.RESOLUTION_PREF_STRING, PreferenceConfiguration.RES_4K, PreferenceConfiguration.RES_1440P);
                }
                if (maxSupportedResW < 2560) {
                    // 1440p is unsupported
                    removeEntryFromListAndSetValue(PreferenceConfiguration.RESOLUTION_PREF_STRING, PreferenceConfiguration.RES_1440P, PreferenceConfiguration.RES_1080P);
                }
                if (maxSupportedResW < 1920) {
                    // 1080p is unsupported
                    removeEntryFromListAndSetValue(PreferenceConfiguration.RESOLUTION_PREF_STRING, PreferenceConfiguration.RES_1080P, PreferenceConfiguration.RES_720P);
                }
                // Never remove 720p
            }
        }
        else {
            // We can get the true metrics via the getRealMetrics() function (unlike the lies
            // that getWidth() and getHeight() tell to us).
            DisplayMetrics metrics = new DisplayMetrics();
            display.getRealMetrics(metrics);
            int width = Math.max(metrics.widthPixels, metrics.heightPixels);
            int height = Math.min(metrics.widthPixels, metrics.heightPixels);
            addNativeResolutionEntries(width, height, false, false);
        }

        if (!PreferenceConfiguration.readPreferences(this.getActivity()).unlockFps) {
            // We give some extra room in case the FPS is rounded down
            if (maxSupportedFps < 118) {
                removeEntryFromListAndSetValue(PreferenceConfiguration.FPS_PREF_STRING, "120", "90");
            }
            if (maxSupportedFps < 88) {
                // 1080p is unsupported
                removeEntryFromListAndSetValue(PreferenceConfiguration.FPS_PREF_STRING, "90", "60");
            }
            // Never remove 30 FPS or 60 FPS
        }
        addNativeFrameRateEntry(maxSupportedFps, false);

        // Android L introduces the drop duplicate behavior of releaseOutputBuffer()
        // that the unlock FPS option relies on to not massively increase latency.
        findPreference(PreferenceConfiguration.UNLOCK_FPS_STRING).setOnPreferenceChangeListener(new Preference.OnPreferenceChangeListener() {
            @Override
            public boolean onPreferenceChange(Preference preference, Object newValue) {
                // HACK: We need to let the preference change succeed before reinitializing to ensure
                // it's reflected in the new layout.
                reloadSettings();

                // Allow the original preference change to take place
                return true;
            }
        });

        // Remove HDR preference for devices below Nougat
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) {
            LimeLog.info("Excluding HDR toggle based on OS");
            PreferenceCategory category =
                    (PreferenceCategory) findPreference("category_video_settings");
            category.removePreference(findPreference("checkbox_enable_hdr"));
        }
        else {
            Display.HdrCapabilities hdrCaps = display.getHdrCapabilities();
            Log.d("HDR CAP", display + "");
            // We must now ensure our display is compatible with HDR10
            boolean foundHdr10 = false;
            if (hdrCaps != null) {
                // getHdrCapabilities() returns null on Lenovo Lenovo Mirage Solo (vega), Android 8.0
                for (int hdrType : hdrCaps.getSupportedHdrTypes()) {
                    if (hdrType == Display.HdrCapabilities.HDR_TYPE_HDR10) {
                        foundHdr10 = true;
                        break;
                    }
                }
            }

            if (!foundHdr10) {
                LimeLog.info("Excluding HDR toggle based on display capabilities");
                PreferenceCategory category =
                        (PreferenceCategory) findPreference("category_video_settings");
                category.removePreference(findPreference("checkbox_enable_hdr"));
            }
            else if (PreferenceConfiguration.isShieldAtvFirmwareWithBrokenHdr()) {
                LimeLog.info("Disabling HDR toggle on old broken SHIELD TV firmware");
                PreferenceCategory category =
                        (PreferenceCategory) findPreference("category_video_settings");
                TwoStatePreference hdrPref = (TwoStatePreference) category.findPreference("checkbox_enable_hdr");
                hdrPref.setEnabled(false);
                hdrPref.setChecked(false);
                hdrPref.setSummary("Update the firmware on your NVIDIA SHIELD Android TV to enable HDR");
            }
        }

        // Sharpness only applies to FSR 1.0 and SGSR 1
        final Preference upscalingMode = findPreference(PreferenceConfiguration.UPSCALING_PREF_STRING);
        final Preference upscalingSharpness = findPreference(PreferenceConfiguration.UPSCALING_SHARPNESS_PREF_STRING);
        if (upscalingMode instanceof ListPreference && upscalingSharpness != null) {
            String mode = ((ListPreference) upscalingMode).getValue();
            upscalingSharpness.setEnabled("fsr1".equals(mode) || "sgsr1".equals(mode));
            upscalingMode.setOnPreferenceChangeListener(new Preference.OnPreferenceChangeListener() {
                @Override
                public boolean onPreferenceChange(Preference preference, Object newValue) {
                    upscalingSharpness.setEnabled("fsr1".equals(newValue) || "sgsr1".equals(newValue));
                    return true;
                }
            });
        }

        // Add a listener to the FPS and resolution preference
        // so the bitrate can be auto-adjusted
        findPreference(PreferenceConfiguration.RESOLUTION_PREF_STRING).setOnPreferenceChangeListener(new Preference.OnPreferenceChangeListener() {
            @Override
            public boolean onPreferenceChange(Preference preference, Object newValue) {
                SharedPreferences prefs = getPrefs();
                String valueStr = (String) newValue;

                // Detect if this value is the native resolution option
                CharSequence[] values = ((ListPreference)preference).getEntryValues();
                boolean isNativeRes = true;
                for (int i = 0; i < values.length; i++) {
                    // Look for a match prior to the start of the native resolution entries
                    if (valueStr.equals(values[i].toString()) && i < nativeResolutionStartIndex) {
                        isNativeRes = false;
                        break;
                    }
                }

                // If this is native resolution, show the warning dialog
                if (isNativeRes) {
                    Dialog.displayDialog(getActivity(),
                            getResources().getString(R.string.title_native_res_dialog),
                            getResources().getString(R.string.text_native_res_dialog),
                            false);
                }

                // Write the new bitrate value
                resetBitrateToDefault(prefs, valueStr, null);

                // Allow the original preference change to take place
                return true;
            }
        });
        findPreference(PreferenceConfiguration.FPS_PREF_STRING).setOnPreferenceChangeListener(new Preference.OnPreferenceChangeListener() {
            @Override
            public boolean onPreferenceChange(Preference preference, Object newValue) {
                SharedPreferences prefs = getPrefs();
                String valueStr = (String) newValue;

                // If this is native frame rate, show the warning dialog
                CharSequence[] values = ((ListPreference)preference).getEntryValues();
                if (nativeFramerateShown && values[values.length - 1].toString().equals(newValue.toString())) {
                    Dialog.displayDialog(getActivity(),
                            getResources().getString(R.string.title_native_fps_dialog),
                            getResources().getString(R.string.text_native_res_dialog),
                            false);
                }

                // Write the new bitrate value
                resetBitrateToDefault(prefs, null, valueStr);

                // Allow the original preference change to take place
                return true;
            }
        });

        findPreference("checkbox_enable_perf_logging").setOnPreferenceChangeListener(new Preference.OnPreferenceChangeListener() {
            @Override
            public boolean onPreferenceChange(Preference preference, Object newValue) {
                Boolean loggingEnabled = (Boolean) newValue;

                if(!loggingEnabled) {
                    new PerformanceDataTracker().clearLogs(preference.getContext());
                }

                // Allow the original preference change to take place
                return true;
            }
        });

        Preference _pref;
        _pref = findPreference("import_keyboard_file");
        if (_pref != null) {
            _pref.setOnPreferenceClickListener(new Preference.OnPreferenceClickListener() {
                @Override
                public boolean onPreferenceClick(Preference preference) {
                    Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
                    intent.addCategory(Intent.CATEGORY_OPENABLE);
                    intent.setType("application/json");
                    startActivityForResult(intent, READ_REQUEST_CODE);
                    return false;
                }
            });
        }

        _pref = findPreference("import_special_button_file");
        if (_pref != null) {
            _pref.setOnPreferenceClickListener(new Preference.OnPreferenceClickListener() {
                @Override
                public boolean onPreferenceClick(Preference preference) {
                    Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
                    intent.addCategory(Intent.CATEGORY_OPENABLE);
                    intent.setType("application/json");
                    startActivityForResult(intent, READ_REQUEST_SPECIAL_CODE);
                    return false;
                }
            });
        }

        setupCocoonSync();

        _pref = findPreference("share_performance_logs");
        if (_pref != null) {
            _pref.setOnPreferenceClickListener(new Preference.OnPreferenceClickListener() {
                @Override
                public boolean onPreferenceClick(Preference preference) {
                    Context context = preference.getContext();
                    PerformanceDataTracker tracker = new PerformanceDataTracker();
                    String logs = tracker.getLog(context);

                    if (logs == null || logs.trim().isEmpty()) {
                        Toast.makeText(context, context.getString(R.string.toast_no_logs), Toast.LENGTH_SHORT).show();
                        return false;
                    }

                    String prefixMessage = context.getString(R.string.email_prefix_message);
                    String emailRecipient = context.getString(R.string.email_recipient);
                    String emailSubject = context.getString(R.string.email_subject);
                    String chooserTitle = context.getString(R.string.email_chooser_title);
                    String noEmailClientsMsg = context.getString(R.string.toast_no_email_clients);

                    try {
                        File cacheDir = context.getCacheDir();
                        File logFile = new File(cacheDir, "artemistics_logs.txt");
                        try (FileOutputStream fos = new FileOutputStream(logFile)) {
                            fos.write(logs.getBytes(StandardCharsets.UTF_8));
                        }

                        Uri logFileUri = FileProvider.getUriForFile(context,
                                context.getPackageName() + ".fileprovider",
                                logFile);

                        Intent emailIntent = new Intent(Intent.ACTION_SEND);
                        emailIntent.setType("text/plain");
                        emailIntent.putExtra(Intent.EXTRA_EMAIL, new String[]{emailRecipient});
                        emailIntent.putExtra(Intent.EXTRA_SUBJECT, emailSubject);
                        emailIntent.putExtra(Intent.EXTRA_TEXT, prefixMessage);
                        emailIntent.putExtra(Intent.EXTRA_STREAM, logFileUri);

                        emailIntent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);

                        context.startActivity(Intent.createChooser(emailIntent, chooserTitle));
                    } catch (IOException e) {
                        Log.d("PerformanceDataTracker", "Error creating log file");
                    } catch (android.content.ActivityNotFoundException ex) {
                        Toast.makeText(context, noEmailClientsMsg, Toast.LENGTH_SHORT).show();
                    }
                    return false;
                }
            });
        }

        _pref = findPreference("export_keyboard_file");
        if (_pref != null) {
            _pref.setOnPreferenceClickListener(new Preference.OnPreferenceClickListener() {
                @Override
                public boolean onPreferenceClick(Preference preference) {
                    File file = new File(requireActivity().getExternalCacheDir(),"export_settings");
                    if(!file.exists()){
                        file.mkdir();
                    }
                    File file1= getJsonContent(requireActivity(),file);
                    if(file1==null){
                        Toast.makeText(requireActivity(),getString(R.string.pref_error_occurred),Toast.LENGTH_SHORT).show();
                        return false;
                    }
                    Uri uri;
                    Intent intent = new Intent(Intent.ACTION_SEND);
                    intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                    String authority= BuildConfig.APPLICATION_ID+".fileprovider";
                    uri = FileProvider.getUriForFile(requireActivity(),authority,file1);
                    intent.putExtra(Intent.EXTRA_STREAM, uri);
                    intent.setType("application/json");
                    startActivity(Intent.createChooser(intent,getString(R.string.pref_save_keyboard_profile)));
                    return false;
                }
            });
        }

        _pref = findPreference("pref_debug_info");
        if (_pref != null) {
            _pref.setOnPreferenceClickListener(new Preference.OnPreferenceClickListener() {
                @Override
                public boolean onPreferenceClick(@NonNull Preference preference) {
                    Intent intent=new Intent(requireActivity(), DebugInfoActivity.class);
                    requireActivity().startActivity(intent);
                    return false;
                }
            });
        }

        setupFormerTopBarEntries();

        EditTextPreference bitrateEditPref = findPreference(PreferenceConfiguration.CUSTOM_BITRATE_PREF_STRING);
        if (bitrateEditPref != null) {
            bitrateEditPref.setOnBindEditTextListener((EditText editText) -> {
                editText.setInputType(InputType.TYPE_NUMBER_FLAG_DECIMAL);
                editText.setFilters(new InputFilter[]{new InputFilter.LengthFilter(5)});
            });

            bitrateEditPref.setOnPreferenceChangeListener((preference, newValue) -> {
                String value = (String) newValue;
                if (TextUtils.isEmpty(value)) {
                    Toast.makeText(getActivity(), getString(R.string.pref_enter_value_0_9999), Toast.LENGTH_SHORT).show();
                    return false;
                }
                float bitrateValue = Float.parseFloat(value) * 1000;
                int bitrate = (int) bitrateValue;
                SharedPreferences prefs = getPrefs();
                prefs.edit().putInt(PreferenceConfiguration.BITRATE_PREF_STRING, bitrate).apply();
                Toast.makeText(getActivity(), getString(R.string.pref_set_success), Toast.LENGTH_SHORT).show();
                return true;
            });
        }

        EditTextPreference resolutionEditPref = findPreference(PreferenceConfiguration.CUSTOM_RESOLUTION_PREF_STRING);
        if (resolutionEditPref != null) {
            resolutionEditPref.setOnBindEditTextListener((EditText editText) -> {
                editText.setInputType(InputType.TYPE_CLASS_TEXT);
                editText.setFilters(new InputFilter[]{new InputFilter.LengthFilter(11)});
            });

            resolutionEditPref.setOnPreferenceChangeListener((preference, newValue) -> {
                String value = (String) newValue;
                if (TextUtils.isEmpty(value)) {
                    Toast.makeText(getActivity(), getString(R.string.pref_enter_value_0_9999), Toast.LENGTH_SHORT).show();
                    return false;
                }

                // Verify format: [width]x[height]
                String[] resolutionSegments = value.split("x");
                if (resolutionSegments.length != 2) {
                    Toast.makeText(getActivity(), getString(R.string.pref_error_occurred), Toast.LENGTH_SHORT).show();
                    return false;
                }

                try {
                    int width = Integer.parseInt(resolutionSegments[0]);
                    int height = Integer.parseInt(resolutionSegments[1]);
                    
                    if (width <= 0 || height <= 0) {
                        Toast.makeText(getActivity(), getString(R.string.pref_error_occurred), Toast.LENGTH_SHORT).show();
                        return false;
                    }

                    // Save the value and reload settings
                    editAndReload(PreferenceConfiguration.CUSTOM_RESOLUTION_PREF_STRING, value);

                    return true;
                } catch (NumberFormatException e) {
                    Toast.makeText(getActivity(), getString(R.string.pref_error_occurred), Toast.LENGTH_SHORT).show();
                    return false;
                }
            });
        }

        EditTextPreference customRefreshRatePref = findPreference(PreferenceConfiguration.CUSTOM_REFRESH_RATE_PREF_STRING);
        if (customRefreshRatePref != null) {
            customRefreshRatePref.setOnBindEditTextListener((EditText editText) -> {
                editText.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL);
                editText.setFilters(new InputFilter[]{new InputFilter.LengthFilter(7)});
            });

            customRefreshRatePref.setOnPreferenceChangeListener((preference, newValue) -> {
                String value = (String) newValue;
                if (TextUtils.isEmpty(value)) {
                    Toast.makeText(getActivity(), getString(R.string.pref_enter_value_0_9999), Toast.LENGTH_SHORT).show();
                    return false;
                }
                
                try {
                    float refreshRate = Float.parseFloat(value);
                    if (refreshRate <= 0) {
                        Toast.makeText(getActivity(), getString(R.string.pref_enter_value_0_9999), Toast.LENGTH_SHORT).show();
                        return false;
                    }
                    
                    // Format to max 3 decimal places
                    String formattedValue = String.format("%.3f", refreshRate);
                    // Remove trailing zeros
                    formattedValue = formattedValue.replaceAll("0+$", "").replaceAll("\\.$", "");

                    editAndReload(PreferenceConfiguration.CUSTOM_REFRESH_RATE_PREF_STRING, formattedValue);

                    return true;
                } catch (NumberFormatException e) {
                    Toast.makeText(getActivity(), getString(R.string.pref_error_occurred), Toast.LENGTH_SHORT).show();
                    return false;
                }
            });
        }

        // Last, so every removal above has found its preference in the category it names.
        onPreferencesReady(screen);
    }

    private void removeEntryFromListAndSetValue(String resolutionPrefString, String entryToRemove, String nextDefault) {
        removeValue(resolutionPrefString, entryToRemove, new Runnable() {
            @Override
            public void run() {
                SharedPreferences prefs = getPrefs();
                setValue(resolutionPrefString, nextDefault);
                resetBitrateToDefault(prefs, null, null);
            }
        });
    }

    private void editAndReload(String prefKey, String newVal) {
        SharedPreferences prefs = getPrefs();
        prefs.edit().putString(prefKey, newVal).apply();

        reloadSettings();
    }

    protected void reloadSettings() {
        // HACK: We need to let the preference change succeed before reinitializing to ensure
        // it's reflected in the new layout.
        final Handler h = new Handler(Looper.getMainLooper());
        h.postDelayed(new Runnable() {
            @Override
            public void run() {
                // Ensure this list is still attached when this timeout expires
                SettingsHost host = findSettingsHost();
                if (host != null) {
                    host.reloadPreferences();
                }
            }
        }, 500);
    }

    /** The parent fragment if it hosts this list, else the activity if it does, else null. */
    private SettingsHost findSettingsHost() {
        if (!isAdded()) {
            return null;
        }
        Fragment parent = getParentFragment();
        if (parent instanceof SettingsHost) {
            return (SettingsHost) parent;
        }
        Activity activity = getActivity();
        return activity instanceof SettingsHost ? (SettingsHost) activity : null;
    }

    int READ_REQUEST_CODE = 1001;
    int READ_REQUEST_SPECIAL_CODE = 1002;
    int COCOON_FOLDER_REQUEST_CODE = 1003;

    private void setupCocoonSync() {
        final Preference folderPref = findPreference(CocoonSync.FOLDER_PREF_STRING);
        if (folderPref != null) {
            showChosenFolder(folderPref);
            folderPref.setOnPreferenceClickListener(new Preference.OnPreferenceClickListener() {
                @Override
                public boolean onPreferenceClick(Preference preference) {
                    Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
                    startActivityForResult(intent, COCOON_FOLDER_REQUEST_CODE);
                    return true;
                }
            });
        }

        ListPreference hostPref = findPreference(CocoonSync.HOST_PREF_STRING);
        if (hostPref == null) {
            return;
        }

        List<ComputerDetails> computers = new ComputerDatabaseManager(getActivity()).getAllComputers();
        if (computers.isEmpty()) {
            hostPref.setEnabled(false);
            hostPref.setSummary(R.string.summary_cocoon_sync_no_pcs);
            return;
        }

        CharSequence[] names = new CharSequence[computers.size()];
        CharSequence[] uuids = new CharSequence[computers.size()];
        for (int i = 0; i < computers.size(); i++) {
            names[i] = computers.get(i).name;
            uuids[i] = computers.get(i).uuid;
        }

        hostPref.setEntries(names);
        hostPref.setEntryValues(uuids);

        // With one PC there is nothing to choose, so choose it: the sync exports a named host
        // and would otherwise sit there doing nothing until the user came and said which.
        if (hostPref.getValue() == null && computers.size() == 1) {
            hostPref.setValue(computers.get(0).uuid);
        }
    }

    private void showChosenFolder(Preference folderPref) {
        String folder = getPrefs().getString(CocoonSync.FOLDER_PREF_STRING, "");
        if (!folder.isEmpty()) {
            folderPref.setSummary(CocoonSync.describeFolder(Uri.parse(folder)));
        }
    }

    @Override
    public void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);

        if (requestCode == COCOON_FOLDER_REQUEST_CODE && resultCode == Activity.RESULT_OK &&
                data != null && data.getData() != null) {
            Uri tree = data.getData();

            // The grant that came back dies with the process; this is what outlives a reboot.
            try {
                requireActivity().getContentResolver().takePersistableUriPermission(tree,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
            } catch (SecurityException e) {
                Toast.makeText(getActivity(), getString(R.string.pref_error_occurred) + e.getMessage(),
                        Toast.LENGTH_LONG).show();
                return;
            }

            getPrefs().edit().putString(CocoonSync.FOLDER_PREF_STRING, tree.toString()).apply();

            Preference folderPref = findPreference(CocoonSync.FOLDER_PREF_STRING);
            if (folderPref != null) {
                showChosenFolder(folderPref);
            }
            return;
        }
        if (requestCode == READ_REQUEST_CODE && resultCode == Activity.RESULT_OK && data.getData() != null) {
            try {
                Uri uri = data.getData();
                String json = FileUriUtils.openUriForRead(getActivity(), uri);
                if (TextUtils.isEmpty(json)) {
                    Toast.makeText(getActivity(), getString(R.string.pref_empty_file), Toast.LENGTH_SHORT).show();
                    return;
                }
                String name = getPrefs().getString(KeyBoardControllerConfigurationLoader.OSC_PREFERENCE, KeyBoardControllerConfigurationLoader.OSC_PREFERENCE_VALUE);
                SharedPreferences.Editor prefEditor = requireActivity().getSharedPreferences(name, Activity.MODE_PRIVATE).edit();
                JSONObject object = new JSONObject(json);
                Iterator it = object.keys();
                prefEditor.clear();
                while (it.hasNext()) {
                    String key = (String) it.next();// 获得key
                    String value = object.getString(key);// 获得value
                    prefEditor.putString(key, value);
                }
                prefEditor.apply();
                Toast.makeText(getActivity(), getString(R.string.pref_import_success), Toast.LENGTH_SHORT).show();
            } catch (Exception e) {
                e.printStackTrace();
                Toast.makeText(getActivity(), getString(R.string.pref_error_occurred) + e.getMessage(), Toast.LENGTH_SHORT).show();
            }
            return;
        }

        if (requestCode == READ_REQUEST_SPECIAL_CODE && resultCode == Activity.RESULT_OK && data.getData() != null) {
            try {
                Uri uri = data.getData();
                String json = FileUriUtils.openUriForRead(getActivity(), uri);
                if (TextUtils.isEmpty(json)) {
                    Toast.makeText(getActivity(), getString(R.string.pref_empty_file), Toast.LENGTH_SHORT).show();
                    return;
                }
                SharedPreferences.Editor prefEditor = getActivity().getSharedPreferences(GameMenu.PREF_NAME, Activity.MODE_PRIVATE).edit();
                prefEditor.putString(GameMenu.KEY_NAME, json);
                prefEditor.apply();
                Toast.makeText(getActivity(), getString(R.string.pref_import_success), Toast.LENGTH_SHORT).show();
            } catch (Exception e) {
                e.printStackTrace();
                Toast.makeText(getActivity(), getString(R.string.pref_error_occurred) + e.getMessage(), Toast.LENGTH_SHORT).show();
            }
        }
    }

    @Override
    public void onDisplayPreferenceDialog(@NonNull Preference preference) {
        if (preference instanceof ConfirmDeleteOscPreference) {
            DialogFragment dialogFragment = ConfirmDeleteOscPreference.DialogFragmentCompat.newInstance(preference.getKey());
            dialogFragment.setTargetFragment(this, 0);
            dialogFragment.show(getFragmentManager(), null);
        } else if (preference instanceof ConfirmDeleteKeyboardPreference) {
            DialogFragment dialogFragment = ConfirmDeleteKeyboardPreference.DialogFragmentCompat.newInstance(preference.getKey());
            dialogFragment.setTargetFragment(this, 0);
            dialogFragment.show(getFragmentManager(), null);
        } else super.onDisplayPreferenceDialog(preference);
    }

    private File getJsonContent(Context context,File file){
        String name = getPrefs().getString(KeyBoardControllerConfigurationLoader.OSC_PREFERENCE, KeyBoardControllerConfigurationLoader.OSC_PREFERENCE_VALUE);
        SharedPreferences pref = context.getSharedPreferences(name, Activity.MODE_PRIVATE);
        Map<String,?> map = pref.getAll();
        File file1= new File(file,name+".json");
        String jsonStr=new Gson().toJson(map);
        if(!FileUriUtils.writerFileString(file1,jsonStr)){
            return null;
        }
        return file1;
    }

    //获取所有设置项配置文件
    private File getAllJsonData(File file){
        SharedPreferences pref = getPrefs();
        Map<String,?> map = pref.getAll();
        //获取适配电脑的数据库信息
//            List<ComputerDetails> map= new ComputerDatabaseManager(context).getAllComputers();
        File file1= new File(file,"allJSON.json");
        String jsonStr=new Gson().toJson(map);
        if(!FileUriUtils.writerFileString(file1,jsonStr)){
            return null;
        }
        return file1;
    }
}
