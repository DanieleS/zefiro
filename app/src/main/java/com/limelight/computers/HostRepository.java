package com.limelight.computers;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.os.Handler;
import android.os.Looper;

import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;
import androidx.preference.PreferenceManager;

import com.limelight.grid.assets.MemoryAssetLoader;
import com.limelight.library.LibraryIntents;
import com.limelight.library.LibraryModel;
import com.limelight.nvstream.http.ComputerDetails;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;

/**
 * The one place the new UI learns about hosts: every known PC, which one is selected, and the
 * selected one's library.
 *
 * <p>Before this, each screen bound the computer manager on its own, kept its own list of hosts
 * and, worse, kept the service's live {@link ComputerDetails}, which polling threads write to
 * while the UI reads them. Library, PCs, the host sheet and the companion panel all need the
 * same hosts at the same time, so they read them from here instead.
 *
 * <h3>Invariants</h3>
 *
 * <ul>
 *   <li><b>Live objects never leave the callback.</b> The polling listener copies the service's
 *   details on the spot and posts the copy. Every {@link HostEntry} published here is built from
 *   such a copy and is never mutated afterwards; a change is a new entry.</li>
 *   <li><b>One listener, while started.</b> The repository registers a single polling listener
 *   between {@link #start()} and {@link #stop()} (the owning activity's start and stop), and
 *   removes only that one. Other screens' listeners are untouched.</li>
 *   <li><b>One library, for the selected host.</b> A host switch closes the old
 *   {@link HostLibrary} and makes a new one; nothing of one host's library survives into the
 *   next.</li>
 *   <li><b>The gateway is handed out last.</b> {@link #gateway()} stays null until the
 *   repository's own state (unique id, library) exists, which keeps closed the race
 *   the old library guarded by hand, where a poll could reach a screen half set up.</li>
 * </ul>
 *
 * <h3>Data flow</h3>
 *
 * <pre>
 *   CMS polling thread ──copy──▶ background executor ──────────▶ main thread
 *     (live details)              name the running game,          new HostEntry ─▶ hosts
 *                                 count the games,                 pending pairing ─▶ pairingRequests
 *                                 publish the host shortcut
 *                                                                  selected host ─▶ HostLibrary ─▶ library
 *                                                                        │
 *                                 art executor ◀── hero image ───────────┘
 *                                 (fetch, decode) ──────────────▶ main: same entry, with its hero
 * </pre>
 *
 * <h3>Threading</h3>
 *
 * The public API is main-thread only, except {@link #suspendPollingBlocking()} and
 * {@link #resumePolling()}, which pairing calls from its own thread. Background work runs on the
 * repository's single-thread executor, which keeps one host's updates in order.
 *
 * <p>The running game's hero image is the exception: it is fetched on a second executor of its
 * own. That fetch talks to the host with the long timeouts (up to about 12 s against a host that
 * half answers), and on the ordered executor it would hold back every host's updates, and the
 * selected library's, behind it. Nothing waits on the image, so nothing needs it in order.
 */
public class HostRepository {

    private static final Comparator<HostEntry> BY_NAME = (a, b) -> {
        String an = a.details.name == null ? "" : a.details.name;
        String bn = b.details.name == null ? "" : b.details.name;
        int byName = an.compareToIgnoreCase(bn);
        // Two hosts may share a name; the UUID keeps their order stable between polls.
        return byName != 0 ? byName : String.valueOf(a.details.uuid).compareTo(String.valueOf(b.details.uuid));
    };

    /** An address a deep link asked to pair with, waiting for a host to answer there. */
    private static final class PendingPairing {
        final ComputerDetails.AddressTuple address;
        final String pin;
        final String passphrase;

        PendingPairing(ComputerDetails.AddressTuple address, String pin, String passphrase) {
            this.address = address;
            this.pin = pin;
            this.passphrase = passphrase;
        }
    }

    private final CmsGateway.Connector connector;
    private final Executor main;
    private final Executor background;
    private final ExecutorService ownedBackground;
    private final Executor art;
    private final ExecutorService ownedArt;
    private final HostLibrary.Factory libraries;
    private final RunningGameResolver runningGames;
    private final ShortcutPublisher shortcuts;
    private final SharedPreferences prefs;
    private final SharedPreferences hiddenPrefs;
    private final File cacheDir;
    private final GameCount gameCounts = new GameCount();

