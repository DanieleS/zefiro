package com.limelight.settings;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.StringRes;

import com.limelight.R;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Where every stream setting lives on screen: sections (Video, Audio, ...), subgroups within
 * them, and the order of the keys inside each.
 *
 * <p>Every key in {@code R.xml.preferences} appears here exactly once, and the screen's order is
 * this table's, not the XML's. The XML keeps its own categories only because the device removals
 * in {@code StreamPreferencesFragment} look preferences up by category name
 * ({@code category_gamepad_settings}, ...); once they have run, {@link SettingsRegrouper} moves
 * what is left into these sections. {@code SettingsCatalogTest} compares the XML's keys with this
 * table, so a key added to the XML without a place here fails the build's tests rather than
 * landing in the regrouper's trailing "Other" section.
 *
 * <p>Pure data: titles are string resource ids, keys are the preference names, which are an
 * external contract (profiles and the settings export use them) and are never renamed here.
 */
public final class SettingsCatalog {

    /** One section of the list, with the short name its jump chip uses. */
    public static final class Section {
        /** Stable id, used to key the section's header in the list ("section_" + id). */
        @NonNull
        public final String id;
        @StringRes
        public final int title;
        @StringRes
        public final int chip;
        @NonNull
        public final List<Subgroup> subgroups;

        Section(@NonNull String id, @StringRes int title, @StringRes int chip, Subgroup... subgroups) {
            this.id = id;
            this.title = title;
            this.chip = chip;
            this.subgroups = Collections.unmodifiableList(Arrays.asList(subgroups));
        }

        /** The section's keys in screen order, across its subgroups. */
        @NonNull
        public List<String> keys() {
            List<String> keys = new ArrayList<>();
            for (Subgroup subgroup : subgroups) {
                keys.addAll(subgroup.keys);
            }
            return keys;
        }
    }

    /** A run of related keys inside a section; named in breadcrumbs, never drawn as a header. */
    public static final class Subgroup {
        @StringRes
        public final int title;
        @NonNull
        public final List<String> keys;

        Subgroup(@StringRes int title, String... keys) {
            this.title = title;
            this.keys = Collections.unmodifiableList(Arrays.asList(keys));
        }
    }

