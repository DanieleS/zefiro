package com.limelight.library;

import android.app.Activity;
import android.app.Dialog;
import android.content.SharedPreferences;
import android.os.Looper;

import androidx.annotation.NonNull;
import androidx.fragment.app.FragmentActivity;
import androidx.preference.PreferenceManager;

import com.limelight.computers.HostEntry;
import com.limelight.computers.HostLibrary;
import com.limelight.computers.HostRepository;
import com.limelight.computers.SnapshotFixtures;
import com.limelight.library.LaunchController.TapAction;
import com.limelight.nvstream.http.ComputerDetails;
import com.limelight.nvstream.http.NvApp;
import com.limelight.nvstream.http.PairingManager.PairState;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowDialog;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * What a tap on a game does, against what the host is running: nothing (start), this game
 * (resume, or the sheet when resuming asks first), another game (the sheet). Plus the parts of a
 * launch the table does not show: the virtual display confirmation, the renderer gate, and a
 * quit letting the library listen to its host again whatever the outcome.
 */
@Config(sdk = {33}, shadows = {com.limelight.shadows.ShadowMoonBridge.class})
@RunWith(RobolectricTestRunner.class)
public class LaunchControllerTest {

    private static final String HOST = "uuid-1";
    private static final int GAME = 7;
    private static final int OTHER_GAME = 9;

    /** Records what would have been started or quit; nothing leaves the test. */
    private static final class FakeServer implements LaunchController.ServerActions {
        final List<String> starts = new ArrayList<>();
        Runnable quitDone;
        Runnable quitFailed;
        /** The control a quit went through, when it disconnected a monitor session; or null. */
        NvApp disconnected;

        @Override
        public void start(@NonNull Activity activity, @NonNull NvApp app,
                          @NonNull ComputerDetails computer, @NonNull String uniqueId,
                          boolean vDisplay) {
            starts.add(app.getAppId() + (vDisplay ? ":vd" : ":primary"));
        }

        @Override
        public void quit(@NonNull Activity activity, @NonNull ComputerDetails computer,
                         @NonNull NvApp app, @NonNull String uniqueId, @NonNull Runnable onDone,
                         @NonNull Runnable onFailed) {
            quitDone = onDone;
            quitFailed = onFailed;
        }

        @Override
        public void disconnectMonitor(@NonNull Activity activity, @NonNull ComputerDetails computer,
                                      @NonNull NvApp disconnect, @NonNull NvApp shown,
                                      @NonNull String uniqueId, @NonNull Runnable onDone,
                                      @NonNull Runnable onFailed) {
            disconnected = disconnect;
            quitDone = onDone;
            quitFailed = onFailed;
        }
    }

    /** Says yes to everything, and remembers what it was asked. */
    private static final class YesPrompts implements LaunchController.Prompts {
        int virtualDisplayAsked;
        int quitAsked;

        @Override
        public void confirmVirtualDisplay(@NonNull ComputerDetails computer, @NonNull Runnable proceed) {
            virtualDisplayAsked++;
            proceed.run();
        }

        @Override
        public void confirmQuit(@NonNull Runnable proceed) {
            quitAsked++;
            proceed.run();
        }
    }

    private FragmentActivity activity;
    private HostRepository repository;
    private HostLibrary library;
    private ComputerDetails computer;
    private FakeServer server;
    private YesPrompts prompts;
    private final List<Runnable> gated = new ArrayList<>();
    private boolean gateOpen = true;

    @Before
    public void setUp() {
        activity = Robolectric.buildActivity(FragmentActivity.class).setup().get();

        computer = new ComputerDetails();
        computer.uuid = HOST;
        computer.name = "Taverna";
        computer.state = ComputerDetails.State.ONLINE;
        computer.pairState = PairState.PAIRED;

        library = mock(HostLibrary.class);
        when(library.getHostUuid()).thenReturn(HOST);
        repository = mock(HostRepository.class);
        when(repository.currentLibrary()).thenReturn(library);
        when(repository.getHost(HOST)).thenReturn(new HostEntry(computer));
        when(repository.uniqueId()).thenReturn("client-id");

        server = new FakeServer();
        prompts = new YesPrompts();
        setPreferences(false, false);
    }

    private LaunchController controller() {
        LaunchController.StartGate gate = start -> {
            if (gateOpen) {
                start.run();
            } else {
                gated.add(start);
            }
        };
        return new LaunchController(activity, repository, gate, null, server, prompts);
    }

    private void setPreferences(boolean useVirtualDisplay, boolean resumeWithoutConfirm) {
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(activity);
        prefs.edit()
                .putBoolean("checkbox_use_virtual_display", useVirtualDisplay)
                .putBoolean("checkbox_resume_without_confirm", resumeWithoutConfirm)
                .commit();
    }

    private static LibraryApp game(int id, boolean running) {
        return new LibraryApp(new NvApp("Game " + id, "uuid-game-" + id, id, false), running, false);
    }

    private static boolean sheetShowing() {
        Dialog dialog = ShadowDialog.getLatestDialog();
        return dialog != null && dialog.isShowing();
    }

    // --- The table itself ---

    @Test
    public void decision_nothingRunning_starts() {
        assertEquals(TapAction.START, LaunchController.decideTap(0, GAME, false));
        assertEquals(TapAction.START, LaunchController.decideTap(0, GAME, true));
    }

    @Test
    public void decision_thisGameRunning_resumesOnlyWithoutConfirm() {
        assertEquals(TapAction.RESUME, LaunchController.decideTap(GAME, GAME, true));
        assertEquals(TapAction.SHEET, LaunchController.decideTap(GAME, GAME, false));
    }

