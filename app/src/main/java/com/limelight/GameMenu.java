package com.limelight;

import android.app.Activity;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.view.ContextThemeWrapper;
import android.widget.Toast;

import androidx.annotation.NonNull;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.limelight.binding.input.GameInputDevice;
import com.limelight.bitrate.LiveBitrate;
import com.limelight.companion.CompanionDisplayManager;
import com.limelight.preferences.PreferenceConfiguration;
import com.limelight.stream.CompanionListSurface;
import com.limelight.stream.ControlsInputRouter;
import com.limelight.stream.ControlsSurface;
import com.limelight.stream.MenuSheetSurface;
import com.limelight.stream.StreamAction;
import com.limelight.stream.StreamActions;
import com.limelight.stream.StreamCapabilities;
import com.limelight.stream.StreamControlsPanel;
import com.limelight.utils.CaptureTool;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * The in-stream controls' router: it snapshots what the stream can do, builds the
 * {@link StreamActions} tree from it, picks the surface to draw it on, and runs what is chosen.
 * What the entries are lives in StreamActions; how they look lives in each surface; what they do
 * lives here, in {@link #perform}.
 *
 * <h3>Which surface</h3>
 *
 * <pre>
 *   companion panel showing                   the panel's list, so the game stays whole
 *   opened for the external display's phone   the centred sheet, on that phone
 *   otherwise                                 the side panel, over the game
 * </pre>
 *
 * Chosen again on every open, since the companion panel can come and go mid-stream.
 *
 * <h3>Open and closed</h3>
 *
 * Whatever surface is up owns the pad and the keys ({@link ControlsInputRouter}); Game is told
 * when one comes up and when it goes, so it can let go of the host's input and take it back.
 */
public class GameMenu implements Game.GameMenuCallbacks, ControlsSurface.Callbacks {

    public static final long KEY_UP_DELAY = 25;
    private static final long TEST_GAME_FOCUS_DELAY = 10;

    /** Where the settings' import keeps the user's own special keys (KeyConfigHelper JSON). */
    public static final String PREF_NAME = "specialPrefs";

    public static final String KEY_NAME = "special_key";

    /**
     * A gamepad's own entry, offered through {@link GameInputDevice#getGameMenuOptions()}. Drawn
     * on the Mouse page; its runnable stays here and is run by index.
     */
    public static class MenuOption {
        private final String label;
        private final boolean withGameFocus;
        private final Runnable runnable;

        public MenuOption(String label, boolean withGameFocus, Runnable runnable) {
            this.label = label;
            this.withGameFocus = withGameFocus;
            this.runnable = runnable;
        }

        public MenuOption(String label, Runnable runnable) {
            this(label, false, runnable);
        }
    }

    private final Game game;
    private final Context dialogScreenContext;
    private final Handler handler = new Handler(Looper.getMainLooper());

    /** Over the game: the side panel. From the external display's phone: the centred sheet. */
    private final ControlsSurface mainSurface;
    private final ControlsSurface companionSurface;
    // The surface the open tree is on, for what differs between them; null while none is.
    private ControlsSurface activeSurface;

    // What the open tree was built from, for what the tree only points at by index or flag.
    private List<MenuOption> deviceOptions = Collections.emptyList();
    private StreamCapabilities caps;
    private CaptureTool captureTool;

    public GameMenu(Game game, Context dialogScreenContext) {
        this.game = game;
        this.dialogScreenContext = dialogScreenContext;
        if (dialogScreenContext == game) {
            StreamControlsPanel panel = game.getControlsPanel();
            panel.bind(this);
            this.mainSurface = panel;
        } else {
            this.mainSurface = new MenuSheetSurface(dialogScreenContext, this);
        }
        this.companionSurface = new CompanionListSurface(game.getResources(), this);
    }

    public GameMenu(Game game) {
        this(game, game);
    }

    /** Main thread only. */
    @Override
    public void showMenu(GameInputDevice device) {
        hideMenu();

        List<MenuOption> options = device != null ? device.getGameMenuOptions() : null;
        deviceOptions = options != null ? new ArrayList<>(options) : Collections.emptyList();
        PreferenceConfiguration prefs = PreferenceConfiguration.readPreferences(game);
        captureTool = prefs.captureTool;
        caps = capture(prefs);

        boolean overGame = dialogScreenContext == game;
        ControlsSurface surface = overGame && CompanionDisplayManager.isShowing()
                ? companionSurface : mainSurface;
        activeSurface = surface;
        surface.open(StreamActions.build(caps));
        if (surface.isOpen()) {
            game.onControlsOpened();
        } else {
            activeSurface = null;
        }
    }

    private StreamCapabilities capture(PreferenceConfiguration prefs) {
        List<StreamCapabilities.DeviceOption> device = new ArrayList<>();
        for (MenuOption option : deviceOptions) {
            device.add(new StreamCapabilities.DeviceOption(option.label, option.withGameFocus));
        }

        StreamCapabilities.Builder b = StreamCapabilities.builder()
                .captureEnabled(captureTool.isEnabled())
                .locale(game.getResources().getConfiguration().locale)
                .allowChangeMouseMode(game.allowChangeMouseMode)
                .mouseModes(game.getMouseModes(), game.getMouseModeIndex())
                .onExternalDisplay(game.isOnExternalDisplay())
                .companion(CompanionDisplayManager.isConfigured(game), CompanionDisplayManager.isShowing())
                .companionAppSupported(game.isCompanionAppSupported())
                // Rotating turns the activity the sheet is drawn over; from the external display's
                // phone that would be the wrong screen.
                .canRotate(dialogScreenContext == game)
                .zoomEnabled(game.isZoomModeEnabled())
                .hudVisible(game.isHudVisible())
                .virtualControllerShown(game.isVirtualControllerShown())
                .serverCmds(game.getServerCmds())
                .deviceOptions(device)
                .defaultSpecialKeys(!prefs.disableDefaultExtraKeys)
                .customKeys(StreamCapabilities.parseCustomKeys(
                        game.getSharedPreferences(PREF_NAME, Activity.MODE_PRIVATE)
                                .getString(KEY_NAME, "")));
        LiveBitrate bitrate = game.getLiveBitrate();
        if (bitrate != null) {
            b.liveBitrate(bitrate.isAdaptive(), bitrate.getCurrentKbps(), bitrate.getChosenKbps());
        }
        return b.build();
    }

    @Override
    public boolean onPageOpening(@NonNull StreamAction page) {
        if (page.id == StreamAction.Id.SERVER_COMMANDS && page.children != null
                && page.children.isEmpty() && !(activeSurface instanceof StreamControlsPanel)) {
            // The side panel says it on the page itself; the sheet and the companion's list
            // have nowhere to put a paragraph, so they say it in a dialog, as it always was.
            Context themedContext = new ContextThemeWrapper(dialogScreenContext,
                    game.getApplicationInfo().theme);
            new MaterialAlertDialogBuilder(themedContext, R.style.ThemeOverlay_Zefiro_MaterialAlertDialog)
                    .setTitle(R.string.game_dialog_title_server_cmd_empty)
                    .setMessage(R.string.game_dialog_message_server_cmd_empty)
                    .show();
            return false;
        }
        if (page.id == StreamAction.Id.SPECIAL_KEYS && caps != null && caps.customKeysInvalid) {
            // The page still opens, with whatever did parse; the toast says the rest is missing.
            Toast.makeText(game, R.string.wrong_import_format, Toast.LENGTH_SHORT).show();
        }
        return true;
    }

    @Override
    public void onLeaf(@NonNull StreamAction leaf) {
        if (leaf.withGameFocus) {
            runWithGameFocus(() -> perform(leaf));
        } else {
            perform(leaf);
        }
    }

    /** What every leaf does. Main thread only. */
    private void perform(StreamAction action) {
        short[] keys = action.keys();
        if (keys != null) {
            // Special keys and the task manager: pressed at the PC through the stream's keyboard.
            game.sendKeys(keys);
            return;
        }

        switch (action.id) {
            case KEYBOARD:
                game.toggleKeyboard();
                break;
            case GAMEPAD:
                game.toggleVirtualController();
                break;
            case MOUSE_MODE_CHOICE:
                game.chooseMouseMode(action.arg);
                break;
            case LOCAL_CURSOR:
                game.toggleMouseLocalCursor();
                break;
            case DEVICE_OPTION:
                if (action.arg < deviceOptions.size()) {
                    Runnable run = deviceOptions.get(action.arg).runnable;
                    if (run != null) {
                        run.run();
                    }
                }
                break;
            case OVERLAY:
                game.toggleHUD();
                break;
            case SCREENSHOT:
                sendCapture(CaptureTool.Action.SCREENSHOT);
                break;
            case SAVE_REPLAY:
                sendCapture(CaptureTool.Action.SAVE_REPLAY);
                break;
            case RECORD:
                sendCapture(CaptureTool.Action.TOGGLE_RECORDING);
                break;
            case BITRATE_ADAPTIVE: {
                LiveBitrate bitrate = game.getLiveBitrate();
                if (bitrate != null) {
                    boolean adaptive = !bitrate.isAdaptive();
                    bitrate.setAdaptive(adaptive);
                    Toast.makeText(game, adaptive ? R.string.game_menu_bitrate_adaptive_on
                            : R.string.game_menu_bitrate_adaptive_off, Toast.LENGTH_SHORT).show();
                }
                break;
            }
            case BITRATE_CHOICE: {
                LiveBitrate bitrate = game.getLiveBitrate();
                if (bitrate != null) {
                    bitrate.setManual(action.arg);
                }
                break;
            }
            case CLIPBOARD_UPLOAD:
                game.sendClipboard(true);
                break;
            case CLIPBOARD_FETCH:
                game.getClipboard(0);
                break;
            case SERVER_COMMAND:
                game.sendExecServerCmd(action.arg);
                break;
            case PAN_ZOOM:
                game.toggleZoomMode();
                break;
            case ROTATE:
                game.rotateScreen();
                break;
            case COMPANION_SCREEN:
                CompanionDisplayManager.toggle();
                break;
            case COMPANION_APP:
                game.openCompanionApp();
                break;
            case SPECIAL_KEYS_BAR:
                game.toggleKeyboardController();
                break;
            case FULL_KEYBOARD:
                game.toggleFullKeyboard();
                break;
            case TOUCH_SENSITIVITY:
                game.switchTouchSensitivity();
                break;
            case DISCONNECT:
                game.disconnect();
                break;
            case QUIT:
                game.quit();
                break;
            default:
                // Pages never reach here; the surface opens them itself.
                break;
        }
    }

    private void sendCapture(CaptureTool.Action action) {
        short[] keys = captureTool != null ? captureTool.keysFor(action) : null;
        if (keys != null) {
            game.sendKeys(keys);
        }
    }

    /**
     * Run once the game window has focus again. The centred sheet is a Dialog and takes it while
     * up; keys injected before it is back are lost, and a dialog opened before it would be
     * dismissed by the focus change. Polled, since there is no callback for it here. The side
     * panel never takes the focus, so from it this runs at once.
     */
    private void runWithGameFocus(Runnable runnable) {
        if (game.isFinishing()) {
            return;
        }
        if (!game.hasWindowFocus() && dialogScreenContext instanceof Game) {
            handler.postDelayed(() -> runWithGameFocus(runnable), TEST_GAME_FOCUS_DELAY);
            return;
        }
        runnable.run();
    }

    @Override
    public void onClosed() {
        if (!isMenuOpen()) {
            activeSurface = null;
            game.onControlsClosed();
        }
    }

    /** Hand a surface's input to whichever surface is open. Main thread only. */
    @Override
    public boolean onNav(ControlsSurface.Nav nav) {
        if (companionSurface.isOpen()) {
            return companionSurface.onNav(nav);
        }
        return mainSurface.onNav(nav);
    }

    @Override
    public void hideMenu() {
        mainSurface.close();
        // Also when this menu did not open it: the panel's menu is shut on any hide, as before.
        companionSurface.close();
    }

    @Override
    public boolean isMenuOpen() {
        return mainSurface.isOpen() || companionSurface.isOpen();
    }
}
