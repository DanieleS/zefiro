package com.limelight.computers;

import android.content.Context;
import android.os.Looper;

import androidx.preference.PreferenceManager;
import androidx.test.core.app.ApplicationProvider;

import com.limelight.TestLogSuppressor;
import com.limelight.library.LibraryApp;
import com.limelight.library.LibraryIntents;
import com.limelight.nvstream.http.ComputerDetails;
import com.limelight.nvstream.http.NvApp;
import com.limelight.nvstream.http.PairingManager.PairState;
import com.limelight.utils.AppLaunchHistory;

import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

/**
 * The repository against a fake service, with every executor direct so a poll travels from the
 * "polling thread" to the LiveData in one call. Robolectric only for the Context (preferences,
 * cache dir) and the main looper LiveData insists on.
 */
@Config(sdk = {33})
@RunWith(RobolectricTestRunner.class)
public class HostRepositoryTest {

    private static final Executor DIRECT = Runnable::run;

    /** The service as the repository sees it: listeners, live details, pollers. */
    private static final class FakeGateway implements CmsGateway {
        final List<ComputerManagerListener> listeners = new ArrayList<>();
        final Map<String, ComputerDetails> live = new HashMap<>();
        final List<FakePoller> pollers = new ArrayList<>();

        ComputerDetails addHost(String uuid, String name) {
            ComputerDetails details = new ComputerDetails();
            details.uuid = uuid;
            details.name = name;
            details.state = ComputerDetails.State.ONLINE;
            details.pairState = PairState.PAIRED;
            live.put(uuid, details);
            return details;
        }

        /** A poll of one host, reported to everyone, as a polling thread would. */
        void emit(ComputerDetails details) {
            for (ComputerManagerListener listener : new ArrayList<>(listeners)) {
                listener.notifyComputerUpdated(details);
            }
        }

        @Override
        public void waitForReady() {
        }

        @Override
        public void addPollingListener(ComputerManagerListener listener) {
            if (!listeners.contains(listener)) {
                listeners.add(listener);
            }
            // The initial report goes to the newcomer only, as the real binder's does.
            for (ComputerDetails details : new ArrayList<>(live.values())) {
                listener.notifyComputerUpdated(details);
            }
        }

        @Override
        public void removePollingListener(ComputerManagerListener listener) {
            listeners.remove(listener);
        }

        @Override
        public void waitForPollingStopped() {
        }

        @Override
        public String getUniqueId() {
            return "0123456789ABCDEF";
        }

        @Override
        public ComputerDetails getComputer(String uuid) {
            return live.get(uuid);
        }

        @Override
        public void invalidateStateForComputer(String uuid) {
        }

        @Override
        public void removeComputer(ComputerDetails computer) {
            live.remove(computer.uuid);
        }

        @Override
        public AppListPoller createAppListPoller(String uuid) {
            if (!live.containsKey(uuid)) {
                return null;
            }
            FakePoller poller = new FakePoller();
            pollers.add(poller);
            return poller;
        }
    }

    private static final class FakePoller implements CmsGateway.AppListPoller {
        boolean running;
        int polls;

        @Override
        public void start() {
            running = true;
        }

        @Override
        public void stop() {
            running = false;
        }

        @Override
        public void pollNow() {
            polls++;
        }
    }

    private Context context;
    private FakeGateway gateway;
    private final List<HostLibrary> librariesMade = new ArrayList<>();
    private final List<String> shortcutsPublished = new ArrayList<>();
    // Hero image fetches, queued and never run: they would talk to a real host.
    private final List<Runnable> artFetches = new ArrayList<>();
    private boolean disconnected;
    private HostRepository repo;

    @BeforeClass
    public static void suppressInvalidIdLogs() {
        TestLogSuppressor.install();
    }

    @Before
    public void setUp() {
        context = ApplicationProvider.getApplicationContext();
        gateway = new FakeGateway();
        repo = newRepository();
    }

    private HostRepository newRepository() {
        return newRepository(DIRECT, DIRECT);
    }