    private final MutableLiveData<List<HostEntry>> hosts =
            new MutableLiveData<>(Collections.<HostEntry>emptyList());
    private final MutableLiveData<String> selectedHostUuid;
    private final MutableLiveData<HostLibrary.Snapshot> library;
    private final MutableLiveData<PairingRequest> pairingRequests = new MutableLiveData<>();

    // Main thread only.
    private final Map<String, HostEntry> entries = new HashMap<>();
    private HostLibrary currentLibrary;
    private String uniqueId;
    private boolean connecting;
    private PendingPairing pendingPairing;

    // Hosts the user removed, until the service polls them again. Written on main, read on the
    // background executor too, hence concurrent; see forgetHost for why it exists.
    private final Set<String> forgotten = Collections.newSetFromMap(new ConcurrentHashMap<>());

    // Whether our listener is registered depends on four things, two of which change off the main
    // thread (pairing suspends and resumes from its own). registrationLock serialises the
    // decision; the flags are volatile so the polling callback can read them without it.
    private final Object registrationLock = new Object();
    private volatile CmsGateway gateway;
    private volatile boolean started;
    private volatile boolean pollingSuspended;
    private volatile boolean closed;
    private boolean listenerRegistered; // guarded by registrationLock

    private final ComputerManagerListener pollingListener = new ComputerManagerListener() {
        @Override
        public void notifyComputerUpdated(ComputerDetails live) {
            // A polling thread, the network callback, or the initial report under the service's
            // pollingTuples lock: copy and post, nothing more.
            if (closed || pollingSuspended || live.uuid == null) {
                return;
            }
            final ComputerDetails copy = new ComputerDetails(live);
            runInBackground(() -> onPolled(copy));
        }
    };

    /** The production repository: binds the real service on the application context. */
    public HostRepository(Context context) {
        this(context, BinderCmsGateway.connector(context), mainThreadExecutor(), null, null,
                HostLibrary.factory(context, mainThreadExecutor()),
                new RunningGameResolver(context), new ShortcutPublisher(context));
    }

    /**
     * @param background where blocking work runs, in order; null for a single thread of the
     *                   repository's own, shut down by {@link #close()}. Tests pass a direct executor.
     * @param art        where the running game's hero image is fetched, apart from the polls; null
     *                   for a second thread of the repository's own, shut down the same way.
     */
    public HostRepository(Context context, CmsGateway.Connector connector, Executor main,
                          Executor background, Executor art, HostLibrary.Factory libraries,
                          RunningGameResolver runningGames, ShortcutPublisher shortcuts) {
        Context app = context.getApplicationContext();
        this.connector = connector;
        this.main = main;
        if (background == null) {
            this.ownedBackground = Executors.newSingleThreadExecutor();
            this.background = ownedBackground;
        } else {
            this.ownedBackground = null;
            this.background = background;
        }
        if (art == null) {
            this.ownedArt = Executors.newSingleThreadExecutor();
            this.art = ownedArt;
        } else {
            this.ownedArt = null;
            this.art = art;
        }
        this.libraries = libraries;
        this.runningGames = runningGames;
        this.shortcuts = shortcuts;
        this.prefs = PreferenceManager.getDefaultSharedPreferences(app);
        this.hiddenPrefs = app.getSharedPreferences(LibraryIntents.PREFS_HIDDEN_APPS, Context.MODE_PRIVATE);
        this.cacheDir = app.getCacheDir();
        // The last host is the default host: the library opens on it before anything is polled.
        String lastHost = prefs.getString(LibraryIntents.PREF_LAST_HOST, null);
        this.selectedHostUuid = new MutableLiveData<>(lastHost);
        this.library = new MutableLiveData<>(lastHost == null ? null : HostLibrary.Snapshot.loading(lastHost));
    }

    /** Posts to the main looper; what production passes as the main executor. */
    public static Executor mainThreadExecutor() {
        final Handler handler = new Handler(Looper.getMainLooper());
        return handler::post;
    }

    // --- Observable state ---

    /** Every host the service knows, sorted by name. Each list and entry is immutable. */
    public LiveData<List<HostEntry>> getHosts() {
        return hosts;
    }

    /** The selected host's UUID, or null; persisted as {@code last_computer_uuid}. */
    public LiveData<String> getSelectedHostUuid() {
        return selectedHostUuid;
    }

    /** The selected host's library, or null when no host is selected. */
    public LiveData<HostLibrary.Snapshot> getLibrary() {
        return library;
    }

