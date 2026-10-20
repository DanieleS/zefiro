package com.limelight.library;

import android.app.Activity;
import android.app.Dialog;
import android.content.ComponentName;
import android.graphics.Bitmap;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.RequiresApi;
import androidx.annotation.StringRes;
import androidx.annotation.VisibleForTesting;
import androidx.fragment.app.FragmentActivity;

import com.limelight.R;
import com.limelight.companion.CompanionAppLauncher;
import com.limelight.companion.CompanionApps;
import com.limelight.computers.HostEntry;
import com.limelight.computers.HostLibrary;
import com.limelight.computers.HostRepository;
import com.limelight.main.ArtExport;
import com.limelight.nvstream.http.ComputerDetails;
import com.limelight.nvstream.http.NvApp;
import com.limelight.preferences.PreferenceConfiguration;
import com.limelight.stats.GameStatsSheet;
import com.limelight.stats.HostSession;
import com.limelight.ui.MenuSheet;
import com.limelight.utils.ServerHelper;
import com.limelight.utils.ShortcutHelper;
import com.limelight.utils.UiHelper;

/**
 * Starts, resumes and quits the selected host's games, and offers everything else that can be
 * done to one (the actions sheet).
 *
 * <p>This is the old library's launch logic ({@code onAppClicked}, {@code startApp},
 * {@code quitThenStart}, {@code quitApp}, {@code launchVirtualDisplay},
 * {@code showRemoteSessionMenu}), taken out of the activity. The See all grid, the shelves, the
 * hero and the companion's Play all launch through it, so a game starts the same way from
 * wherever it is picked. It is owned by the main activity, never by a section: a quit in flight
 * must outlive the section's views.
 *
 * <h3>A tap on a tile</h3>
 *
 * <pre>
 *   host running        tapped game    resume without confirm    does
 *   ─────────────       ───────────    ──────────────────────    ──────────────────────────────
 *   nothing             any            any                       START (in the preferred display)
 *   this game           this one       on                        RESUME
 *   this game           this one       off                       the actions sheet
 *   another game        any            any                       the actions sheet
 * </pre>
 *
 * The table is {@link #decideTap}, a pure function, so a test pins it without a host.
 *
 * <h3>Every start waits for the renderer</h3>
 *
 * The stream picks its decoder from the GL renderer, which the main screen learns once per
 * system build (see {@code RendererProbe}). A start asked for before that is held until it is
 * known, through {@link StartGate}, never dropped and never run early.
 *
 * <h3>Quitting</h3>
 *
 * While a quit is in flight the library ignores the host ({@link HostLibrary#setSuspendUpdates}),
 * so the game it still reports as running does not flash back; once the host has answered, the
 * library listens again and asks for the app list at once. Unlike the old library, which only let
 * go on success, a failed quit lets go too, or the library would stay deaf to its host for good.
 *
 * <h3>Threading</h3>
 *
 * Main thread only. The quit's answer comes from a background thread and is posted back to main.
 */
public class LaunchController {

    /** What a tap on a tile does; see the class comment. */
    public enum TapAction {
        START,
        RESUME,
        SHEET
    }

    /** Holds a start until the stream can choose its decoder; {@code RendererProbe} in production. */
    public interface StartGate {
        void whenReady(@NonNull Runnable start);
    }

    /**
     * The calls into {@link ServerHelper} that leave this screen or talk to the host: a seam, so
     * a test can see what would have been started without starting a stream.
     */
    public interface ServerActions {
        void start(@NonNull Activity activity, @NonNull NvApp app, @NonNull ComputerDetails computer,
                   @NonNull String uniqueId, boolean vDisplay);

        /** Quits the host's game; exactly one of the two callbacks runs, on any thread. */
        void quit(@NonNull Activity activity, @NonNull ComputerDetails computer, @NonNull NvApp app,
                  @NonNull String uniqueId, @NonNull Runnable onDone, @NonNull Runnable onFailed);
    }

    /** The confirmations a launch may ask for; dialogs in production, answered at once in tests. */
    public interface Prompts {
        /** The host cannot make a virtual display right now: ask whether to go ahead anyway. */
        void confirmVirtualDisplay(@NonNull ComputerDetails computer, @NonNull Runnable proceed);

        /** Starting this ends the game the host is running: ask first. */
        void confirmQuit(@NonNull Runnable proceed);
    }

    private final FragmentActivity activity;
    private final HostRepository repository;
    private final StartGate gate;
    @Nullable
    private final ArtExport artExport;
    private final ServerActions server;
    private final Prompts prompts;
    private final Handler main = new Handler(Looper.getMainLooper());

