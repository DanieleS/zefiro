package com.limelight.stream;

import androidx.annotation.NonNull;

import com.limelight.R;
import com.limelight.binding.input.KeyboardTranslator;
import com.limelight.bitrate.BitrateSteps;
import com.limelight.stream.StreamAction.Group;
import com.limelight.stream.StreamAction.Id;

import java.util.ArrayList;
import java.util.List;

/**
 * The in-stream controls as one tree, built from a {@link StreamCapabilities} snapshot. Pure: no
 * Context, no Game, so every entry and every rule that hides one is checked off the device.
 *
 * <h3>The tree</h3>
 *
 * <pre>
 *   Tiles    Keyboard · Gamepad (not on an external display) · Mouse▸ · Overlay
 *   List     Screenshot (capture tool on) · Bitrate▸ (live bitrate) · Special keys▸ · More▸
 *   Danger   Disconnect · Quit game
 *
 *   Mouse▸         Mouse mode▸ (if allowed) · the gamepad's own options
 *   Mouse mode▸    the modes this display allows · toggle the local cursor
 *   Bitrate▸       Adaptive on/off · the bitrate ladder
 *   Special keys▸  14 built-in combos (unless cleared) · the user's imported shortcuts
 *   More▸          clipboard up/down · Server commands▸ · pan/zoom · rotate · companion screen ·
 *                  companion app · special-keys bar · full keyboard · task manager ·
 *                  touch sensitivity · save last 30 s · start/stop recording
 * </pre>
 *
 * There are no Cancel entries: every surface closes on its own (B, BACK, a tap outside), and a
 * Cancel row was only ever a second way to do that taking up a line.
 *
 * <p>The withGameFocus flags are the old menu's, entry for entry: they mark what injects keys or
 * opens something over the game, and so has to wait for the game window to have focus again.
 */
public final class StreamActions {

    /** The built-in combos of the Special keys page, label and keys, in the order shown. */
    private static final int[] DEFAULT_KEY_LABELS = {
            R.string.game_menu_send_keys_esc,
            R.string.game_menu_send_keys_f11,
            R.string.game_menu_send_keys_alt_f4,
            R.string.game_menu_send_keys_alt_enter,
            R.string.game_menu_send_keys_ctrl_v,
            R.string.game_menu_send_keys_win,
            R.string.game_menu_send_keys_win_d,
            R.string.game_menu_send_keys_win_g,
            R.string.game_menu_send_keys_ctrl_alt_tab,
            R.string.game_menu_send_keys_shift_tab,
            R.string.game_menu_send_keys_win_shift_left,
            R.string.game_menu_send_keys_ctrl_alt_shift_f1,
            R.string.game_menu_send_keys_ctrl_alt_shift_f12,
            R.string.game_menu_send_keys_alt_b,
    };

    private static final short[][] DEFAULT_KEYS = {
            {KeyboardTranslator.VK_ESCAPE},
            {KeyboardTranslator.VK_F11},
            {KeyboardTranslator.VK_LMENU, KeyboardTranslator.VK_F4},
            {KeyboardTranslator.VK_LMENU, KeyboardTranslator.VK_RETURN},
            {KeyboardTranslator.VK_LCONTROL, KeyboardTranslator.VK_V},
            {KeyboardTranslator.VK_LWIN},
            {KeyboardTranslator.VK_LWIN, KeyboardTranslator.VK_D},
            {KeyboardTranslator.VK_LWIN, KeyboardTranslator.VK_G},
            {KeyboardTranslator.VK_LCONTROL, KeyboardTranslator.VK_LMENU, KeyboardTranslator.VK_TAB},
            {KeyboardTranslator.VK_LSHIFT, KeyboardTranslator.VK_TAB},
            {KeyboardTranslator.VK_LWIN, KeyboardTranslator.VK_LSHIFT, KeyboardTranslator.VK_LEFT},
            {KeyboardTranslator.VK_LCONTROL, KeyboardTranslator.VK_LMENU, KeyboardTranslator.VK_LSHIFT, KeyboardTranslator.VK_F1},
            {KeyboardTranslator.VK_LCONTROL, KeyboardTranslator.VK_LMENU, KeyboardTranslator.VK_LSHIFT, KeyboardTranslator.VK_F12},
            // Labelled "ALT + B" since it always was, but it sends Win+Alt+B (the Game Bar's HDR
            // toggle), which is what people use it for.
            {KeyboardTranslator.VK_LWIN, KeyboardTranslator.VK_LMENU, KeyboardTranslator.VK_B},
    };

    private static final short[] TASK_MANAGER_KEYS = {
            KeyboardTranslator.VK_LCONTROL, KeyboardTranslator.VK_LSHIFT, KeyboardTranslator.VK_ESCAPE
    };

