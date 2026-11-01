package com.limelight.benchmark;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.preference.PreferenceManager;

import com.limelight.benchmark.BenchmarkTypes.Codec;
import com.limelight.benchmark.BenchmarkTypes.RunConfig;
import com.limelight.nvstream.jni.MoonBridge;
import com.limelight.preferences.PreferenceConfiguration;
import com.limelight.profiles.ProfilesManager;
import com.limelight.profiles.SettingsProfile;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * How a benchmark configuration maps onto this app's settings: codec, bitrate and the four
 * frame pacing modes, which the benchmark names {@code min-latency}, {@code max-smoothness},
 * {@code balanced} and {@code cap-fps}.
 */
public final class BenchmarkSettings {
    private BenchmarkSettings() {
    }

    public static final String PACING_MIN_LATENCY = "min-latency";
    public static final String PACING_BALANCED = "balanced";
    public static final String PACING_CAP_FPS = "cap-fps";
    public static final String PACING_MAX_SMOOTHNESS = "max-smoothness";

    /** The pacing modes, in the order the planner tries them. */
    public static final List<String> PACING_MODES = Collections.unmodifiableList(
            Arrays.asList(PACING_MIN_LATENCY, PACING_MAX_SMOOTHNESS, PACING_BALANCED, PACING_CAP_FPS));

    private static final String KEY_FORMAT = "video_format";
    private static final String KEY_BITRATE = PreferenceConfiguration.BITRATE_PREF_STRING;
    private static final String KEY_FRAME_PACING = "frame_pacing";

    /** The benchmark's name for a {@code PreferenceConfiguration.FRAME_PACING_*} value. */
    public static String pacingOf(int framePacing) {
        switch (framePacing) {
            case PreferenceConfiguration.FRAME_PACING_BALANCED:
                return PACING_BALANCED;
            case PreferenceConfiguration.FRAME_PACING_CAP_FPS:
                return PACING_CAP_FPS;
            case PreferenceConfiguration.FRAME_PACING_MAX_SMOOTHNESS:
                return PACING_MAX_SMOOTHNESS;
            case PreferenceConfiguration.FRAME_PACING_MIN_LATENCY:
            default:
                return PACING_MIN_LATENCY;
        }
    }

    /** The {@code FRAME_PACING_*} value of a benchmark pacing name, or -1 for one Zefiro lacks. */
    public static int framePacingOf(String pacing) {
        if (PACING_MIN_LATENCY.equals(pacing)) {
            return PreferenceConfiguration.FRAME_PACING_MIN_LATENCY;
        }
        if (PACING_BALANCED.equals(pacing)) {
            return PreferenceConfiguration.FRAME_PACING_BALANCED;
        }
        if (PACING_CAP_FPS.equals(pacing)) {
            return PreferenceConfiguration.FRAME_PACING_CAP_FPS;
        }
        if (PACING_MAX_SMOOTHNESS.equals(pacing)) {
            return PreferenceConfiguration.FRAME_PACING_MAX_SMOOTHNESS;
        }
        return -1;
    }

    /** The {@code frame_pacing} preference value of a benchmark pacing name, or null. */
    private static String pacingPreference(String pacing) {
        switch (framePacingOf(pacing)) {
            case PreferenceConfiguration.FRAME_PACING_MIN_LATENCY:
                return "latency";
            case PreferenceConfiguration.FRAME_PACING_BALANCED:
                return "balanced";
            case PreferenceConfiguration.FRAME_PACING_CAP_FPS:
                return "cap-fps";
            case PreferenceConfiguration.FRAME_PACING_MAX_SMOOTHNESS:
                return "smoothness";
            default:
                return null;
        }
    }

    /** The user's settings as a configuration. Auto's codec is a guess the baseline run corrects. */
    public static RunConfig fromPreferences(PreferenceConfiguration p) {
        Codec codec;
        switch (p.videoFormat) {
            case FORCE_H264:
                codec = Codec.H264;
                break;
            case FORCE_AV1:
                codec = Codec.AV1;
                break;
            default:
                codec = Codec.HEVC;
                break;
        }
        return new RunConfig(codec, p.bitrate, pacingOf(p.framePacing));
    }

