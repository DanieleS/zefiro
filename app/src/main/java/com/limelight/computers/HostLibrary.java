package com.limelight.computers;

import android.content.ComponentCallbacks2;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.SystemClock;
import android.util.LruCache;

import androidx.annotation.Nullable;

import com.limelight.LimeLog;
import com.limelight.R;
import com.limelight.grid.AppSortOrder;
import com.limelight.grid.assets.CachedAppAssetLoader;
import com.limelight.grid.assets.DiskAssetLoader;
import com.limelight.grid.assets.MemoryAssetLoader;
import com.limelight.grid.assets.NetworkAssetLoader;
import com.limelight.grid.assets.NoArtDetector;
import com.limelight.library.LibraryApp;
import com.limelight.library.LibraryIntents;
import com.limelight.library.LibraryModel;
import com.limelight.nvstream.http.AppMetadata;
import com.limelight.nvstream.http.AppStats;
import com.limelight.nvstream.http.ComputerDetails;
import com.limelight.nvstream.http.NvApp;
import com.limelight.nvstream.http.NvHTTP;
import com.limelight.nvstream.http.PairingManager.PairState;
import com.limelight.stats.HostLink;
import com.limelight.stats.HostSession;
import com.limelight.utils.AppLaunchHistory;
import com.limelight.utils.CacheHelper;
import com.limelight.utils.CocoonSync;
import com.limelight.utils.ShortcutHelper;