    /**
     * A pairing a deep link asked for, ready to run; null when there is none. The observer runs
     * it and calls {@link #pairingHandled()}, so it is not offered again on the next observe.
     */
    public LiveData<PairingRequest> getPairingRequests() {
        return pairingRequests;
    }

    /** The selected host's library itself, for the actions on it; null when none is open. */
    public HostLibrary currentLibrary() {
        return currentLibrary;
    }

    /** The latest entry for a host, or null. */
    public HostEntry getHost(String uuid) {
        return uuid == null ? null : entries.get(uuid);
    }

    /** The service, once the repository is fully set up; null before that. */
    public CmsGateway gateway() {
        return gateway;
    }

    /** This client's id as hosts know it from pairing; null until the service is ready. */
    public String uniqueId() {
        return uniqueId;
    }

    // --- Lifecycle ---

    /** Starts receiving polls (binding first if needed). Called from the owner's onStart. */
    public void start() {
        if (closed) {
            return;
        }
        started = true;
        connect();
        updateRegistration();
        if (currentLibrary != null) {
            currentLibrary.setActive(true);
        }
    }

    /** Stops receiving polls; the binding stays. Called from the owner's onStop. */
    public void stop() {
        started = false;
        updateRegistration();
        if (currentLibrary != null) {
            currentLibrary.setActive(false);
        }
    }

    /** Stops, closes the library and unbinds. The repository is unusable afterwards. */
    public void close() {
        if (closed) {
            return;
        }
        stop();
        closed = true;
        closeLibrary();
        connector.disconnect();
        synchronized (registrationLock) {
            gateway = null;
            listenerRegistered = false;
        }
        if (ownedBackground != null) {
            ownedBackground.shutdownNow();
        }
        if (ownedArt != null) {
            ownedArt.shutdownNow();
        }
    }

    // --- Selection ---

    /** Makes this host the selected one and opens its library; null selects none. */
    public void select(String uuid) {
        if (closed) {
            return;
        }
        boolean same = Objects.equals(uuid, selectedHostUuid.getValue());
        if (same && (uuid == null || currentLibrary != null || gateway == null)) {
            return;
        }
        if (!same) {
            selectedHostUuid.setValue(uuid);
            if (uuid == null) {
                prefs.edit().remove(LibraryIntents.PREF_LAST_HOST).apply();
            } else {
                prefs.edit().putString(LibraryIntents.PREF_LAST_HOST, uuid).apply();
            }
        }
        openLibrary(gateway);
    }

    /**
     * Drops a host the user removed. The service no longer polls it, so it would otherwise stay
     * in the list with its last state forever. Call it once the service's {@code removeComputer}
     * has returned, so that the service already denies knowing the host.
     *
     * <p>Dropping the entry is not enough on its own: a poll copied just before the removal may
     * still be queued on the background executor, or posted to main. Run as usual, it would
     * republish the shortcut the removal disabled and put the entry back, a ghost the service
     * never polls again. So the host is also remembered as forgotten, and its updates are
     * discarded until the service reports it again, which only happens if it is added back.
     */
    public void forgetHost(String uuid) {
        if (uuid == null) {
            return;
        }
        forgotten.add(uuid);
        // The decoded covers are process-wide and outlive every screen, so whoever removes the
        // host has to drop them; here, every removal path does. Even for a host this repository
        // never listed: its library may have been drawn from the cache before the first poll.
        new MemoryAssetLoader().clearHost(uuid);
        gameCounts.forget(uuid);
        HostEntry removed = entries.remove(uuid);
        if (removed == null) {
            return;
        }
        shortcuts.forget(uuid);
        if (removed.details.runningGameId != 0) {
            runningGames.forgetBackground(RunningGameResolver.keyOf(removed.details));
        }
        publishHosts();
        if (uuid.equals(selectedHostUuid.getValue())) {
            select(null);
        }
    }

    // --- Pairing ---

    /**
     * Parks a pairing a deep link asked for, until a host answers at that address; it then comes
     * out of {@link #getPairingRequests()}. A later call replaces it.
     */
    public void setPendingPairing(ComputerDetails.AddressTuple address, String pin, String passphrase) {
        pendingPairing = address == null ? null : new PendingPairing(address, pin, passphrase);
    }

    /** The current pairing request was taken care of. */
    public void pairingHandled() {
        pairingRequests.setValue(null);
    }