    private HostRepository newRepository(Executor main, Executor background) {
        CmsGateway.Connector connector = new CmsGateway.Connector() {
            @Override
            public void connect(Callback callback) {
                callback.onConnected(gateway);
            }

            @Override
            public void disconnect() {
                disconnected = true;
            }
        };
        HostLibrary.Factory libraries = (uuid, g, uniqueId, listener) -> {
            HostLibrary library = new HostLibrary(context, uuid, g, uniqueId, DIRECT, DIRECT, listener);
            librariesMade.add(library);
            return library;
        };
        return new HostRepository(context, connector, main, background, artFetches::add, libraries,
                new RunningGameResolver(context),
                new ShortcutPublisher(details -> shortcutsPublished.add(details.uuid)));
    }

    /** Runs queued tasks, and whatever they queue, until nothing is left. */
    private static void drain(List<Runnable> queue) {
        while (!queue.isEmpty()) {
            queue.remove(0).run();
        }
    }

    private List<String> hostNames() {
        List<String> names = new ArrayList<>();
        for (HostEntry entry : repo.getHosts().getValue()) {
            names.add(entry.details.name);
        }
        return names;
    }

    @Test
    public void everyHostIsPublishedSortedByName() {
        gateway.addHost("uuid-z", "Zeta");
        gateway.addHost("uuid-a", "alpha");

        repo.start();

        assertEquals(java.util.Arrays.asList("alpha", "Zeta"), hostNames());
    }

    @Test
    public void everyPollIsFannedOutAsANewEntry() {
        ComputerDetails live = gateway.addHost("uuid-a", "Alpha");
        repo.start();
        HostEntry first = repo.getHost("uuid-a");

        live.runningGameId = 7;
        live.runningGameName = "Some Game";
        gateway.emit(live);

        HostEntry second = repo.getHost("uuid-a");
        assertNotSame(first, second);
        assertEquals(0, first.details.runningGameId);
        assertEquals(7, second.details.runningGameId);
        assertEquals("Some Game", second.runningAppName);
        assertSame(second, repo.getHosts().getValue().get(0));
    }

    @Test
    public void theHeroFetchStaysOffThePollingPath() {
        ComputerDetails live = gateway.addHost("uuid-a", "Alpha");
        repo.start();
        assertTrue(artFetches.isEmpty());

        live.runningGameId = 7;
        live.runningGameName = "Some Game";
        gateway.emit(live);

        // Queued on its own executor and not yet run, yet the poll already reached the list:
        // a slow host's image can no longer hold back anyone's updates.
        assertEquals(1, artFetches.size());
        assertEquals(7, repo.getHost("uuid-a").details.runningGameId);

        // Once per game, however often the host is polled.
        gateway.emit(live);
        assertEquals(1, artFetches.size());
    }

    @Test
    public void theHeroIsFetchedAgainWhenTheGameComesBack() {
        ComputerDetails live = gateway.addHost("uuid-a", "Alpha");
        repo.start();

        live.runningGameId = 7;
        live.runningGameName = "Some Game";
        gateway.emit(live);
        assertEquals(1, artFetches.size());

        // Quitting drops the image with the game...
        live.runningGameId = 0;
        live.runningGameName = null;
        gateway.emit(live);
        assertNull(repo.getHost("uuid-a").runningBackground);

        // ...so playing it again must be allowed to fetch it again, or "Continue" would stay
        // without its hero for the rest of a launcher's life.
        live.runningGameId = 7;
        live.runningGameName = "Some Game";
        gateway.emit(live);
        assertEquals(2, artFetches.size());
    }

    @Test
    public void publishedEntriesDoNotFollowTheLiveObject() {
        ComputerDetails live = gateway.addHost("uuid-a", "Alpha");
        repo.start();

        HostEntry published = repo.getHost("uuid-a");
        assertNotSame(live, published.details);

        // A polling thread keeps writing to the live object after the callback returned.
        live.state = ComputerDetails.State.OFFLINE;
        live.name = "Renamed";
        live.runningGameId = 99;

        assertEquals(ComputerDetails.State.ONLINE, published.details.state);
        assertEquals("Alpha", published.details.name);
        assertEquals(0, published.details.runningGameId);
        assertSame(published, repo.getHosts().getValue().get(0));
    }