    @Test
    public void decision_anotherGameRunning_alwaysOpensTheSheet() {
        assertEquals(TapAction.SHEET, LaunchController.decideTap(OTHER_GAME, GAME, true));
        assertEquals(TapAction.SHEET, LaunchController.decideTap(OTHER_GAME, GAME, false));
    }

    // --- Through the controller ---

    @Test
    public void tap_nothingRunning_startsInThePreferredDisplay() {
        controller().onTileClicked(game(GAME, false));

        assertEquals(1, server.starts.size());
        assertEquals(GAME + ":primary", server.starts.get(0));
        assertFalse(sheetShowing());
    }

    @Test
    public void tap_nothingRunning_virtualDisplayNotReady_asksFirst() {
        setPreferences(true, false);
        computer.vDisplaySupported = true;
        computer.vDisplayDriverReady = false;

        controller().onTileClicked(game(GAME, false));

        assertEquals(1, prompts.virtualDisplayAsked);
        assertEquals(GAME + ":vd", server.starts.get(0));
    }

    @Test
    public void tap_thisGameRunning_resumeWithoutConfirm_resumes() {
        setPreferences(false, true);
        computer.runningGameId = GAME;

        controller().onTileClicked(game(GAME, true));

        assertEquals(1, server.starts.size());
        assertEquals(0, prompts.quitAsked);
        assertFalse(sheetShowing());
    }

    @Test
    public void tap_thisGameRunning_confirmFirst_opensTheSheet() {
        computer.runningGameId = GAME;

        controller().onTileClicked(game(GAME, true));
        Shadows.shadowOf(Looper.getMainLooper()).idle();

        assertTrue(server.starts.isEmpty());
        assertTrue(sheetShowing());
    }

    @Test
    public void tap_anotherGameRunning_opensTheSheet() {
        setPreferences(false, true);
        computer.runningGameId = OTHER_GAME;

        controller().onTileClicked(game(GAME, false));
        Shadows.shadowOf(Looper.getMainLooper()).idle();

        assertTrue(server.starts.isEmpty());
        assertTrue(sheetShowing());
    }

    @Test
    public void tap_anOfflineHostRunsNothing_soTheTapStarts() {
        // The host was last seen in a game, but it is offline: it is not running it.
        computer.runningGameId = OTHER_GAME;
        computer.state = ComputerDetails.State.OFFLINE;

        controller().onTileClicked(game(GAME, false));

        assertEquals(1, server.starts.size());
    }

    @Test
    public void start_waitsForTheRenderer() {
        gateOpen = false;

        controller().onTileClicked(game(GAME, false));
        assertTrue(server.starts.isEmpty());

        gated.get(0).run();
        assertEquals(1, server.starts.size());
    }

    @Test
    public void quitThenStart_asksBeforeEndingTheRunningGame() {
        computer.runningGameId = OTHER_GAME;

        controller().quitThenStart(game(GAME, false).app, false);

        assertEquals(1, prompts.quitAsked);
        assertEquals(GAME + ":primary", server.starts.get(0));
    }

    @Test
    public void quit_suspendsTheLibrary_thenListensAndPollsOnSuccess() {
        computer.runningGameId = GAME;

        controller().quit(game(GAME, true).app);
        verify(library).setSuspendUpdates(true);
        verify(library, never()).setSuspendUpdates(false);

        server.quitDone.run();
        Shadows.shadowOf(Looper.getMainLooper()).idle();

        org.mockito.InOrder order = inOrder(library);
        order.verify(library).setSuspendUpdates(true);
        order.verify(library).setSuspendUpdates(false);
        order.verify(library).pollNow();
    }

    @Test
    public void quit_ofAHeldMonitorSessionDisconnectsTheMonitor() {
        // The host reports no game for the session; the library offers its Remote Monitor entry.
        NvApp monitor = new NvApp("Remote Monitor", NvApp.REMOTE_MONITOR_UUID, 11, true);
        NvApp disconnect = new NvApp("Disconnect Monitor", NvApp.DISCONNECT_MONITOR_UUID, 92, true);
        LibraryModel.Specials specials = new LibraryModel.Specials(null, monitor, null,
                new NvApp("Resume", NvApp.SESSION_RESUME_UUID, 91, true), disconnect);
        when(library.getSnapshot()).thenReturn(SnapshotFixtures.ready(HOST,
                new ArrayList<LibraryApp>(), monitor, specials));

        controller().quit(monitor);
        assertEquals(disconnect, server.disconnected);
        server.quitDone.run();
        Shadows.shadowOf(Looper.getMainLooper()).idle();
        verify(library).pollNow();

        // A game is still quit the plain way.
        server.disconnected = null;
        controller().quit(game(GAME, true).app);
        assertEquals(null, server.disconnected);
    }

    @Test
    public void quit_aFailureStillLetsTheLibraryListenAgain() {
        computer.runningGameId = GAME;

        controller().quit(game(GAME, true).app);
        server.quitFailed.run();
        Shadows.shadowOf(Looper.getMainLooper()).idle();

        verify(library).setSuspendUpdates(false);
        verify(library, never()).pollNow();
    }

    @Test
    public void noHostAnswerYet_startsNothing() {
        when(repository.getHost(HOST)).thenReturn(null);

        controller().onTileClicked(game(GAME, false));

        assertTrue(server.starts.isEmpty());
    }

    @Test
    public void showActions_raisesTheSheet() {
        Dialog sheet = controller().showActions(game(GAME, false));

        assertNotNull(sheet);
        assertTrue(sheet.isShowing());
        assertTrue(server.starts.isEmpty());
    }
}