    /**
     * Stops listening to polls and waits until none is in flight, so pairing talks to the host
     * alone, as {@code PcView} did around {@code doPair}. Blocks: call it on the pairing thread,
     * never on main. Pair with {@link #resumePolling()}.
     *
     * <p>Polling stops entirely only if no other screen listens at the time; with the new UI
     * that is the normal case, since MainActivity is the only screen up while it pairs.
     */
    public void suspendPollingBlocking() {
        pollingSuspended = true;
        updateRegistration();
        CmsGateway g = gateway;
        if (g != null) {
            g.waitForPollingStopped();
        }
    }

    /** Listens to polls again after {@link #suspendPollingBlocking()}; any thread. */
    public void resumePolling() {
        pollingSuspended = false;
        updateRegistration();
    }

    // --- Internals ---

    private void connect() {
        if (connecting) {
            return;
        }
        connecting = true;
        connector.connect(new CmsGateway.Connector.Callback() {
            @Override
            public void onConnected(final CmsGateway connected) {
                if (closed) {
                    return;
                }
                // waitForReady blocks until discovery is bound, so it is never done on main.
                runInBackground(() -> {
                    connected.waitForReady();
                    final String id = connected.getUniqueId();
                    main.execute(() -> onGatewayReady(connected, id));
                });
            }

            @Override
            public void onDisconnected() {
                // The service died with our listener in it. The binding stays, and the system
                // calls onConnected again when the service is back.
                synchronized (registrationLock) {
                    gateway = null;
                    listenerRegistered = false;
                }
                closeLibrary();
                String selected = selectedHostUuid.getValue();
                library.setValue(selected == null ? null : HostLibrary.Snapshot.loading(selected));
            }
        });
    }

    private void onGatewayReady(CmsGateway ready, String id) {
        if (closed) {
            return;
        }
        uniqueId = id;
        // Our own state first, then the gateway goes public and the polls start flowing.
        openLibrary(ready);
        synchronized (registrationLock) {
            gateway = ready;
        }
        updateRegistration();
    }

    private void updateRegistration() {
        synchronized (registrationLock) {
            CmsGateway g = gateway;
            boolean want = g != null && started && !pollingSuspended && !closed;
            if (want == listenerRegistered) {
                return;
            }
            // Flip first: adding reports synchronously, and that report may come back round here.
            listenerRegistered = want;
            if (want) {
                g.addPollingListener(pollingListener);
            } else if (g != null) {
                g.removePollingListener(pollingListener);
            }
        }
    }

    // On the background executor: the slow part of an update, kept off both the polling thread
    // (which holds the service's lock) and main.
    private void onPolled(final ComputerDetails copy) {
        // Asking the service is a short lock on its host list, fine here and not on main.
        CmsGateway g = gateway;
        boolean known = g != null && g.getComputer(copy.uuid) != null;
        if (forgotten.contains(copy.uuid)) {
            if (!known) {
                // Copied before the user removed it; see forgetHost.
                return;
            }
            // The service polls it again, so it was added back.
            forgotten.remove(copy.uuid);
        }

        final String runningName = runningGames.resolveRunningAppName(copy);
        final int gameCount = countGames(copy);
        // Only for a host the service still has. This also covers a removal whose forgetHost is
        // still on its way to main, and costs nothing else: a brand-new host's very first
        // report comes before the service lists it, and the next poll publishes it instead.
        if (known) {
            shortcuts.onHostUpdated(copy);
        }
        main.execute(() -> onHostUpdated(copy, runningName, gameCount));
    }

    /**
     * On the background executor: the games the Library would list for this host, from the list
     * on the details (the selected host's, filled in by its app list poller) or else the copy the
     * last library visit cached; {@link HostEntry#UNKNOWN_GAME_COUNT} for a host with neither.
     *
     * <p>The hidden ids are read on every poll, but a hide or unhide only shows on the poll after
     * it, so the count may lag a hide by one poll. {@link GameCount} keeps an unchanged list from
     * being parsed again.
     */
    private int countGames(ComputerDetails copy) {
        Set<Integer> hidden = LibraryModel.parseHiddenIds(hiddenPrefs.getStringSet(copy.uuid, null));
        return gameCounts.countFor(copy.uuid, RunningGameResolver.readAppList(cacheDir, copy), hidden);
    }