    /** How many built-in combos the Special keys page has; for the tests. */
    static final int DEFAULT_KEY_COUNT = DEFAULT_KEY_LABELS.length;

    private StreamActions() {
    }

    /** The whole tree, root first; its children are the main page, in drawing order. */
    @NonNull
    public static StreamAction build(@NonNull StreamCapabilities caps) {
        List<StreamAction> main = new ArrayList<>();

        // Tiles: the toggles reached most, each a single press.
        main.add(StreamAction.of(Id.KEYBOARD, Group.TILE)
                .label(R.string.stream_action_keyboard).icon(R.drawable.ic_stream_keyboard)
                .withGameFocus().build());
        if (!caps.onExternalDisplay) {
            // The on-screen controller is drawn over the game; on an external display the phone
            // in the hand is a touchpad and has nowhere to put one.
            main.add(StreamAction.of(Id.GAMEPAD, Group.TILE)
                    .label(R.string.stream_action_gamepad).icon(R.drawable.ic_stream_gamepad)
                    .checked(caps.virtualControllerShown).withGameFocus().build());
        }
        List<StreamAction> mouse = mousePage(caps);
        if (!mouse.isEmpty()) {
            main.add(StreamAction.of(Id.MOUSE, Group.TILE)
                    .label(R.string.stream_action_mouse).icon(R.drawable.ic_stream_mouse)
                    .children(mouse).build());
        }
        main.add(StreamAction.of(Id.OVERLAY, Group.TILE)
                .label(R.string.stream_action_overlay).icon(R.drawable.ic_stream_overlay)
                .checked(caps.hudVisible).withGameFocus().build());

        // List.
        if (caps.captureEnabled) {
            main.add(StreamAction.of(Id.SCREENSHOT, Group.LIST)
                    .label(R.string.game_menu_capture_screenshot).withGameFocus().build());
        }
        if (caps.liveBitrate) {
            // The entry shows where the bitrate stands now, so it is read without opening it.
            main.add(StreamAction.of(Id.BITRATE, Group.LIST)
                    .label(caps.bitrateAdaptive ? R.string.game_menu_bitrate_adaptive
                            : R.string.game_menu_bitrate, mbps(caps, caps.bitrateCurrentKbps))
                    .children(bitratePage(caps)).build());
        }
        main.add(StreamAction.of(Id.SPECIAL_KEYS, Group.LIST)
                .label(R.string.stream_action_special_keys)
                .children(specialKeysPage(caps)).build());
        main.add(StreamAction.of(Id.MORE, Group.LIST)
                .label(R.string.stream_action_more)
                .children(morePage(caps)).build());

        // Danger: last, so a stray press on the way down never lands on it first.
        main.add(StreamAction.of(Id.DISCONNECT, Group.DANGER)
                .label(R.string.game_menu_disconnect).build());
        main.add(StreamAction.of(Id.QUIT, Group.DANGER)
                .label(R.string.library_quit_game).build());

        return StreamAction.of(Id.ROOT, Group.LIST)
                .label(R.string.quick_menu_title).children(main).build();
    }

    private static List<StreamAction> mousePage(StreamCapabilities caps) {
        List<StreamAction> page = new ArrayList<>();
        if (caps.allowChangeMouseMode) {
            page.add(StreamAction.of(Id.MOUSE_MODE, Group.LIST)
                    .label(R.string.game_menu_select_mouse_mode)
                    .children(mouseModePage(caps)).build());
        }
        // The gamepad's own entries (its mouse emulation), only when the controls were opened
        // from one: they act on that pad.
        for (int i = 0; i < caps.deviceOptions.size(); i++) {
            StreamCapabilities.DeviceOption option = caps.deviceOptions.get(i);
            StreamAction.Builder b = StreamAction.of(Id.DEVICE_OPTION, Group.LIST)
                    .text(option.label).arg(i);
            if (option.withGameFocus) {
                b.withGameFocus();
            }
            page.add(b.build());
        }
        return page;
    }

    /**
     * The modes this display allows, the one in use checked, then the local cursor's toggle. A
     * page rather than the dialog it used to be, so it is walked like every other page and the
     * side panel never has to hand the screen to a window of its own.
     *
     * <p>Both wait for the game: the cursor's toggle asks for pointer capture, which only a
     * focused window gets, and a mode change cancels the touches in flight on the stream.
     */
    private static List<StreamAction> mouseModePage(StreamCapabilities caps) {
        List<StreamAction> page = new ArrayList<>();
        for (StreamCapabilities.MouseMode mode : caps.mouseModes) {
            page.add(StreamAction.of(Id.MOUSE_MODE_CHOICE, Group.LIST)
                    .text(mode.label).arg(mode.index)
                    .checked(mode.index == caps.currentMouseMode).withGameFocus().build());
        }
        page.add(StreamAction.of(Id.LOCAL_CURSOR, Group.LIST)
                .label(R.string.toggle_local_mouse_cursor).withGameFocus().build());
        return page;
    }