    public LaunchController(@NonNull FragmentActivity activity, @NonNull HostRepository repository,
                            @NonNull StartGate gate, @NonNull ArtExport artExport) {
        this(activity, repository, gate, artExport, new HelperServerActions(),
                new DialogPrompts(activity));
    }

    @VisibleForTesting
    public LaunchController(@NonNull FragmentActivity activity, @NonNull HostRepository repository,
                            @NonNull StartGate gate, @Nullable ArtExport artExport,
                            @NonNull ServerActions server, @NonNull Prompts prompts) {
        this.activity = activity;
        this.repository = repository;
        this.gate = gate;
        this.artExport = artExport;
        this.server = server;
        this.prompts = prompts;
    }

    /** Drops the quit results still to come; from the activity's onDestroy. */
    public void release() {
        main.removeCallbacksAndMessages(null);
    }

    /** The table in the class comment. */
    @NonNull
    public static TapAction decideTap(int runningAppId, int tappedAppId, boolean resumeWithoutConfirm) {
        if (runningAppId == 0) {
            return TapAction.START;
        }
        if (runningAppId == tappedAppId && resumeWithoutConfirm) {
            return TapAction.RESUME;
        }
        return TapAction.SHEET;
    }

    /**
     * Whether a start in a virtual display has to be confirmed first: the host either has no
     * virtual display driver, or has one that is not ready. Ported from the old library's checks.
     */
    public static boolean needsVirtualDisplayConfirmation(boolean vDisplay, @NonNull ComputerDetails computer) {
        return vDisplay && !(computer.vDisplaySupported && computer.vDisplayDriverReady);
    }

    // --- Tiles ---

    /** A tap (or A) on a game's tile. */
    public void onTileClicked(@NonNull LibraryApp app) {
        PreferenceConfiguration prefs = PreferenceConfiguration.readPreferences(activity);
        switch (decideTap(runningAppId(), app.appId(), prefs.resumeWithoutConfirm)) {
            case START:
                start(app.app, prefs.useVirtualDisplay);
                break;
            case RESUME:
                // As the old library: rejoining asks for no confirmation, and keeps the preferred
                // display.
                launch(app.app, prefs.useVirtualDisplay);
                break;
            case SHEET:
            default:
                showActions(app);
                break;
        }
    }

    /** A long-press or Y on a game: everything that can be done to it, as a sheet. */
    @Nullable
    public Dialog showActions(@NonNull LibraryApp app) {
        HostLibrary library = repository.currentLibrary();
        HostLibrary.Snapshot snapshot = library != null ? library.getSnapshot() : null;
        PreferenceConfiguration prefs = PreferenceConfiguration.readPreferences(activity);

        GameActionsSheet.State state = new GameActionsSheet.State(
                runningAppId(),
                prefs.useVirtualDisplay,
                CompanionAppLauncher.isSupported(activity),
                snapshot != null && snapshot.statsAvailable,
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.O);
        return GameActionsSheet.show(activity, app, state, companionLabel(app.app), this::run);
    }

    // --- Launching ---

    /** Starts a game, confirming first when the virtual display it asks for is not available. */
    public void start(@NonNull NvApp app, boolean vDisplay) {
        ComputerDetails computer = selectedComputer();
        if (computer == null) {
            return;
        }
        if (needsVirtualDisplayConfirmation(vDisplay, computer)) {
            // Confirmed, the start still asks the host for one: it may manage after all.
            prompts.confirmVirtualDisplay(computer, () -> launch(app, true));
        } else {
            launch(app, vDisplay);
        }
    }

    /** Rejoins the game the host is running. */
    public void resume(@NonNull NvApp app) {
        launch(app, false);
    }

    /** Quits the host's game after a confirmation, then has the library ask the host afresh. */
    public void quit(@NonNull NvApp app) {
        prompts.confirmQuit(() -> doQuit(app));
    }

    /** Ends the game the host is running and starts this one, each step confirmed. */
    public void quitThenStart(@NonNull NvApp app, boolean vDisplay) {
        ComputerDetails computer = selectedComputer();
        if (computer == null) {
            return;
        }
        // Starting another game is what ends the running one; the host does the quitting.
        if (needsVirtualDisplayConfirmation(vDisplay, computer)) {
            prompts.confirmVirtualDisplay(computer,
                    () -> prompts.confirmQuit(() -> launch(app, true)));
        } else {
            prompts.confirmQuit(() -> launch(app, vDisplay));
        }
    }