    private void onHostUpdated(ComputerDetails copy, String runningName, int gameCount) {
        if (closed || forgotten.contains(copy.uuid)) {
            return;
        }

        // A hero image belongs to the game that was running, not to the host.
        HostEntry previous = entries.get(copy.uuid);
        if (previous != null && previous.details.runningGameId != 0
                && previous.details.runningGameId != copy.runningGameId) {
            // The host left that game. The resolver remembers asking, not the image, so let it
            // ask again should the host go back to the game; otherwise "Continue" would stay
            // without its hero for as long as this repository lives, which is days on a launcher.
            runningGames.forgetBackground(RunningGameResolver.keyOf(previous.details));
        }
        Bitmap background = previous != null && copy.runningGameId != 0
                && previous.details.runningGameId == copy.runningGameId
                ? previous.runningBackground : null;
        HostEntry entry = new HostEntry(copy, runningName, background, gameCount);
        entries.put(copy.uuid, entry);
        publishHosts();

        fetchRunningBackground(entry);
        matchPendingPairing(copy);

        if (currentLibrary != null && copy.uuid.equalsIgnoreCase(currentLibrary.getHostUuid())) {
            currentLibrary.onHostUpdated(entry);
        }
    }

    private void fetchRunningBackground(final HostEntry entry) {
        if (entry.runningBackground != null || uniqueId == null) {
            return;
        }
        final String uuid = entry.details.uuid;
        final String key = RunningGameResolver.keyOf(entry.details);
        // Never on the ordered executor: a slow host would stall every other host's polls behind
        // this one fetch.
        runningGames.fetchRunningBackground(entry.details, uniqueId, this::runArtFetch,
                bitmap -> main.execute(() -> {
                    HostEntry current = entries.get(uuid);
                    // The host may have moved on to another game while we were fetching.
                    if (closed || current == null || current.runningBackground != null
                            || !key.equals(RunningGameResolver.keyOf(current.details))) {
                        return;
                    }
                    entries.put(uuid, new HostEntry(current.details, current.runningAppName, bitmap,
                            current.gameCount));
                    publishHosts();
                }));
    }

    private void matchPendingPairing(ComputerDetails copy) {
        PendingPairing pending = pendingPairing;
        if (pending == null || copy.state != ComputerDetails.State.ONLINE
                || !pending.address.equals(copy.activeAddress)) {
            return;
        }
        pendingPairing = null;
        pairingRequests.setValue(new PairingRequest(copy, pending.pin, pending.passphrase));
    }

    private void publishHosts() {
        List<HostEntry> list = new ArrayList<>(entries.values());
        Collections.sort(list, BY_NAME);
        hosts.setValue(Collections.unmodifiableList(list));
    }

    // Replaces the library with one for the selected host. Without a gateway yet, it publishes
    // a loading snapshot; onGatewayReady comes back here once the service is ready.
    private void openLibrary(CmsGateway g) {
        closeLibrary();

        String uuid = selectedHostUuid.getValue();
        if (uuid == null) {
            library.setValue(null);
            return;
        }
        if (g == null) {
            library.setValue(HostLibrary.Snapshot.loading(uuid));
            return;
        }

        HostLibrary opened = libraries.create(uuid, g, uniqueId, (source, snapshot) -> {
            // A closed library's late result must not overwrite the current host's.
            if (source == currentLibrary) {
                library.setValue(snapshot);
            }
        });
        currentLibrary = opened;
        opened.open();

        HostEntry known = findEntry(uuid);
        if (known != null) {
            opened.onHostUpdated(known);
        }
        opened.setActive(started);
    }

    private void closeLibrary() {
        if (currentLibrary != null) {
            HostLibrary old = currentLibrary;
            currentLibrary = null;
            old.close();
        }
    }

    private HostEntry findEntry(String uuid) {
        HostEntry exact = entries.get(uuid);
        if (exact != null) {
            return exact;
        }
        for (HostEntry entry : entries.values()) {
            if (uuid.equalsIgnoreCase(entry.details.uuid)) {
                return entry;
            }
        }
        return null;
    }

    private void runInBackground(Runnable task) {
        execute(background, task);
    }

    private void runArtFetch(Runnable task) {
        execute(art, task);
    }

    private void execute(Executor executor, Runnable task) {
        if (closed) {
            return;
        }
        try {
            executor.execute(task);
        } catch (RejectedExecutionException e) {
            // Closed between the check and the call; the work is no longer wanted.
        }
    }
}