    @Test
    public void stopRemovesOnlyItsOwnListener() {
        ComputerManagerListener otherScreen = details -> { };
        gateway.listeners.add(otherScreen);

        repo.start();
        assertEquals(2, gateway.listeners.size());

        repo.stop();
        assertEquals(1, gateway.listeners.size());
        assertTrue(gateway.listeners.contains(otherScreen));

        // Starting twice is still one listener.
        repo.start();
        repo.start();
        assertEquals(2, gateway.listeners.size());
    }

    @Test
    public void suspendingForPairingStopsAndResumesTheListener() {
        repo.start();
        assertEquals(1, gateway.listeners.size());

        repo.suspendPollingBlocking();
        assertTrue(gateway.listeners.isEmpty());

        repo.resumePolling();
        assertEquals(1, gateway.listeners.size());
    }

    @Test
    public void theLastHostIsSelectedFromPreferences() {
        PreferenceManager.getDefaultSharedPreferences(context).edit()
                .putString(LibraryIntents.PREF_LAST_HOST, "uuid-a").commit();
        gateway.addHost("uuid-a", "Alpha");

        repo = newRepository();
        assertEquals("uuid-a", repo.getSelectedHostUuid().getValue());
        // Before the service is ready there is no library yet, only the promise of one.
        assertEquals(HostLibrary.State.LOADING, repo.getLibrary().getValue().state);

        repo.start();
        assertNotNull(repo.currentLibrary());
        assertEquals("uuid-a", repo.currentLibrary().getHostUuid());
    }

    @Test
    public void aHostSwitchClosesTheOldLibrary() {
        gateway.addHost("uuid-a", "Alpha");
        gateway.addHost("uuid-b", "Beta");
        repo.start();

        repo.select("uuid-a");
        HostLibrary first = repo.currentLibrary();
        assertFalse(first.isClosed());
        assertTrue(gateway.pollers.get(0).running);

        repo.select("uuid-b");

        assertTrue(first.isClosed());
        assertFalse(gateway.pollers.get(0).running);
        HostLibrary second = repo.currentLibrary();
        assertNotSame(first, second);
        assertEquals("uuid-b", second.getHostUuid());
        assertEquals("uuid-b", repo.getLibrary().getValue().hostUuid);
        assertEquals("uuid-b", PreferenceManager.getDefaultSharedPreferences(context)
                .getString(LibraryIntents.PREF_LAST_HOST, null));

        // Selecting the same host again keeps its library.
        repo.select("uuid-b");
        assertSame(second, repo.currentLibrary());
    }

    @Test
    public void anOfflineHostIsAStateNotAnExit() {
        ComputerDetails live = gateway.addHost("uuid-a", "Alpha");
        repo.start();
        repo.select("uuid-a");

        live.state = ComputerDetails.State.OFFLINE;
        gateway.emit(live);

        assertEquals(HostLibrary.State.OFFLINE, repo.getLibrary().getValue().state);
        assertFalse(repo.currentLibrary().isClosed());

        live.state = ComputerDetails.State.ONLINE;
        live.pairState = PairState.NOT_PAIRED;
        gateway.emit(live);
        assertEquals(HostLibrary.State.UNPAIRED, repo.getLibrary().getValue().state);
    }

    @Test
    public void theHostsListMakesTheLibraryReady() {
        ComputerDetails live = gateway.addHost("uuid-a", "Alpha");
        repo.start();
        repo.select("uuid-a");
        assertEquals(HostLibrary.State.LOADING, repo.getLibrary().getValue().state);

        repo.currentLibrary().setHidden(2, true);
        live.rawAppList = "<root status_code=\"200\">"
                + "<App><AppTitle>Bravo</AppTitle><ID>2</ID><IDX>1</IDX></App>"
                + "<App><AppTitle>Alpha</AppTitle><ID>1</ID><IDX>0</IDX></App>"
                + "</root>";
        live.runningGameId = 2;
        gateway.emit(live);

        HostLibrary.Snapshot snapshot = repo.getLibrary().getValue();
        assertEquals(HostLibrary.State.READY, snapshot.state);
        assertEquals(2, snapshot.apps.size());
        // Host order, by IDX.
        LibraryApp alpha = snapshot.apps.get(0);
        LibraryApp bravo = snapshot.apps.get(1);
        assertEquals("Alpha", alpha.app.getAppName());
        assertTrue(bravo.hidden);
        assertTrue(bravo.running);
        // A hidden game that runs is still what there is to resume.
        assertEquals(2, snapshot.running.getAppId());
    }