    private static final SettingsCatalog INSTANCE = new SettingsCatalog(Arrays.asList(
            new Section("video", R.string.settings_section_video, R.string.settings_section_video,
                    new Subgroup(R.string.settings_group_resolution_fps,
                            "list_resolution", "list_fps"),
                    new Subgroup(R.string.settings_group_bitrate,
                            "seekbar_bitrate_kbps", "seekbar_metered_bitrate_kbps",
                            "checkbox_adaptive_bitrate", "list_adaptive_bitrate_mode"),
                    new Subgroup(R.string.settings_group_error_correction,
                            "list_video_fec"),
                    new Subgroup(R.string.settings_group_frame_pacing,
                            "frame_pacing", "pref_low_latency_frame_balance",
                            "checkbox_ultra_low_latency", "checkbox_forceTightThresholds",
                            "checkbox_vsync_target_present"),
                    new Subgroup(R.string.settings_group_display,
                            "checkbox_use_virtual_display", "checkbox_auto_orientation",
                            "checkbox_auto_invert_video_resolution",
                            "checkbox_enable_view_top_center"),
                    new Subgroup(R.string.settings_group_scaling,
                            "seekbar_resolution_scale_factor", "list_video_scale_mode",
                            "upscaling_mode", "upscaling_sharpness"),
                    new Subgroup(R.string.settings_group_custom_values,
                            "edit_diy_w_h", "edit_diy_bitrate", "custom_refresh_rate",
                            "checkbox_enforce_display_mode"),
                    new Subgroup(R.string.settings_group_3d,
                            "render_mode_list", "parallax_depth", "convergence_ratio",
                            "balance_shift"),
                    new Subgroup(R.string.settings_group_hdr_codec,
                            "checkbox_enable_hdr", "video_format", "checkbox_enable_pyrowave",
                            "seekbar_pyrowave_bitrate_kbps", "checkbox_full_range"),
                    new Subgroup(R.string.settings_group_refresh_rate,
                            "checkbox_unlock_fps", "checkbox_reduce_refresh_rate")),
            new Section("audio", R.string.settings_section_audio, R.string.settings_section_audio,
                    new Subgroup(R.string.settings_group_audio,
                            "list_audio_config", "checkbox_enable_audiofx", "checkbox_host_audio")),
            new Section("controllers", R.string.settings_section_controllers,
                    R.string.settings_section_controllers,
                    new Subgroup(R.string.settings_group_gamepad,
                            "seekbar_deadzone", "checkbox_multi_controller", "checkbox_usb_driver",
                            "checkbox_usb_bind_all"),
                    new Subgroup(R.string.settings_group_mouse_emulation,
                            "checkbox_mouse_emulation", "analog_scrolling"),
                    new Subgroup(R.string.settings_group_buttons,
                            "checkbox_flip_face_buttons", "checkbox_gamepad_touchpad_as_mouse"),
                    new Subgroup(R.string.settings_group_motion,
                            "checkbox_gamepad_motion_sensors", "checkbox_gamepad_motion_fallback",
                            "checkbox_force_device_motion"),
                    new Subgroup(R.string.settings_group_extras,
                            "checkbox_enable_joyconfix", "checkbox_gamepad_enable_battery_report"),
                    new Subgroup(R.string.settings_group_rumble,
                            "checkbox_enable_rumble", "checkbox_vibrate_fallback",
                            "checkbox_enable_device_rumble", "seekbar_vibrate_fallback_strength")),
            new Section("touch", R.string.settings_section_touch, R.string.settings_chip_touch,
                    new Subgroup(R.string.settings_group_touch_mode,
                            "mouse_mode_list", "checkbox_remember_mouse_mode",
                            "checkbox_mouse_local_cursor", "checkbox_multi_touch_gestures",
                            "checkbox_mouse_nav_buttons", "checkbox_absolute_mouse_mode"),
                    new Subgroup(R.string.settings_group_trackpad,
                            "seekbar_trackpad_sensitivity_x", "seekbar_trackpad_sensitivity_y",
                            "checkbox_trackpad_drag_drop_vibration",
                            "seekbar_trackpad_drag_drop_threshold", "checkbox_trackpad_swap_axis"),
                    new Subgroup(R.string.settings_group_keyboard,
                            "checkbox_force_qwerty", "checkbox_back_as_meta",
                            "checkbox_right_alt_as_meta", "checkbox_back_as_guide",
                            "checkbox_ignore_synth_events", "checkbox_enable_commit_text",
                            "checkbox_text_focus_keyboard"),
                    new Subgroup(R.string.settings_group_onscreen_keyboard,
                            "seekbar_keyboard_axi_opacity", "onscreen_keyboard_autofit",
                            "seekbar_onscreen_keyboard_height", "seekbar_onscreen_keyboard_width",
                            "list_onscreen_keyboard_align_mode",
                            "checkbox_enable_sticky_modifier_key_virtual_keyboard"),
                    new Subgroup(R.string.settings_group_onscreen_controls,
                            "checkbox_show_onscreen_controls", "checkbox_hide_osc_when_has_gamepad",
                            "checkbox_onscreen_style_official", "checkbox_vibrate_osc",
                            "checkbox_only_show_L3R3", "checkbox_show_guide_button",
                            "seekbar_osc_opacity", "checkbox_enable_analog_stick_new",
                            "seekbar_osc_free_analog_stick_opacity", "option_reset_osc_preference"),
                    new Subgroup(R.string.settings_group_special_keys,
                            "checkbox_enable_keyboard", "checkbox_enable_clear_default_special_button",
                            "checkbox_vibrate_keyboard", "keyboard_axi_list",
                            "checkbox_enable_keyboard_square", "option_reset_osc",
                            "import_keyboard_file", "export_keyboard_file",
                            "import_special_button_file", "option_help_custom_keys"),
                    new Subgroup(R.string.settings_group_touch_sensitivity,
                            "checkbox_enable_touch_sensitivity",
                            "checkbox_enable_global_touch_sensitivity",
                            "checkbox_enable_touch_sensitivity_rotation_auto",
                            "seekbar_touch_sensitivity_opacity_x", "seekbar_touch_sensitivity_opacity_y",
                            "seekbar_touchpad_sensitivity_opacity",
                            "seekbar_touchpad_sensitivity_y_opacity")),
            new Section("screens", R.string.settings_section_screens, R.string.settings_chip_screens,
                    new Subgroup(R.string.settings_group_companion,
                            "checkbox_enable_fullexdisplay", "checkbox_enable_companion_display",
                            "checkbox_enable_companion_stats", "checkbox_enable_companion_app",
                            "pref_companion_screen"),
                    new Subgroup(R.string.settings_group_library,
                            "checkbox_resume_without_confirm"),
                    new Subgroup(R.string.settings_group_stream,
                            "checkbox_enable_quit_dialog", "checkbox_enable_floating_button",
                            "checkbox_full_screen", "checkbox_disable_warnings",
                            "checkbox_show_overlay_zoom_toggle_button", "checkbox_remember_zoom_pan",
                            "checkbox_enable_post_stream_toast", "checkbox_enable_pip"),
                    new Subgroup(R.string.settings_group_perf_overlay,
                            "checkbox_enable_perf_overlay", "checkbox_enable_perf_overlay_lite",
                            "checkbox_enable_perf_overlay_lite_dialog",
                            "checkbox_enable_perf_overlay_bottom", "checkbox_enable_perf_logging",
                            "share_performance_logs", "option_view_shared_pref_logs")),
            new Section("connection", R.string.settings_section_connection,
                    R.string.settings_section_connection,
                    new Subgroup(R.string.settings_group_host,
                            "checkbox_enable_sops", "list_capture_tool"),
                    new Subgroup(R.string.settings_group_network,
                            "checkbox_prevent_packet_loss", "checkbox_auto_reconnect",
                            "seekbar_network_timeout_seconds", "seekbar_reconnect_window_seconds"),
                    new Subgroup(R.string.settings_group_clipboard,
                            "checkbox_smart_clipboard_sync", "checkbox_smart_clipboard_sync_toast",
                            "checkbox_hide_clipboard_content"),
                    new Subgroup(R.string.settings_group_cocoon_sync,
                            "checkbox_cocoon_sync", "cocoon_sync_folder", "cocoon_sync_host")),
            new Section("help", R.string.settings_section_help, R.string.settings_chip_help,
                    new Subgroup(R.string.settings_group_general, "pref_profiles"),
                    new Subgroup(R.string.settings_group_help, "pref_help"),
                    new Subgroup(R.string.settings_group_diagnostics, "pref_debug_info"),
                    new Subgroup(R.string.settings_group_updates,
                            "option_software_release", "option_follow_update"))));