    /**
     * Adaptation on or off, then a ladder of bitrates with the settings' choice and the current
     * one among them. A list rather than a slider, so the d-pad drives it like the rest.
     */
    private static List<StreamAction> bitratePage(StreamCapabilities caps) {
        List<StreamAction> page = new ArrayList<>();
        page.add(StreamAction.of(Id.BITRATE_ADAPTIVE, Group.LIST)
                .label(caps.bitrateAdaptive ? R.string.game_menu_bitrate_adaptive_on
                        : R.string.game_menu_bitrate_adaptive_off).build());
        for (int kbps : BitrateSteps.menuChoices(caps.bitrateChosenKbps, caps.bitrateCurrentKbps)) {
            page.add(StreamAction.of(Id.BITRATE_CHOICE, Group.LIST)
                    .label(kbps == caps.bitrateCurrentKbps ? R.string.game_menu_bitrate_choice_current
                            : R.string.game_menu_bitrate_choice, mbps(caps, kbps))
                    .arg(kbps).build());
        }
        return page;
    }

    private static List<StreamAction> specialKeysPage(StreamCapabilities caps) {
        List<StreamAction> page = new ArrayList<>();
        if (caps.defaultSpecialKeys) {
            for (int i = 0; i < DEFAULT_KEY_LABELS.length; i++) {
                page.add(StreamAction.of(Id.SPECIAL_KEY, Group.LIST)
                        .label(DEFAULT_KEY_LABELS[i]).keys(DEFAULT_KEYS[i]).build());
            }
        }
        for (StreamCapabilities.CustomKey key : caps.customKeys) {
            page.add(StreamAction.of(Id.SPECIAL_KEY, Group.LIST)
                    .text(key.name).keys(key.keys).build());
        }
        return page;
    }

    private static List<StreamAction> morePage(StreamCapabilities caps) {
        List<StreamAction> page = new ArrayList<>();
        page.add(more(Id.CLIPBOARD_UPLOAD, R.string.game_menu_upload_clipboard).withGameFocus().build());
        page.add(more(Id.CLIPBOARD_FETCH, R.string.game_menu_fetch_clipboard).withGameFocus().build());

        // A page even when the host sent none: the surface says so there (the side panel on the
        // page, the others in a dialog), rather than the entry vanishing and leaving people to
        // wonder where it went.
        List<StreamAction> cmds = new ArrayList<>();
        for (int i = 0; i < caps.serverCmds.size(); i++) {
            cmds.add(StreamAction.of(Id.SERVER_COMMAND, Group.LIST)
                    .text("> " + caps.serverCmds.get(i)).arg(i).withGameFocus().build());
        }
        page.add(more(Id.SERVER_COMMANDS, R.string.game_menu_server_cmd).children(cmds).build());

        page.add(more(Id.PAN_ZOOM, caps.zoomEnabled ? R.string.game_menu_disable_zoom_mode
                : R.string.game_menu_enable_zoom_mode).withGameFocus().build());
        if (caps.canRotate) {
            page.add(more(Id.ROTATE, R.string.game_menu_rotate_screen).withGameFocus().build());
        }
        if (caps.companionConfigured) {
            page.add(more(Id.COMPANION_SCREEN, caps.companionShowing
                    ? R.string.game_menu_companion_hide : R.string.game_menu_companion_show).build());
        }
        if (caps.companionAppSupported) {
            page.add(more(Id.COMPANION_APP, R.string.game_menu_companion_app).build());
        }
        page.add(more(Id.SPECIAL_KEYS_BAR, R.string.game_menu_toggle_keyboard_model).withGameFocus().build());
        page.add(more(Id.FULL_KEYBOARD, R.string.game_menu_toggle_virtual_keyboard_model).withGameFocus().build());
        page.add(more(Id.TASK_MANAGER, R.string.game_menu_task_manager)
                .keys(TASK_MANAGER_KEYS).withGameFocus().build());
        page.add(more(Id.TOUCH_SENSITIVITY, R.string.game_menu_switch_touch_sensitivity_model)
                .withGameFocus().build());
        if (caps.captureEnabled) {
            page.add(more(Id.SAVE_REPLAY, R.string.game_menu_capture_replay).withGameFocus().build());
            page.add(more(Id.RECORD, R.string.game_menu_capture_record).withGameFocus().build());
        }
        return page;
    }

    private static StreamAction.Builder more(Id id, int labelRes) {
        return StreamAction.of(id, Group.MORE).label(labelRes);
    }

    private static String mbps(StreamCapabilities caps, int kbps) {
        return BitrateSteps.formatMbps(kbps, caps.locale);
    }
}