    @Test
    public void quittingSuspendsUpdatesUntilThePollAfter() {
        ComputerDetails live = gateway.addHost("uuid-a", "Alpha");
        live.runningGameId = 5;
        repo.start();
        repo.select("uuid-a");
        HostLibrary library = repo.currentLibrary();
        assertEquals(5, repo.getLibrary().getValue().running.getAppId());

        library.setSuspendUpdates(true);
        live.runningGameId = 0;
        gateway.emit(live);
        assertEquals(5, repo.getLibrary().getValue().running.getAppId());

        library.setSuspendUpdates(false);
        library.pollNow();
        assertEquals(1, gateway.pollers.get(0).polls);
        gateway.emit(live);
        assertNull(repo.getLibrary().getValue().running);
    }

    @Test
    public void aPendingPairingMatchesTheHostThatAnswersThere() {
        repo.start();
        repo.setPendingPairing(new ComputerDetails.AddressTuple("10.0.0.5", 47989), "1234", "secret");

        ComputerDetails elsewhere = gateway.addHost("uuid-x", "Elsewhere");
        elsewhere.activeAddress = new ComputerDetails.AddressTuple("10.0.0.9", 47989);
        gateway.emit(elsewhere);
        assertNull(repo.getPairingRequests().getValue());

        ComputerDetails target = gateway.addHost("uuid-p", "Target");
        target.pairState = PairState.NOT_PAIRED;
        target.activeAddress = new ComputerDetails.AddressTuple("10.0.0.5", 47989);
        gateway.emit(target);

        PairingRequest request = repo.getPairingRequests().getValue();
        assertNotNull(request);
        assertEquals("uuid-p", request.host.uuid);
        assertNotSame(target, request.host);
        assertEquals("1234", request.pin);
        assertEquals("secret", request.passphrase);

        // Taken care of, and not offered again by the next poll.
        repo.pairingHandled();
        gateway.emit(target);
        assertNull(repo.getPairingRequests().getValue());
    }

    @Test
    public void aHostShortcutIsPublishedOncePerName() {
        ComputerDetails live = gateway.addHost("uuid-a", "Alpha");
        repo.start();
        gateway.emit(live);
        gateway.emit(live);
        assertEquals(1, shortcutsPublished.size());

        live.name = "Alpha 2";
        gateway.emit(live);
        assertEquals(2, shortcutsPublished.size());

        // Unpaired, then paired again: the shortcut the unpairing disabled comes back.
        live.pairState = PairState.NOT_PAIRED;
        gateway.emit(live);
        live.pairState = PairState.PAIRED;
        gateway.emit(live);
        assertEquals(3, shortcutsPublished.size());
    }

    @Test
    public void forgettingTheSelectedHostClearsTheSelection() {
        gateway.addHost("uuid-a", "Alpha");
        repo.start();
        repo.select("uuid-a");
        HostLibrary library = repo.currentLibrary();

        repo.forgetHost("uuid-a");

        assertTrue(repo.getHosts().getValue().isEmpty());
        assertNull(repo.getSelectedHostUuid().getValue());
        assertNull(repo.getLibrary().getValue());
        assertTrue(library.isClosed());
    }

    @Test
    public void aPollQueuedBeforeARemovalDoesNotBringTheHostBack() {
        List<Runnable> background = new ArrayList<>();
        repo = newRepository(DIRECT, background::add);
        ComputerDetails live = gateway.addHost("uuid-a", "Alpha");
        repo.start();
        drain(background);
        assertEquals(1, repo.getHosts().getValue().size());
        shortcutsPublished.clear();

        // A poll copied by the polling thread, still waiting on the background executor...
        live.name = "Alpha renamed";
        gateway.emit(live);
        // ...when the user removes the host.
        gateway.removeComputer(live);
        repo.forgetHost("uuid-a");
        drain(background);

        assertTrue(repo.getHosts().getValue().isEmpty());
        assertNull(repo.getHost("uuid-a"));
        assertTrue(shortcutsPublished.isEmpty());
    }