    /**
     * Starts the host's Virtual Display entry, after a confirmation when another game is
     * running, since starting it ends that game. Nothing when the host advertises none.
     */
    public void launchVirtualDisplay() {
        HostLibrary.Snapshot snapshot = currentSnapshot();
        final NvApp app = snapshot != null ? snapshot.specials.virtualDisplay : null;
        if (app == null) {
            return;
        }
        // The entry is itself a virtual-display session, so it is launched directly rather than
        // through the "use virtual display" preference.
        int running = runningAppId();
        if (running != 0 && running != app.getAppId()) {
            prompts.confirmQuit(() -> launch(app, false));
        } else {
            launch(app, false);
        }
    }

    /**
     * Offers the host's remote-session entries. Remote Monitor and Remote Input join alongside
     * whatever is running rather than replacing it, so they start without a quit confirmation.
     */
    @Nullable
    public Dialog showRemoteSessionMenu() {
        HostLibrary.Snapshot snapshot = currentSnapshot();
        if (snapshot == null) {
            return null;
        }
        MenuSheet sheet = new MenuSheet(activity)
                .setTitle(activity.getString(R.string.action_remote_session));
        final NvApp monitor = snapshot.specials.remoteMonitor;
        final NvApp input = snapshot.specials.remoteInput;
        if (monitor != null) {
            sheet.add(activity.getString(R.string.remote_session_monitor), () -> launch(monitor, false));
        }
        if (input != null) {
            sheet.add(activity.getString(R.string.remote_session_input), () -> launch(input, false));
        }
        return sheet.isEmpty() ? null : sheet.showCentered(activity);
    }

    // --- The actions sheet's other rows ---