    /** The codec a stream really uses, from the video format the host and decoder settled on. */
    public static Codec codecOfVideoFormat(int videoFormat) {
        if ((videoFormat & MoonBridge.VIDEO_FORMAT_MASK_AV1) != 0) {
            return Codec.AV1;
        }
        if ((videoFormat & MoonBridge.VIDEO_FORMAT_MASK_H265) != 0) {
            return Codec.HEVC;
        }
        return Codec.H264;
    }

    private static PreferenceConfiguration.FormatOption formatOf(Codec codec) {
        switch (codec) {
            case H264:
                return PreferenceConfiguration.FormatOption.FORCE_H264;
            case AV1:
                return PreferenceConfiguration.FormatOption.FORCE_AV1;
            case HEVC:
            default:
                return PreferenceConfiguration.FormatOption.FORCE_HEVC;
        }
    }

    /** Whether Zefiro can stream this configuration, so a result may be applied to the settings. */
    public static boolean canApply(RunConfig c) {
        return c.codec != Codec.PYROWAVE && framePacingOf(c.pacing) >= 0 && c.bitrateKbps > 0;
    }

    /** In memory only, for one stream: the saved settings are not touched. */
    public static void applyTo(PreferenceConfiguration p, RunConfig c) {
        p.videoFormat = formatOf(c.codec);
        p.bitrate = c.bitrateKbps;
        int framePacing = framePacingOf(c.pacing);
        if (framePacing >= 0) {
            p.framePacing = framePacing;
            // The warp variants of "latency" are other pacing than the one being measured
            p.framePacingWarpFactor = 0;
        }
    }

    /** The preference values a configuration stands for, as the settings screen stores them. */
    static Map<String, Object> preferenceValues(RunConfig c) {
        Map<String, Object> values = new LinkedHashMap<>();
        String format;
        switch (c.codec) {
            case H264:
                format = "neverh265";
                break;
            case AV1:
                format = "forceav1";
                break;
            case HEVC:
            default:
                format = "forceh265";
                break;
        }
        values.put(KEY_FORMAT, format);
        values.put(KEY_BITRATE, c.bitrateKbps);
        String pacing = pacingPreference(c.pacing);
        if (pacing != null) {
            values.put(KEY_FRAME_PACING, pacing);
        }
        return values;
    }

    /**
     * Into the user's settings: the active profile when there is one (writing the base
     * preferences under a profile would be overridden by it), else the base preferences.
     */
    public static void applyToSettings(Context context, RunConfig c) {
        ProfilesManager profiles = ProfilesManager.getInstance();
        SettingsProfile active = profiles.getActive();
        Map<String, Object> values = preferenceValues(c);
        if (active != null) {
            Map<String, Object> options = active.getOptions() != null ? new LinkedHashMap<>(active.getOptions()) : new LinkedHashMap<>();
            options.putAll(values);
            active.setOptions(options);
            active.setModifiedUtc(System.currentTimeMillis());
            profiles.update(active);
            profiles.save(context);
            return;
        }
        SharedPreferences.Editor editor = PreferenceManager.getDefaultSharedPreferences(context).edit();
        for (Map.Entry<String, Object> e : values.entrySet()) {
            Object v = e.getValue();
            if (v instanceof Boolean) {
                editor.putBoolean(e.getKey(), (Boolean) v);
            }
            else if (v instanceof Integer) {
                editor.putInt(e.getKey(), (Integer) v);
            }
            else {
                editor.putString(e.getKey(), String.valueOf(v));
            }
        }
        editor.apply();
    }

    /** A new profile holding the active one's settings plus this configuration, made active. */
    public static void applyAsProfile(Context context, RunConfig c, String name) {
        ProfilesManager profiles = ProfilesManager.getInstance();
        SettingsProfile active = profiles.getActive();
        Map<String, Object> options = new LinkedHashMap<>();
        if (active != null && active.getOptions() != null) {
            options.putAll(active.getOptions());
        }
        options.putAll(preferenceValues(c));
        long now = System.currentTimeMillis();
        SettingsProfile profile = new SettingsProfile(UUID.randomUUID(), name, now, now, options);
        profiles.add(profile);
        profiles.setActive(profile.getUuid());
        profiles.save(context);
    }
}