import org.xmlpull.v1.XmlPullParserException;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.StringReader;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Everything the library knows about one host: its games, which one is running, what the user
 * hid, the order they browse in, the host's Playnite metadata and whether it keeps statistics.
 *
 * <p>This is the old library's per-host state, taken out of the Activity. There it lived and died
 * with the screen, and a host going offline or unpaired could only {@code finish()} it. Here it
 * lives as long as the host is selected; offline and unpaired are {@link State states} a screen
 * shows, and nothing finishes.
 *
 * <h3>Lifetime</h3>
 *
 * One instance per selected host. {@link HostRepository} makes one when a host is selected and
 * {@link #close()}s it on a host switch, which releases everything: the app list poller, the
 * executor, the cover loader's workers and the cached backgrounds. Nothing carries over from one
 * host to the next, so no state can leak across hosts by construction. (The decoded covers live
 * in a process-wide cache keyed by host, which is shared rather than carried.)
 *
 * <pre>
 *            open()                  host answers, list parsed
 *   (new) ──────────▶ LOADING ─────────────────────────────────▶ READY
 *                        │  ▲                                     │ ▲
 *                        │  └─────────── host back ───────────────┘ │
 *                        ▼                                          ▼
 *                 OFFLINE / UNPAIRED ◀─── host offline / unpaired ──┘
 *   any state ──close()──▶ (closed: every late callback is dropped)
 * </pre>
 *
 * <h3>Threading</h3>
 *
 * Main thread only, except where a method says otherwise. Disk, network and parsing run on the
 * library's executor, and their results come back through the main executor, where they are
 * dropped if the library was closed in between. Snapshots are built and published on main.
 */
public class HostLibrary {

    public enum State {
        /** Nothing to show yet: no cached list and no answer from the host. */
        LOADING,
        /** The games are known; the host is reachable, or has not said otherwise yet. */
        READY,
        /** The host does not answer. The last known games are still in the snapshot. */
        OFFLINE,
        /** The host answers but does not know this device. */
        UNPAIRED
    }

    /**
     * What a screen draws, as one immutable value. A new one is published whenever any of it
     * changes, and only then: a poll that changes nothing publishes nothing.
     */
    public static final class Snapshot {
        public final String hostUuid;
        public final State state;
        /** Every game, hidden ones flagged, in {@link #sortOrder}. Unmodifiable. */
        public final List<LibraryApp> apps;
        /** What the host is running (possibly a hidden game, a special or a stand-in); or null. */
        public final NvApp running;
        public final LibraryModel.Specials specials;
        /** Playnite metadata keyed by upper-cased app UUID; empty on stock hosts. Unmodifiable. */
        public final Map<String, AppMetadata> metadata;
        /** Whether the host serves play statistics; nothing about them is offered until it does. */
        public final boolean statsAvailable;
        public final AppSortOrder sortOrder;
        /**
         * Fingerprints of the cached cover files that are the host's stand-in art rather than a
         * game's own, so a tile draws its title card instead; see {@link NoArtDetector}. It
         * grows as covers arrive, and a new set is a new snapshot, so a screen rebinds its tiles
         * and their covers are judged again. Unmodifiable.
         */
        public final Set<NoArtDetector.ArtFingerprint> noArtFingerprints;
        /**
         * Goes up when covers have landed in the disk cache, and when the host becomes reachable,
         * so a screen rebinds its tiles and they load again. A tile the host could not be asked
         * about (no address before the first poll, or offline) keeps its LOADING card, and
         * nothing else would tell it the cover is there now: a cover with a fingerprint of its
         * own leaves {@link #noArtFingerprints} as it was.
         */
        public final int artVersion;
        /**
         * Goes up each time this device records a launch on the host. The history itself is not
         * copied in (it is a preferences file, read through {@link #getLaunchHistory()}), so
         * without this a launch would leave "Recently played" stale whenever it did not also
         * change {@link #apps}' order, which under every order but RECENT it does not.
         */
        public final int launchHistoryVersion;

        Snapshot(String hostUuid, State state, List<LibraryApp> apps, NvApp running,
                 LibraryModel.Specials specials, Map<String, AppMetadata> metadata,
                 boolean statsAvailable, AppSortOrder sortOrder,
                 Set<NoArtDetector.ArtFingerprint> noArtFingerprints, int artVersion,
                 int launchHistoryVersion) {
            this.hostUuid = hostUuid;
            this.state = state;
            this.apps = apps;
            this.running = running;
            this.specials = specials;
            this.metadata = metadata;
            this.statsAvailable = statsAvailable;
            this.sortOrder = sortOrder;
            this.noArtFingerprints = noArtFingerprints;
            this.artVersion = artVersion;
            this.launchHistoryVersion = launchHistoryVersion;
        }

        /** The snapshot of a host whose library is not open yet. */
        public static Snapshot loading(String hostUuid) {
            return new Snapshot(hostUuid, State.LOADING, Collections.<LibraryApp>emptyList(), null,
                    LibraryModel.Specials.NONE, Collections.<String, AppMetadata>emptyMap(),
                    false, AppSortOrder.HOST,
                    Collections.<NoArtDetector.ArtFingerprint>emptySet(), 0, 0);
        }

        // Whether a screen would draw the two the same. Specials and metadata are compared by
        // identity: they are replaced, never edited, so a new instance is the only way they change.
        boolean sameAs(Snapshot other) {
            return other != null
                    && Objects.equals(hostUuid, other.hostUuid)
                    && state == other.state
                    && apps.equals(other.apps)
                    && sameRunning(running, other.running)
                    && specials == other.specials
                    && metadata == other.metadata
                    && statsAvailable == other.statsAvailable
                    && sortOrder == other.sortOrder
                    && noArtFingerprints.equals(other.noArtFingerprints)
                    && artVersion == other.artVersion
                    && launchHistoryVersion == other.launchHistoryVersion;
        }

        private static boolean sameRunning(NvApp a, NvApp b) {
            if (a == null || b == null) {
                return a == b;
            }
            return a.getAppId() == b.getAppId() && Objects.equals(a.getAppName(), b.getAppName());
        }
    }

    /** Receives every new snapshot, on the main thread. */
    public interface Listener {
        void onSnapshot(HostLibrary library, Snapshot snapshot);
    }

    /** How the repository makes a library; tests substitute their own executors. */
    public interface Factory {
        HostLibrary create(String hostUuid, CmsGateway gateway, String uniqueId, Listener listener);
    }

    /** Receives a game's background art, on the main thread, and only when there is one. */
    public interface BackgroundCallback {
        void onBackground(int appId, Bitmap background);
    }

    // A hero image is made for a TV; the companion panel is at most this wide.
    private static final int BACKGROUND_MAX_WIDTH = 1600;

    // An eighth of the heap, at most 32 MB: a handful of decoded heroes, which is what a user
    // browsing back and forth revisits. The old library kept every one it ever loaded, in a plain
    // map.
    private static final int BACKGROUND_CACHE_KB =
            (int) Math.min(32 * 1024, Runtime.getRuntime().maxMemory() / 1024 / 8);

    // A failed probe waits this long before the next poll may retry it, doubling per failure up
    // to the cap: a waking host gets asked again soon, a host that keeps failing is not hammered.
    private static final long PROBE_RETRY_MIN_MS = 15_000;
    private static final long PROBE_RETRY_MAX_MS = 5 * 60_000;

    // The cache file, beside "applist", holding the host's last /appmetadata answer with games
    // in it; see readCachedMetadata.
    private static final String METADATA_CACHE = "appmetadata";

    private final Context context;
    private final String hostUuid;
    private final CmsGateway gateway;
    private final String uniqueId;
    private final Executor executor;
    private final ExecutorService ownedExecutor;
    private final Executor main;
    private final Listener listener;
    private final File cacheDir;
    private final SharedPreferences hiddenPrefs;
    private final SharedPreferences sortPrefs;
    private final AppLaunchHistory launchHistory;
    private final ShortcutHelper shortcuts;
    private final LruCache<Integer, Bitmap> backgrounds;
    private final DiskAssetLoader diskArt;
    private final CachedAppAssetLoader art;

    private CmsGateway.AppListPoller poller;
    private boolean active;
    private boolean pollerRunning;

    /** The host as last polled; null until the first poll reaches us. */
    private HostEntry host;
    /** The list last parsed (from the cache or the host), so an unchanged one is skipped. */
    private String lastRawApplist;
    private List<NvApp> games = Collections.emptyList();
    private LibraryModel.Specials specials = LibraryModel.Specials.NONE;
    private boolean haveList;

    private final Set<Integer> hiddenIds;
    private AppSortOrder sortOrder;

    private Map<String, AppMetadata> metadata = Collections.emptyMap();
    private boolean statsAvailable;
    private AppStats firstStatsPage;
    private HostSession session;

    // The probe's progress. The old library probed once per visit, so a failure was retried on the
    // next one; this library lives as long as the host is selected, which on a launcher is days. So
    // a failed probe is retried on later polls, backing off, and each visit (setActive(true)) asks
    // again even after an answer, to pick up metadata the host gained meanwhile.
    private boolean probeInFlight;
    private boolean probeAnswered;
    private int probeFailures;
    private long probeRetryAtMs;

    private int launchHistoryVersion;
    // Kept here because preferences hold their listeners weakly.
    private SharedPreferences.OnSharedPreferenceChangeListener launchWatch;

    // Whether a poller is being made on the executor; see requestPoller.
    private boolean pollerRequested;

    // The list as last handed to the frontend sync, so an unchanged one is not handed over again.
    private String lastSyncedSignature;

    private boolean suspendUpdates;
    private boolean closed;
    private Snapshot snapshot;

    private Set<NoArtDetector.ArtFingerprint> noArtFingerprints = Collections.emptySet();
    // Whether a fingerprint pass is queued and not yet started. Covers arrive in bursts (a whole
    // grid's worth at once), and one pass after the burst sees them all; one per cover would
    // read every file again each time. Set from the loader's workers, so atomic.
    private final AtomicBoolean fingerprintQueued = new AtomicBoolean();
    // Whether a cover has been written to disk since the last fingerprint pass, which then
    // bumps artVersion; one bump per burst, as for the pass itself. Set from the loader's workers.
    private final AtomicBoolean coversLanded = new AtomicBoolean();
    private int artVersion;

    // Whether every game's cover is to be fetched ahead of the tiles, once the host can be asked.
    // True from the start, so the first answer of the session prefetches even when its list is
    // the one cached by the last visit (which never reaches applyList again); set again whenever
    // the host's list changes or the host comes back.
    private boolean prefetchPending = true;

    /**
     * @param executor where disk, network and parsing run; null for a single thread of the
     *                 library's own, shut down by {@link #close()}.
     * @param main     where results come back; the main thread in production.
     */
    public HostLibrary(Context context, String hostUuid, CmsGateway gateway, String uniqueId,
                       Executor executor, Executor main, Listener listener) {
        this.context = context.getApplicationContext();
        this.hostUuid = hostUuid;
        this.gateway = gateway;
        this.uniqueId = uniqueId;
        if (executor == null) {
            this.ownedExecutor = Executors.newSingleThreadExecutor();
            this.executor = ownedExecutor;
        } else {
            this.ownedExecutor = null;
            this.executor = executor;
        }
        this.main = main;
        this.listener = listener;
        this.cacheDir = this.context.getCacheDir();
        this.hiddenPrefs = this.context.getSharedPreferences(LibraryIntents.PREFS_HIDDEN_APPS, Context.MODE_PRIVATE);
        this.sortPrefs = this.context.getSharedPreferences(LibraryIntents.PREFS_SORT, Context.MODE_PRIVATE);
        this.launchHistory = new AppLaunchHistory(this.context, hostUuid);
        this.shortcuts = new ShortcutHelper(this.context);
        this.backgrounds = new LruCache<Integer, Bitmap>(BACKGROUND_CACHE_KB) {
            @Override
            protected int sizeOf(Integer key, Bitmap value) {
                return Math.max(1, value.getByteCount() / 1024);
            }
        };
        this.diskArt = new DiskAssetLoader(this.context);
        this.art = new CachedAppAssetLoader(new NetworkAssetLoader(this.context, uniqueId),
                new MemoryAssetLoader(), diskArt, main);
        this.art.setArtFetchListener(this::onCoverFetched);

        this.hiddenIds = LibraryModel.parseHiddenIds(hiddenPrefs.getStringSet(hostUuid, null));
        this.sortOrder = AppSortOrder.fromKey(sortPrefs.getString(LibraryIntents.KEY_SORT, null),
                AppSortOrder.HOST);
    }

    /** The production factory: each library gets its own background thread. */
    public static Factory factory(Context context, final Executor main) {
        // The factory lives as long as the repository, which outlives any screen that made it;
        // capturing the caller's context here would keep that screen alive with it.
        final Context app = context.getApplicationContext();
        return (hostUuid, gateway, uniqueId, listener) ->
                new HostLibrary(app, hostUuid, gateway, uniqueId, null, main, listener);
    }

    /**
     * Publishes the first (LOADING) snapshot, then shows the list cached by the last visit while
     * the host is asked for a fresh one. Kept out of the constructor so the listener never sees a
     * half-built library.
     */
    public void open() {
        publish();
        // Launches are recorded on the launch path, which does not know this library exists.
        launchWatch = launchHistory.watch(this::onLaunchRecorded);
        runInBackground(this::readCachedList);
        runInBackground(this::readCachedMetadata);
        // The covers cached by earlier visits tell the stand-in apart before any tile asks.
        queueFingerprint();
    }

    public String getHostUuid() {
        return hostUuid;
    }

    /** The last published snapshot; never null after {@link #open()}. */
    public Snapshot getSnapshot() {
        return snapshot;
    }

    public boolean isClosed() {
        return closed;
    }

    /** The first page of statistics the probe fetched (this week), or null when there is none. */
    public AppStats getFirstStatsPage() {
        return firstStatsPage;
    }

    /** This device's launch record for the host; what "Recently played" and RECENT read. */
    public AppLaunchHistory getLaunchHistory() {
        return launchHistory;
    }

    /**
     * Runs the app list poller while the library is on screen. The repository follows the
     * activity's start and stop, as the old library followed resume and pause.
     */
    public void setActive(boolean active) {
        boolean visitStarts = active && !this.active;
        this.active = active;
        updatePoller();
        if (visitStarts && !closed) {
            // Once per visit, as the old library did: the host may have gained metadata or
            // statistics since the last answer, and a failure backing off is worth a fresh try now
            // that the user is looking. The old metadata stays on screen until the new answer
            // replaces it.
            probeAnswered = false;
            probeFailures = 0;
            probeRetryAtMs = 0;
            maybeStartProbe();
        }
    }

    /**
     * Ignores the host while a quit is in flight, so the game still listed as running does not
     * flash back before the host has caught up. Ported from the old library's
     * {@code suspendGridUpdates}.
     */
    public void setSuspendUpdates(boolean suspend) {
        suspendUpdates = suspend;
    }

    /** Asks the host for its app list now, rather than at the end of the poller's wait. */
    public void pollNow() {
        if (poller != null) {
            poller.pollNow();
        }
    }

    /** Hides or shows a game, remembering it in the {@code HiddenApps} preference. */
    public void setHidden(int appId, boolean hidden) {
        if (closed) {
            return;
        }
        boolean changed = hidden ? hiddenIds.add(appId) : hiddenIds.remove(appId);
        if (!changed) {
            return;
        }
        hiddenPrefs.edit()
                .putStringSet(hostUuid, LibraryModel.formatHiddenIds(hiddenIds))
                .apply();
        publish();
    }

    public Set<Integer> getHiddenIds() {
        return Collections.unmodifiableSet(new HashSet<>(hiddenIds));
    }

    /**
     * Changes the order for every host, as the preference is global, and re-sorts. The order it
     * already had is still recorded as chosen ({@link #isSortOrderChosen}).
     */
    public void setSortOrder(AppSortOrder order) {
        if (closed || order == null) {
            return;
        }
        boolean changed = order != sortOrder;
        sortOrder = order;
        sortPrefs.edit().putString(LibraryIntents.KEY_SORT, order.getKey()).apply();
        if (changed) {
            publish();
        }
    }

    /**
     * Whether the user has ever picked an order; until then the library is in host order, and
     * See all opens on what was played last when there is a history (see
     * {@code LibraryShelves.seeAllDefault}).
     */
    public boolean isSortOrderChosen() {
        return sortPrefs.contains(LibraryIntents.KEY_SORT);
    }

    /**
     * The connection everything about this host shares (statistics, a game's page, the
     * achievement icons), made on first use. Null while the host has no address to reach.
     */
    public HostSession session() {
        if (session == null && host != null) {
            HostLink link = HostLink.of(host.details, uniqueId);
            if (link != null) {
                session = new HostSession(context, link);
            }
        }
        return session;
    }

    /**
     * The covers of this host's games. Owned here so it is closed with the library; a screen
     * uses it for what {@link #loadArt} does not cover, such as {@code getBitmap} for a shortcut.
     */
    public CachedAppAssetLoader getArtLoader() {
        return art;
    }

    /**
     * A game's cover on this host, as the loader identifies it. It carries the host as last
     * polled, address included, so a cover not on disk yet can be fetched; before the first
     * poll it carries the bare identity, which serves the disk cache only. A cover that needed
     * the host then stays LOADING until {@link Snapshot#artVersion} moves and the tile is bound
     * again.
     */
    public CachedAppAssetLoader.LoaderTuple artTuple(NvApp app) {
        return new CachedAppAssetLoader.LoaderTuple(hostIdentity(), app);
    }

    /**
     * Loads a game's cover for a view targetWidthPx wide; see {@link CachedAppAssetLoader#load}.
     * Main thread. On a closed library nothing calls back.
     */
    public CachedAppAssetLoader.Request loadArt(NvApp app, int targetWidthPx,
                                                CachedAppAssetLoader.ArtCallback callback) {
        return art.load(artTuple(app), targetWidthPx, callback);
    }

    /**
     * Gives this library's decoded backgrounds back as the system asks; see
     * {@code ComponentCallbacks2}. The covers are not trimmed here: their cache is shared by the
     * whole process, and ArtemisApplication trims it whatever screen is up. Nothing calls this
     * yet; the screen that owns the library (MainActivity, from S4) forwards its onTrimMemory.
     */
    public void trimMemory(int level) {
        if (level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW) {
            backgrounds.evictAll();
        }
    }

    /** A game's background art if it was loaded already, or null. */
    public Bitmap cachedBackground(int appId) {
        return backgrounds.get(appId);
    }

    /**
     * Loads a game's background art into the cache, then hands it over. Only games whose
     * metadata says they have one are worth asking for; a failure is silent, as the art is
     * decoration and the caller already shows something.
     */
    public void loadBackground(final int appId, final BackgroundCallback callback) {
        if (closed) {
            return;
        }
        Bitmap cached = backgrounds.get(appId);
        if (cached != null) {
            callback.onBackground(appId, cached);
            return;
        }
        final HostSession hostSession = session();
        if (hostSession == null) {
            return;
        }
        runInBackground(() -> {
            Bitmap bitmap = null;
            try {
                bitmap = decodeSampled(hostSession.http().getBackgroundArt(appId), BACKGROUND_MAX_WIDTH);
            } catch (Exception e) {
                LimeLog.warning("Failed to fetch background for app " + appId + ": " + e.getMessage());
            }
            if (bitmap == null) {
                return;
            }
            final Bitmap loaded = bitmap;
            main.execute(() -> {
                if (closed) {
                    return;
                }
                backgrounds.put(appId, loaded);
                callback.onBackground(appId, loaded);
            });
        });
    }

    /**
     * The host as just polled. The repository passes only this library's host, already copied
     * out of the service.
     */
    public void onHostUpdated(HostEntry entry) {
        if (closed || suspendUpdates) {
            return;
        }

        HostEntry previous = host;
        host = entry;
        ComputerDetails details = entry.details;

        if (canFetchArt(details) && (previous == null || !canFetchArt(previous.details))) {
            // Tiles bound while the host could not be asked kept their LOADING card and ended
            // their request; a rebind asks again, now that someone will answer. The prefetch
            // fills in the covers of tiles no screen is showing.
            artVersion++;
            prefetchPending = true;
        }

        // The poller could not exist before the service knew the host; it may now.
        updatePoller();

        if (isUnpaired(details) && (previous == null || !isUnpaired(previous.details))) {
            // Shortcuts to a host that no longer knows us would only fail; they come back when
            // the host is paired again and its shortcut is republished.
            final ComputerDetails unpaired = details;
            final String reason = context.getString(R.string.scut_not_paired);
            runInBackground(() -> shortcuts.disableComputerShortcut(unpaired, reason));
        }

        if (details.rawAppList != null && !details.rawAppList.equals(lastRawApplist)) {
            final String raw = details.rawAppList;
            lastRawApplist = raw;
            runInBackground(() -> {
                final List<NvApp> parsed = parse(raw);
                if (parsed != null) {
                    main.execute(() -> {
                        if (!closed) {
                            applyList(parsed, true);
                        }
                    });
                }
            });
        }

        maybePrefetchArt();
        maybeStartProbe();
        publish();
    }

    /**
     * Releases everything: the poller, the executor (queued work is dropped, running work's
     * results are ignored), the cover loader's workers and the cached backgrounds. The decoded
     * covers themselves stay in the shared memory cache, so switching back is instant.
     * Idempotent.
     */
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        active = false;
        launchHistory.unwatch(launchWatch);
        launchWatch = null;
        if (poller != null) {
            if (pollerRunning) {
                poller.stop();
            }
            poller = null;
            pollerRunning = false;
        }
        backgrounds.evictAll();
        art.close();
        if (ownedExecutor != null) {
            ownedExecutor.shutdownNow();
        }
    }

    // --- Internals ---

    private void updatePoller() {
        if (closed) {
            return;
        }
        if (poller == null) {
            if (active) {
                requestPoller();
            }
            return;
        }
        // ApplistPoller.start() makes a new thread each time, and a second start would orphan
        // the first one still polling; so start and stop strictly alternate.
        if (active && !pollerRunning) {
            poller.start();
            pollerRunning = true;
        } else if (!active && pollerRunning) {
            poller.stop();
            pollerRunning = false;
        }
    }

    // Making a poller looks the host up in the service, under its pollingTuples lock. Polls and
    // network-change reports hold that lock while every other screen's listener runs, so on main
    // it could stall the UI; the executor takes the wait instead. A null answer means the service
    // does not list the host yet, and the next poll (which calls updatePoller) asks again.
    private void requestPoller() {
        if (pollerRequested) {
            return;
        }
        pollerRequested = true;
        runInBackground(() -> {
            final CmsGateway.AppListPoller created = gateway.createAppListPoller(hostUuid);
            main.execute(() -> {
                pollerRequested = false;
                // A poller that was never started holds no thread, so a closed library can
                // simply drop it.
                if (closed || created == null) {
                    return;
                }
                poller = created;
                // Starts it, or leaves it idle if the screen left meanwhile.
                updatePoller();
            });
        });
    }

    // On the executor. The cached list shows the library at once, as the old library did, rather
    // than a spinner while the host is asked; the host's own answer replaces it moments later.
    private void readCachedList() {
        final String raw;
        try (InputStream in = CacheHelper.openCacheFileForInput(cacheDir, "applist", hostUuid)) {
            raw = CacheHelper.readInputStreamToString(in);
        } catch (IOException e) {
            // Never visited: the library stays LOADING until the host answers.
            return;
        }
        final List<NvApp> parsed = parse(raw);
        if (parsed == null) {
            LimeLog.warning("Saved applist corrupted: " + raw);
            return;
        }
        main.execute(() -> {
            if (closed) {
                return;
            }
            if (lastRawApplist == null) {
                lastRawApplist = raw;
                applyList(parsed, false);
            } else if (games.isEmpty() && specials.holdsMonitorSession()) {
                // The host answered first, but only with its session catalogue, which keeps the
                // games it finds (none yet): the cached games fill in under the held session.
                applyCachedUnderSession(parsed);
            }
            // Otherwise the host answered first with its library, the newer list.
        });
    }

    // On the executor. The metadata the host last served, so what ranks on it (when each game
    // was last played on the PC, above all) is there before the probe, and stays when the probe
    // gets nothing: the host answers 404 for its fork endpoints while a session is held. Only
    // fills a library that has none yet; the host's own answer is the newer one.
    private void readCachedMetadata() {
        final String raw;
        try (InputStream in = CacheHelper.openCacheFileForInput(cacheDir, METADATA_CACHE, hostUuid)) {
            raw = CacheHelper.readInputStreamToString(in);
        } catch (IOException e) {
            // Never answered: nothing to fill in.
            return;
        }
        final Map<String, AppMetadata> cached = AppMetadata.parseAll(raw);
        if (cached.isEmpty()) {
            return;
        }
        main.execute(() -> {
            if (closed || !metadata.isEmpty()) {
                return;
            }
            metadata = Collections.unmodifiableMap(cached);
            publish();
        });
    }

    // On the executor. Kept as the host sent it, read back by readCachedMetadata.
    private void writeCachedMetadata(String raw) {
        try (OutputStream out = CacheHelper.openCacheFileForOutput(cacheDir, METADATA_CACHE, hostUuid)) {
            CacheHelper.writeStringToOutputStream(out, raw);
        } catch (IOException e) {
            LimeLog.warning("Failed to cache app metadata: " + e.getMessage());
        }
    }

    /**
     * @param fromHost whether the list is the host's answer just now, as opposed to the copy
     *                 cached from the last visit. Only the host's answer is synced to the
     *                 frontend's folder: the cache can be months old, and syncing it would remove
     *                 every game added since, only for the host's list to write them back.
     */
    private void applyList(List<NvApp> hostList, boolean fromHost) {
        // The specials too: a session catalogue keeps them, as it keeps the games.
        LibraryModel.Reduction reduction = LibraryModel.reduce(games, specials, hostList);
        games = reduction.games;
        specials = reduction.specials;
        haveList = true;

        updateGameShortcuts(reduction);
        if (fromHost) {
            syncCocoonLibrary();
            prefetchPending = true;
        }
        maybePrefetchArt();
        maybeStartProbe();
        publish();
    }

    // Main thread. The cached list's games and specials, the held session kept over them. Not
    // synced to the frontend, as no cached list is (see applyList).
    private void applyCachedUnderSession(List<NvApp> cachedList) {
        LibraryModel.Reduction cached = LibraryModel.reduce(games, cachedList);
        LibraryModel.Specials held = specials;
        games = cached.games;
        specials = new LibraryModel.Specials(cached.specials.virtualDisplay,
                cached.specials.remoteMonitor, cached.specials.remoteInput,
                held.monitorResume, held.monitorDisconnect);
        haveList = true;
        updateGameShortcuts(cached);
        maybePrefetchArt();
        maybeStartProbe();
        publish();
    }

    // A game that left the host disables its pinned shortcut; one that came back (or a host
    // paired again) re-enables a leftover one. IPC, so off the main thread.
    private void updateGameShortcuts(LibraryModel.Reduction reduction) {
        if (reduction.added.isEmpty() && reduction.removed.isEmpty()) {
            return;
        }
        final ComputerDetails computer = hostIdentity();
        final List<NvApp> added = reduction.added;
        final List<NvApp> removed = reduction.removed;
        final String reason = context.getString(R.string.app_removed_from_pc);
        runInBackground(() -> {
            for (NvApp app : added) {
                shortcuts.enableAppShortcut(computer, app);
            }
            for (NvApp app : removed) {
                shortcuts.disableAppShortcut(computer, app, reason);
            }
        });
    }

    // The shortcut calls key on the host UUID alone, so before the first poll a bare identity
    // stands in for the details.
    private ComputerDetails hostIdentity() {
        if (host != null) {
            return host.details;
        }
        ComputerDetails identity = new ComputerDetails();
        identity.uuid = hostUuid;
        return identity;
    }

    // Every game's cover into the disk cache ahead of any tile, as the old library did on every
    // visit: the grid then fills from disk, and the stand-in detection has every cover to compare
    // rather than only the ones scrolled past. Hidden games too: the stand-in shared by one visible
    // and one hidden game is only found with both on disk, and hidden games are still drawn,
    // dimmed, when the user asks. Waits for a list and a host that can answer, so it runs on the
    // first answer of the session, after a change of list, and when the host comes back; covers
    // already on disk are skipped by the loader, so a repeat costs a file check each. The loader's
    // prefetch queue has no bound, unlike its tile queues, so a library of hundreds of games is
    // fetched whole rather than losing its first games to the newest.
    private void maybePrefetchArt() {
        if (!prefetchPending || !haveList || host == null || !canFetchArt(host.details)) {
            return;
        }
        prefetchPending = false;
        for (NvApp app : games) {
            art.queueCacheLoad(artTuple(app));
        }
    }

    /**
     * A cover has just been written to the disk cache. It may be one more copy of the host's
     * stand-in, and it is in any case a cover that tiles waiting on it can now load. Any thread:
     * the loader calls it from the worker that fetched the cover.
     */
    void onCoverFetched(CachedAppAssetLoader.LoaderTuple tuple) {
        if (hostUuid.equals(tuple.computer.uuid)) {
            coversLanded.set(true);
            queueFingerprint();
        }
    }

    // Any thread.
    private void queueFingerprint() {
        if (fingerprintQueued.compareAndSet(false, true)) {
            runInBackground(this::fingerprintArt);
        }
    }

    /**
     * On the executor. Fingerprints every cover cached for this host and publishes the ones
     * that games share, which are the host's stand-in art. Bytes rather than pixels, because the
     * disk cache stores each response as it came, so the stand-in is the same file for every
     * game it stands in for; see {@link NoArtDetector}. Covers that landed since the last pass
     * bump {@link Snapshot#artVersion} as well, so the tiles waiting on them load them.
     */
    private void fingerprintArt() {
        // Cleared first, so a cover that lands while this pass runs queues another one. The
        // landed flag is read before the listing: a cover it reports is on disk by then.
        fingerprintQueued.set(false);
        final boolean landed = coversLanded.getAndSet(false);
        Map<Integer, NoArtDetector.ArtFingerprint> byAppId = new HashMap<>();
        for (Map.Entry<Integer, File> entry : diskArt.listCachedFiles(hostUuid).entrySet()) {
            NoArtDetector.ArtFingerprint fp = NoArtDetector.fingerprint(entry.getValue());
            if (fp != null) {
                byAppId.put(entry.getKey(), fp);
            }
        }
        final Set<NoArtDetector.ArtFingerprint> found =
                NoArtDetector.placeholderFingerprints(byAppId);
        main.execute(() -> {
            if (closed) {
                return;
            }
            boolean standInsChanged = !found.equals(noArtFingerprints);
            if (!standInsChanged && !landed) {
                return;
            }
            if (standInsChanged) {
                noArtFingerprints = Collections.unmodifiableSet(found);
                // The loader judges covers it delivers from now on; the new snapshot makes
                // screens rebind, which re-judges the ones already showing.
                art.setPlaceholderFingerprints(noArtFingerprints);
            }
            if (landed) {
                artVersion++;
            }
            publish();
        });
    }

    // The poller says the same thing over and over, and the sync touches storage, so it only
    // runs when the host's answer actually changes. Names matter as much as ids: a renamed game
    // is a renamed file.
    private void syncCocoonLibrary() {
        if (host == null || !CocoonSync.isDesignatedHost(context, host.details)) {
            return;
        }

        StringBuilder signature = new StringBuilder();
        for (NvApp app : games) {
            signature.append(app.getAppId()).append(':').append(app.getAppName()).append('\n');
        }
        if (signature.toString().equals(lastSyncedSignature)) {
            return;
        }
        lastSyncedSignature = signature.toString();

        final ComputerDetails computer = host.details;
        final List<NvApp> apps = games;
        runInBackground(() -> CocoonSync.sync(context, computer, apps));
    }

    /**
     * Asks the host, as soon as it is reachable and the games are known, for its Playnite
     * metadata and whether it keeps statistics. Both are fork-only endpoints, so a stock host
     * quietly yields nothing: no metadata, and no statistics offered anywhere.
     *
     * <p>Once per visit when it answers. When the request fails outright (a host still waking,
     * a TLS hiccup, a timeout) a later poll retries it after {@link #probeRetryDelayMs}.
     *
     * <p>An answer with nothing in it never replaces one with something: the host answers 404
     * for both endpoints while it holds a session, and a library that dropped what it knew then
     * would rank "Recently played" and See all on this device's launches alone until the next
     * visit, a different order from one visit to the next. The last metadata with games in it is
     * kept on disk too ({@link #readCachedMetadata}), and the last week of statistics in memory.
     */
    private void maybeStartProbe() {
        if (probeInFlight || probeAnswered || !haveList || host == null
                || !isReachable(host.details)) {
            return;
        }
        if (probeFailures > 0 && SystemClock.elapsedRealtime() < probeRetryAtMs) {
            return;
        }
        final HostSession hostSession = session();
        if (hostSession == null) {
            return;
        }
        probeInFlight = true;

        runInBackground(() -> {
            final Map<String, AppMetadata> fetched;
            AppStats stats;
            try {
                NvHTTP http = hostSession.http();
                String raw = http.getAppMetadataRaw();
                fetched = AppMetadata.parseAll(raw);
                if (!fetched.isEmpty()) {
                    writeCachedMetadata(raw);
                }
                // Any failure here reads as "no statistics": the entries stay hidden.
                try {
                    stats = http.getAppStats(AppStats.RANGE_WEEK, 0);
                } catch (Exception e) {
                    stats = null;
                }
            } catch (Exception e) {
                LimeLog.warning("Failed to fetch app metadata: " + e.getMessage());
                main.execute(this::onProbeFailed);
                return;
            }
            final AppStats firstPage = stats;
            main.execute(() -> {
                probeInFlight = false;
                if (closed) {
                    return;
                }
                probeAnswered = true;
                probeFailures = 0;
                if (!fetched.isEmpty()) {
                    metadata = Collections.unmodifiableMap(fetched);
                }
                if (firstPage != null) {
                    firstStatsPage = firstPage;
                    statsAvailable = true;
                }
                // The orders that rank by metadata were sorting on nothing until now.
                publish();
            });
        });
    }

    // What was known stays (empty on a first failure, the last answer otherwise); the next poll
    // past the backoff tries again.
    private void onProbeFailed() {
        probeInFlight = false;
        if (closed) {
            return;
        }
        probeFailures++;
        probeRetryAtMs = SystemClock.elapsedRealtime() + probeRetryDelayMs(probeFailures);
    }

    static long probeRetryDelayMs(int failures) {
        // The shift is capped before it can overflow; the delay is capped well before that.
        int doublings = Math.min(Math.max(failures, 1) - 1, 16);
        return Math.min(PROBE_RETRY_MAX_MS, PROBE_RETRY_MIN_MS << doublings);
    }

    // Main thread: preferences notify their listeners there.
    private void onLaunchRecorded() {
        if (closed) {
            return;
        }
        launchHistoryVersion++;
        publish();
    }

    private void publish() {
        if (closed) {
            return;
        }
        Snapshot next = buildSnapshot();
        if (next.sameAs(snapshot)) {
            return;
        }
        snapshot = next;
        listener.onSnapshot(this, next);
    }

    private Snapshot buildSnapshot() {
        // An offline host still carries the game it was last seen running; it is not running it.
        int runningId = host != null && host.details.state != ComputerDetails.State.OFFLINE
                ? host.details.runningGameId : 0;
        Comparator<NvApp> order = sortOrder.comparator(metadata, launchHistory);
        List<LibraryApp> apps = LibraryModel.toLibraryApps(games, hiddenIds, runningId, order);
        NvApp running = LibraryModel.resolveRunning(games, specials, runningId,
                host != null ? host.runningAppName : null);
        return new Snapshot(hostUuid, computeState(), apps, running, specials, metadata,
                statsAvailable, sortOrder, noArtFingerprints, artVersion, launchHistoryVersion);
    }

    private State computeState() {
        if (host != null) {
            if (host.details.state == ComputerDetails.State.OFFLINE) {
                return State.OFFLINE;
            }
            if (isUnpaired(host.details)) {
                return State.UNPAIRED;
            }
        }
        return haveList ? State.READY : State.LOADING;
    }

    private static boolean isUnpaired(ComputerDetails details) {
        return details.state == ComputerDetails.State.ONLINE && details.pairState != PairState.PAIRED;
    }

    private static boolean isReachable(ComputerDetails details) {
        return details.state == ComputerDetails.State.ONLINE && details.pairState == PairState.PAIRED;
    }

    // Whether a cover asked for now has someone to answer it: the loader needs an address, and
    // the box art endpoint a paired host.
    private static boolean canFetchArt(ComputerDetails details) {
        return isReachable(details) && details.activeAddress != null;
    }

    private void runInBackground(Runnable task) {
        if (closed) {
            return;
        }
        try {
            executor.execute(task);
        } catch (RejectedExecutionException e) {
            // Closed between the check and the call; the work is no longer wanted.
        }
    }

    private static List<NvApp> parse(String raw) {
        try {
            return NvHTTP.getAppListByReader(new StringReader(raw));
        } catch (XmlPullParserException | IOException e) {
            return null;
        }
    }

    /**
     * A game's metadata as this host last served it, read from the copy the library keeps on
     * disk; null when the host never answered, or has nothing for that game. For a screen that
     * runs without a library open, the stream's connection scene above all, which must not ask
     * the host for it again while the host is busy starting the game. Disk I/O: never on the
     * main thread.
     */
    @Nullable
    public static AppMetadata cachedMetadata(File cacheDir, String hostUuid, String appUuid) {
        if (hostUuid == null || appUuid == null || appUuid.isEmpty()) {
            return null;
        }
        String raw;
        try (InputStream in = CacheHelper.openCacheFileForInput(cacheDir, METADATA_CACHE, hostUuid)) {
            raw = CacheHelper.readInputStreamToString(in);
        } catch (IOException e) {
            return null;
        }
        return AppMetadata.parseAll(raw).get(appUuid.toUpperCase(Locale.ROOT));
    }

    /**
     * A game's background art as the host sends it, decoded down to the size the library keeps
     * it at. Blocking, for a caller that fetched the stream itself (see {@link #cachedMetadata}).
     */
    public static Bitmap decodeBackground(InputStream in) throws IOException {
        return decodeSampled(in, BACKGROUND_MAX_WIDTH);
    }

    // Decode a stream down to at most maxWidth px wide, so a 4K hero image doesn't blow up memory.
    static Bitmap decodeSampled(InputStream in, int maxWidth) throws IOException {
        try (InputStream stream = in) {
            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            byte[] chunk = new byte[16 * 1024];
            int read;
            while ((read = stream.read(chunk)) != -1) {
                buffer.write(chunk, 0, read);
            }
            byte[] bytes = buffer.toByteArray();

            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            BitmapFactory.decodeByteArray(bytes, 0, bytes.length, bounds);

            int sample = 1;
            while (bounds.outWidth / sample > maxWidth) {
                sample *= 2;
            }

            BitmapFactory.Options opts = new BitmapFactory.Options();
            opts.inSampleSize = sample;
            return BitmapFactory.decodeByteArray(bytes, 0, bytes.length, opts);
        }
    }
}