    private void run(@NonNull GameActionsSheet.Item item, @NonNull LibraryApp app) {
        PreferenceConfiguration prefs = PreferenceConfiguration.readPreferences(activity);
        switch (item) {
            case START_PRIMARY_DISPLAY:
                start(app.app, false);
                break;
            case START_VIRTUAL_DISPLAY:
                start(app.app, true);
                break;
            case RESUME:
                resume(app.app);
                break;
            case QUIT:
                quit(app.app);
                break;
            case QUIT_AND_START:
                // The plain label stands for the preferred display, the other row for the other.
                quitThenStart(app.app, prefs.useVirtualDisplay);
                break;
            case QUIT_AND_START_PRIMARY_DISPLAY:
                quitThenStart(app.app, false);
                break;
            case QUIT_AND_START_VIRTUAL_DISPLAY:
                quitThenStart(app.app, true);
                break;
            case HIDE:
                setHidden(app, !app.hidden);
                break;
            case COMPANION_APP:
                CompanionAppLauncher.showPicker(activity, app.app.getAppUUID(), app.app.getAppName(), null);
                break;
            case STATS:
                showStats(app.app);
                break;
            case DETAILS:
                com.limelight.utils.Dialog.displayDialog(activity,
                        activity.getString(R.string.title_details), app.app.toString(), false);
                break;
            case PIN_SHORTCUT:
                // Offered from O only; the check is for the compiler's sake.
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    pinShortcut(app.app);
                }
                break;
            case EXPORT_LAUNCHER:
                exportLauncher(app.app);
                break;
        }
    }

    private void setHidden(LibraryApp app, boolean hidden) {
        HostLibrary library = repository.currentLibrary();
        if (library != null) {
            library.setHidden(app.appId(), hidden);
        }
    }

    private void showStats(NvApp app) {
        HostLibrary library = repository.currentLibrary();
        HostSession session = library != null ? library.session() : null;
        if (session != null) {
            GameStatsSheet.show(activity, session, app.getAppUUID(), app.getAppName());
        }
    }

    // The cover comes from the loader's memory, not from a tile: the sheet may be raised from a
    // shelf, the hero or the companion, none of which holds a plain bitmap to read. A cover not
    // in memory (or the host's stand-in) gives the app icon instead, as a shortcut always could.
    @RequiresApi(Build.VERSION_CODES.O)
    private void pinShortcut(NvApp app) {
        ComputerDetails computer = selectedComputer();
        HostLibrary library = repository.currentLibrary();
        if (computer == null || library == null) {
            return;
        }
        Bitmap cover = library.getArtLoader().getBitmap(library.artTuple(app));
        if (!new ShortcutHelper(activity).createPinnedGameShortcut(computer, app, cover)) {
            Toast.makeText(activity, R.string.unable_to_pin_shortcut, Toast.LENGTH_LONG).show();
        }
    }

    private void exportLauncher(NvApp app) {
        final ComputerDetails computer = selectedComputer();
        if (computer == null || artExport == null) {
            return;
        }
        if (app.getAppUUID() == null || app.getAppUUID().isEmpty()) {
            // Sunshine names no app UUID, and a file without one cannot be told apart from its
            // neighbours: say so before going on, as the old library did.
            UiHelper.displayConfirmationDialog(activity,
                    activity.getString(R.string.title_export_sunshine_launcher_file),
                    activity.getString(R.string.message_export_sunshine_launcher_file),
                    activity.getString(R.string.proceed),
                    activity.getString(R.string.cancel),
                    () -> artExport.export(computer, app),
                    null);
        } else {
            artExport.export(computer, app);
        }
    }

    // The row names the app already chosen for the game, so the user sees it without opening
    // the picker. Only asked when the row is offered.
    @Nullable
    private String companionLabel(NvApp app) {
        if (!CompanionAppLauncher.isSupported(activity)) {
            return null;
        }
        ComponentName current = CompanionApps.get(activity, app.getAppUUID(), app.getAppName());
        return current != null
                ? activity.getString(R.string.applist_menu_companion_app_set,
                        CompanionAppLauncher.labelFor(activity, current))
                : activity.getString(R.string.applist_menu_companion_app);
    }

    // --- Internals ---

    // Every start comes through here: the host as last polled, this device's id, the renderer.
    private void launch(@NonNull NvApp app, boolean vDisplay) {
        final ComputerDetails computer = selectedComputer();
        final String uniqueId = repository.uniqueId();
        if (computer == null) {
            return;
        }
        if (uniqueId == null) {
            toast(R.string.error_manager_not_running);
            return;
        }
        gate.whenReady(() -> server.start(activity, app, computer, uniqueId, vDisplay));
    }

    private void doQuit(@NonNull NvApp app) {
        final ComputerDetails computer = selectedComputer();
        final String uniqueId = repository.uniqueId();
        final HostLibrary library = repository.currentLibrary();
        if (computer == null || library == null) {
            return;
        }
        if (uniqueId == null) {
            toast(R.string.error_manager_not_running);
            return;
        }
        library.setSuspendUpdates(true);
        // Either outcome lets the library listen again; the success also asks the host now.
        server.quit(activity, computer, app, uniqueId,
                () -> main.post(() -> listenAgain(library, true)),
                () -> main.post(() -> listenAgain(library, false)));
    }

    // Main thread. A library closed by a host switch meanwhile is left alone.
    private static void listenAgain(HostLibrary library, boolean pollNow) {
        if (library.isClosed()) {
            return;
        }
        library.setSuspendUpdates(false);
        if (pollNow) {
            library.pollNow();
        }
    }

    /**
     * The id of what the selected host is running, or 0. An offline host still carries the game
     * it was last seen in, but it is not running it, as the library's snapshot has it too.
     */
    private int runningAppId() {
        HostEntry host = selectedHost();
        if (host == null || host.details.state == ComputerDetails.State.OFFLINE) {
            return 0;
        }
        return host.details.runningGameId;
    }

    @Nullable
    private HostEntry selectedHost() {
        HostLibrary library = repository.currentLibrary();
        return library != null ? repository.getHost(library.getHostUuid()) : null;
    }

    /** The selected host as last polled; null (after telling the user) when it never answered. */
    @Nullable
    private ComputerDetails selectedComputer() {
        HostEntry host = selectedHost();
        if (host == null) {
            toast(R.string.pair_pc_offline);
            return null;
        }
        return host.details;
    }

    @Nullable
    private HostLibrary.Snapshot currentSnapshot() {
        HostLibrary library = repository.currentLibrary();
        return library != null ? library.getSnapshot() : null;
    }

    private void toast(@StringRes int message) {
        Toast.makeText(activity, message, Toast.LENGTH_SHORT).show();
    }

    // --- Production seams ---

    private static final class HelperServerActions implements ServerActions {
        @Override
        public void start(@NonNull Activity activity, @NonNull NvApp app,
                          @NonNull ComputerDetails computer, @NonNull String uniqueId,
                          boolean vDisplay) {
            ServerHelper.doStart(activity, app, computer, uniqueId, vDisplay);
        }

        @Override
        public void quit(@NonNull Activity activity, @NonNull ComputerDetails computer,
                         @NonNull NvApp app, @NonNull String uniqueId, @NonNull Runnable onDone,
                         @NonNull Runnable onFailed) {
            ServerHelper.doQuit(activity, computer, app, uniqueId, onDone, onFailed);
        }
    }

    private static final class DialogPrompts implements Prompts {
        private final Activity activity;

        DialogPrompts(Activity activity) {
            this.activity = activity;
        }

        @Override
        public void confirmVirtualDisplay(@NonNull ComputerDetails computer, @NonNull Runnable proceed) {
            UiHelper.displayVdisplayConfirmationDialog(activity, computer, proceed, null);
        }

        @Override
        public void confirmQuit(@NonNull Runnable proceed) {
            UiHelper.displayQuitConfirmationDialog(activity, proceed, null);
        }
    }
}