    @Test
    public void anUpdatePostedBeforeARemovalDoesNotBringTheHostBack() {
        List<Runnable> main = new ArrayList<>();
        repo = newRepository(main::add, DIRECT);
        ComputerDetails live = gateway.addHost("uuid-a", "Alpha");
        repo.start();
        drain(main);
        assertEquals(1, repo.getHosts().getValue().size());

        // This time the poll got as far as being posted to main.
        gateway.emit(live);
        gateway.removeComputer(live);
        repo.forgetHost("uuid-a");
        drain(main);

        assertTrue(repo.getHosts().getValue().isEmpty());
        assertNull(repo.getHost("uuid-a"));
    }

    @Test
    public void aForgottenHostAddedBackIsListedAgain() {
        ComputerDetails live = gateway.addHost("uuid-a", "Alpha");
        repo.start();
        gateway.removeComputer(live);
        repo.forgetHost("uuid-a");
        assertTrue(repo.getHosts().getValue().isEmpty());
        shortcutsPublished.clear();

        gateway.emit(gateway.addHost("uuid-a", "Alpha"));

        assertNotNull(repo.getHost("uuid-a"));
        assertEquals(1, shortcutsPublished.size());
    }

    @Test
    public void closeReleasesEverything() {
        gateway.addHost("uuid-a", "Alpha");
        repo.start();
        repo.select("uuid-a");
        HostLibrary library = repo.currentLibrary();

        repo.close();

        assertTrue(gateway.listeners.isEmpty());
        assertTrue(library.isClosed());
        assertTrue(disconnected);
        assertNull(repo.gateway());
    }

    @Test
    public void aRecordedLaunchRepublishesTheLibrary() {
        ComputerDetails live = gateway.addHost("uuid-a", "Alpha");
        repo.start();
        repo.select("uuid-a");
        live.rawAppList = "<root status_code=\"200\">"
                + "<App><AppTitle>Alpha</AppTitle><ID>1</ID><IDX>0</IDX></App>"
                + "</root>";
        gateway.emit(live);
        HostLibrary.Snapshot before = repo.getLibrary().getValue();

        // Recorded the way the launch path does it, knowing nothing of the library.
        AppLaunchHistory.recordLaunch(context, live, new NvApp("Alpha", null, 1, false));
        shadowOf(Looper.getMainLooper()).idle();

        HostLibrary.Snapshot after = repo.getLibrary().getValue();
        assertNotSame(before, after);
        assertTrue(after.launchHistoryVersion > before.launchHistoryVersion);

        // Another host's launch is not this library's news.
        ComputerDetails other = new ComputerDetails();
        other.uuid = "uuid-b";
        AppLaunchHistory.recordLaunch(context, other, new NvApp("Beta", null, 1, false));
        shadowOf(Looper.getMainLooper()).idle();
        assertSame(after, repo.getLibrary().getValue());
    }

    @Test
    public void theAppListPollerIsMadeOffTheMainThread() {
        gateway.addHost("uuid-a", "Alpha");
        List<Runnable> libraryWork = new ArrayList<>();
        HostLibrary library = new HostLibrary(context, "uuid-a", gateway, "id", libraryWork::add,
                DIRECT, (source, snapshot) -> { });
        library.open();

        library.setActive(true);
        // The lookup takes the service's pollingTuples lock: queued, not done on the caller.
        assertTrue(gateway.pollers.isEmpty());

        drain(libraryWork);
        assertEquals(1, gateway.pollers.size());
        assertTrue(gateway.pollers.get(0).running);

        // Made once; later polls and visits reuse it.
        library.setActive(false);
        library.setActive(true);
        drain(libraryWork);
        assertEquals(1, gateway.pollers.size());
        library.close();
    }

    @Test
    public void aFailedProbeBacksOffAndCaps() {
        assertEquals(15_000, HostLibrary.probeRetryDelayMs(1));
        assertEquals(30_000, HostLibrary.probeRetryDelayMs(2));
        assertEquals(60_000, HostLibrary.probeRetryDelayMs(3));
        assertEquals(5 * 60_000, HostLibrary.probeRetryDelayMs(10));
        assertEquals(5 * 60_000, HostLibrary.probeRetryDelayMs(Integer.MAX_VALUE));
    }
}