    @NonNull
    public static SettingsCatalog get() {
        return INSTANCE;
    }

    private final List<Section> sections;
    private final List<String> keys = new ArrayList<>();
    private final Map<String, Section> sectionByKey = new HashMap<>();
    private final Map<String, Subgroup> subgroupByKey = new HashMap<>();
    private final Map<String, Integer> positionByKey = new HashMap<>();

    SettingsCatalog(@NonNull List<Section> sections) {
        this.sections = Collections.unmodifiableList(new ArrayList<>(sections));
        for (Section section : sections) {
            int position = 0;
            for (Subgroup subgroup : section.subgroups) {
                for (String key : subgroup.keys) {
                    keys.add(key);
                    // Only the first place counts; SettingsCatalogTest reports the duplicate.
                    if (sectionByKey.containsKey(key)) {
                        continue;
                    }
                    sectionByKey.put(key, section);
                    subgroupByKey.put(key, subgroup);
                    positionByKey.put(key, position++);
                }
            }
        }
    }

    /** The sections in screen order. */
    @NonNull
    public List<Section> sections() {
        return sections;
    }

    /** Every key in screen order; a key listed twice by mistake appears twice. */
    @NonNull
    public List<String> keys() {
        return Collections.unmodifiableList(keys);
    }

    public boolean contains(@Nullable String key) {
        return key != null && sectionByKey.containsKey(key);
    }

    @Nullable
    public Section sectionOf(@Nullable String key) {
        return key == null ? null : sectionByKey.get(key);
    }

    @Nullable
    public Subgroup subgroupOf(@Nullable String key) {
        return key == null ? null : subgroupByKey.get(key);
    }

    /** The key's 0-based place among all of its section's keys, or -1 if it is not here. */
    public int positionInSection(@Nullable String key) {
        Integer position = key == null ? null : positionByKey.get(key);
        return position == null ? -1 : position;
    }
}
