package com.limelight.main;

import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.text.format.DateUtils;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.lifecycle.Observer;

import com.limelight.R;
import com.limelight.companion.CompanionDisplayManager;
import com.limelight.companion.CompanionPage;
import com.limelight.companion.CompanionState;
import com.limelight.companion.CompanionState.Browsing;
import com.limelight.computers.HostEntry;
import com.limelight.computers.HostLibrary;
import com.limelight.computers.HostRepository;
import com.limelight.grid.AppSortOrder;
import com.limelight.library.LaunchController;
import com.limelight.library.LibraryApp;
import com.limelight.nvstream.http.AppMetadata;
import com.limelight.nvstream.http.ComputerDetails;
import com.limelight.nvstream.http.NvApp;
import com.limelight.pcs.HostCardBinder;
import com.limelight.preferences.AddComputerManually;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * Feeds the companion panel (the Thor's bottom screen) while the main screen is browsing, and
 * answers the panel's Play, Resume, Quit, host and "+" (Add PC) taps.
 *
 * <p>The old library fed the panel from the activity, a raw thread per backdrop, and a listener on
 * every tile it laid out. With the sections as fragments of one activity, re-hosting no longer
 * marks a change of screen: switching to PCs or Stats keeps the same activity, so the panel would
 * keep the last game forever unless told otherwise. This feed is the one place that tells it. It is
 * owned by {@link MainActivity}, never by a section, so the panel's taps always reach a screen that
 * is there.
 *
 * <h3>What the panel shows</h3>
 *
 * <pre>
 *   Library or Search, a game in focus     GAME        details, Play (or Resume / Quit game)
 *   Library, the chip or no games in focus HOST        the selected PC's name and state
 *   PCs                                    HOST        the focused card's PC
 *   Stats                                  IDLE_HOSTS  the app name
 *                                          ── each over the hosts row
 * </pre>
 *
 * Sections report focus through {@link GameFocusListener}; the activity reports a section
 * switch the same way (a host for most, {@link #none()} for Stats), and the section's own first
 * focus then refines it.
 *
 * <h3>Settling</h3>
 *
 * A d-pad held across a shelf moves focus a dozen times a second. Each report replaces the one
 * before it and waits {@value #FOCUS_SETTLE_MS} ms; only the one still standing is resolved and
 * drawn, and only it may fetch a backdrop. Fetches run on the selected host's
 * {@link HostLibrary} executor and land in its cache, so coming back to a game draws its art at
 * once, and no game is fetched for twice while it stays in focus.
 *
 * <p>What a report names is resolved again whenever the host state moves (a poll, metadata, the
 * game starting or stopping), so the panel follows a game that starts running without the d-pad
 * moving. An unchanged answer does not redraw: {@link CompanionState} drops equal pushes.
 *
 * <h3>Pages</h3>
 *
 * A screen with a lower half of its own (a game's achievements, the Stats remote) publishes it
 * here as a {@link CompanionPage} ({@link CompanionPages}). The panel draws a page in place of
 * the spotlight, so while one is published the focus reports above still settle but are not
 * seen. The feed holds the page and its owner, routes the page's taps to that owner, and, as for
 * browsing taps, hands the d-pad back to the main screen after each.
 *
 * <h3>Lifecycle</h3>
 *
 * <pre>
 *   start()  ── onStart: watches the hosts and the library, registers the panel's actions,
 *              and pushes what was last reported, page included, so coming back from a stream
 *              restores it
 *   stop()   ── onStop:  stops watching, unregisters, and clears the browsing surface; a game
 *              launch stops this activity without destroying it. The page and its owner are
 *              kept for start()
 * </pre>
 *
 * Main thread only.
 */
public final class CompanionFeed implements GameFocusListener,
        CompanionDisplayManager.BrowsingActions, CompanionPages, CompanionDisplayManager.PageActions {

    /** How long a focus report must stand before the panel follows it. */
    static final long FOCUS_SETTLE_MS = 150;

    /**
     * How long after a tap on a page the main window entering touch mode is put down to that tap:
     * the time it takes the reclaim after it to give the main window its focus back. As long as
     * MainActivity's window for a lost focus, for the same round trip.
     */
    static final long PAGE_TAP_GRACE_MS = 600;

    /** What a report names; resolved into a {@link Browsing} only when drawn. */
    private static final class Focus {
        final Browsing.Kind kind;
        /** The host, or null for the selected one. */
        @Nullable
        final String hostUuid;
        final int appId;
        /** The game's place in the list it was focused in; -1 and 0 when not told. */
        final int position;
        final int count;

        Focus(Browsing.Kind kind, @Nullable String hostUuid, int appId) {
            this(kind, hostUuid, appId, -1, 0);
        }

        Focus(Browsing.Kind kind, @Nullable String hostUuid, int appId, int position, int count) {
            this.kind = kind;
            this.hostUuid = hostUuid;
            this.appId = appId;
            this.position = position;
            this.count = count;
        }

        @Override
        public boolean equals(Object o) {
            if (!(o instanceof Focus)) {
                return false;
            }
            Focus that = (Focus) o;
            return kind == that.kind && appId == that.appId && position == that.position
                    && count == that.count && Objects.equals(hostUuid, that.hostUuid);
        }

        @Override
        public int hashCode() {
            return Objects.hash(kind, hostUuid, appId, position, count);
        }
    }

    private static final Focus SELECTED_HOST = new Focus(Browsing.Kind.HOST, null, Browsing.NO_APP);
    private static final Focus NOTHING = new Focus(Browsing.Kind.IDLE_HOSTS, null, Browsing.NO_APP);

    private final Context context;
    private final HostRepository repository;
    private final LaunchController launcher;
    private final Runnable reclaimFocus;
    private final CompanionState state = CompanionState.getInstance();
    private final Handler main = new Handler(Looper.getMainLooper());

    // What is drawn, and the report waiting to replace it.
    private Focus current = SELECTED_HOST;
    @Nullable
    private Focus pending;
    private boolean started;

    // The page published last and the screen it belongs to; both null when there is none.
    @Nullable
    private CompanionPage page;
    @Nullable
    private PageOwner pageOwner;
    // When the page was last tapped on the panel (uptime), or 0 for never.
    private long pageTapAt;

    // The backdrop asked for while the current game is in focus, so it is asked for once.
    @Nullable
    private HostLibrary backdropLibrary;
    private int backdropAppId = Browsing.NO_APP;

    private final Runnable settle = () -> {
        if (pending != null) {
            current = pending;
            pending = null;
            refresh();
        }
    };

    private final Observer<List<HostEntry>> hostsObserver = hosts -> {
        pushHosts();
        refresh();
    };
    private final Observer<String> selectionObserver = uuid -> {
        pushHosts();
        refresh();
    };
    private final Observer<HostLibrary.Snapshot> libraryObserver = snapshot -> refresh();

    /**
     * @param reclaimFocus run after a tap on the panel, to hand the d-pad back to the main screen
     */
    public CompanionFeed(@NonNull Context context, @NonNull HostRepository repository,
                         @NonNull LaunchController launcher, @NonNull Runnable reclaimFocus) {
        this.context = context;
        this.repository = repository;
        this.launcher = launcher;
        this.reclaimFocus = reclaimFocus;
    }

    /** From the activity's onStart. */
    public void start() {
        if (started) {
            return;
        }
        started = true;
        if (pending != null) {
            // Reported while stopped (a section restored before onStart): nothing to settle.
            current = pending;
            pending = null;
        }
        CompanionDisplayManager.setBrowsingActions(this);
        CompanionDisplayManager.setPageActions(this);
        // Each delivers the value it holds at once, which draws what was last reported.
        repository.getHosts().observeForever(hostsObserver);
        repository.getSelectedHostUuid().observeForever(selectionObserver);
        repository.getLibrary().observeForever(libraryObserver);
        if (page != null) {
            state.setPage(page);
        }
    }

    /**
     * From the activity's onStop. The surface is cleared here, not on destroy: launching a game
     * only stops this activity, and the stream's panel must not open on the library's game.
     */
    public void stop() {
        if (!started) {
            return;
        }
        started = false;
        main.removeCallbacks(settle);
        if (pending != null) {
            // Kept, so the panel comes back to what the d-pad was last on.
            current = pending;
            pending = null;
        }
        repository.getHosts().removeObserver(hostsObserver);
        repository.getSelectedHostUuid().removeObserver(selectionObserver);
        repository.getLibrary().removeObserver(libraryObserver);
        CompanionDisplayManager.setBrowsingActions(null);
        CompanionDisplayManager.setPageActions(null);
        // Takes the page off the state too; it is kept here for start().
        state.clearBrowsing();
    }

    // --- GameFocusListener ---

    @Override
    public void focusGame(@NonNull LibraryApp app) {
        focusGame(app, -1, 0);
    }

    @Override
    public void focusGame(@NonNull LibraryApp app, int position, int count) {
        HostLibrary.Snapshot snapshot = repository.getLibrary().getValue();
        report(new Focus(Browsing.Kind.GAME, snapshot != null ? snapshot.hostUuid : null,
                app.appId(), position, count));
    }

    @Override
    public void focusHost(@Nullable String hostUuid) {
        report(hostUuid == null ? SELECTED_HOST : new Focus(Browsing.Kind.HOST, hostUuid, Browsing.NO_APP));
    }

    @Override
    public void none() {
        report(NOTHING);
    }

    /**
     * The section on screen changed; its own focus reports follow and refine this. Settings has
     * nothing of its own for the panel yet, so it shows the idle hosts surface, as Stats does
     * before its page is published.
     */
    public void onSection(@NonNull Section section) {
        if (section == Section.STATS || section == Section.SETTINGS) {
            none();
        } else {
            focusHost(null);
        }
    }

    private void report(Focus next) {
        main.removeCallbacks(settle);
        if (next.equals(current)) {
            // Back where it was before the report in flight settled: nothing to redraw.
            pending = null;
            return;
        }
        pending = next;
        if (started) {
            main.postDelayed(settle, FOCUS_SETTLE_MS);
        }
    }

    // --- Drawing ---

    private void pushHosts() {
        if (!started) {
            return;
        }
        List<HostEntry> hosts = repository.getHosts().getValue();
        String selected = repository.getSelectedHostUuid().getValue();
        List<CompanionState.HostChip> chips = new ArrayList<>();
        if (hosts != null) {
            for (HostEntry entry : hosts) {
                ComputerDetails details = entry.details;
                if (details.uuid == null) {
                    continue;
                }
                chips.add(new CompanionState.HostChip(details.uuid, details.name,
                        details.state == ComputerDetails.State.ONLINE,
                        details.uuid.equalsIgnoreCase(selected)));
            }
        }
        state.setHosts(chips);
    }

    private void refresh() {
        if (!started) {
            return;
        }
        state.setBrowsing(resolve(current));
    }

    /** What a report names, as the panel draws it now. */
    @NonNull
    private Browsing resolve(@NonNull Focus focus) {
        HostLibrary.Snapshot snapshot = repository.getLibrary().getValue();
        switch (focus.kind) {
            case GAME: {
                LibraryApp app = snapshot != null && sameHost(snapshot.hostUuid, focus.hostUuid)
                        ? find(snapshot.apps, focus.appId) : null;
                if (app != null) {
                    return game(snapshot, app, focus);
                }
                // Gone from the list, or another host was selected meanwhile (a tap on the
                // panel's hosts row): the selected host is what the Library now shows.
                forgetBackdrop();
                return host(repository.getSelectedHostUuid().getValue(), snapshot);
            }
            case HOST:
                forgetBackdrop();
                return host(focus.hostUuid != null
                        ? focus.hostUuid : repository.getSelectedHostUuid().getValue(), snapshot);
            case IDLE_HOSTS:
            default:
                forgetBackdrop();
                return Browsing.idleHosts();
        }
    }

    private Browsing game(@NonNull HostLibrary.Snapshot snapshot, @NonNull LibraryApp app,
                          @NonNull Focus focus) {
        AppMetadata metadata = metadataFor(snapshot.metadata, app.app);
        String meta = Browsing.metaLine(metadata);
        if (meta.isEmpty()) {
            // Metadata is the host's to add, never needed: without it, when this device last
            // played the game, if it has, so the panel still says something of its own about it.
            meta = lastPlayedLine(snapshot, app);
        }
        return new Browsing(Browsing.Kind.GAME, snapshot.hostUuid, app.appId(),
                app.app.getAppName(), meta,
                metadata != null ? metadata.getDescription() : null,
                backdropFor(snapshot, app.appId(), metadata), app.running,
                focus.position, focus.count);
    }

    /** "Last played 3 days ago", from this device's launch record; empty when never. */
    @NonNull
    private String lastPlayedLine(@NonNull HostLibrary.Snapshot snapshot, @NonNull LibraryApp app) {
        HostLibrary library = repository.currentLibrary();
        if (library == null || library.isClosed()
                || !sameHost(library.getHostUuid(), snapshot.hostUuid)) {
            return "";
        }
        long when = AppSortOrder.lastPlayed(app.app, snapshot.metadata, library.getLaunchHistory());
        if (when <= 0) {
            return "";
        }
        CharSequence ago = DateUtils.getRelativeTimeSpanString(when, System.currentTimeMillis(),
                DateUtils.MINUTE_IN_MILLIS);
        return context.getString(R.string.companion_last_played, ago);
    }

    /**
     * A host's name and state, over the art of the game it is running. For the selected host
     * that game is known, so the panel offers Resume and Quit game for it as the hero does.
     */
    private Browsing host(@Nullable String hostUuid, @Nullable HostLibrary.Snapshot snapshot) {
        HostEntry entry = repository.getHost(hostUuid);
        if (entry == null) {
            return Browsing.idleHosts();
        }
        ComputerDetails details = entry.details;
        String name = details.name != null ? details.name : context.getString(R.string.library_this_pc);
        boolean online = details.state == ComputerDetails.State.ONLINE;
        String meta = online && entry.runningAppName != null
                ? context.getString(R.string.pcview_status_running, entry.runningAppName)
                : context.getString(HostCardBinder.statusLabel(details));

        NvApp running = snapshot != null && sameHost(snapshot.hostUuid, details.uuid)
                ? snapshot.running : null;
        int appId = running != null ? running.getAppId() : Browsing.NO_APP;
        return new Browsing(Browsing.Kind.HOST, details.uuid, appId, name, meta, null,
                online ? entry.runningBackground : null, running != null);
    }

    /**
     * The game's backdrop if the selected host's library has it cached; otherwise asks for it
     * once, when the host says there is one, and draws again when it lands.
     */
    @Nullable
    private Bitmap backdropFor(@NonNull HostLibrary.Snapshot snapshot, final int appId,
                               @Nullable AppMetadata metadata) {
        final HostLibrary library = repository.currentLibrary();
        if (library == null || library.isClosed() || !sameHost(library.getHostUuid(), snapshot.hostUuid)) {
            return null;
        }
        Bitmap cached = library.cachedBackground(appId);
        if (cached != null || metadata == null || !metadata.hasBackground()) {
            return cached;
        }
        if (backdropLibrary == library && backdropAppId == appId) {
            // Asked already while this game has been in focus; a failure is not retried on
            // every poll.
            return null;
        }
        backdropLibrary = library;
        backdropAppId = appId;
        library.loadBackground(appId, (id, background) -> {
            // Drawn only if the panel is still on that game of that host; either way the art
            // stays in the library's cache for when it comes back.
            if (started && current.kind == Browsing.Kind.GAME && current.appId == id
                    && repository.currentLibrary() == library) {
                refresh();
            }
        });
        return null;
    }

    private void forgetBackdrop() {
        backdropLibrary = null;
        backdropAppId = Browsing.NO_APP;
    }

    // --- Pages (CompanionPages) ---

    @Override
    public void publish(@NonNull PageOwner owner, @NonNull CompanionPage next) {
        pageOwner = owner;
        page = next;
        if (started) {
            state.setPage(next);
        }
    }

    @Override
    public void clear(@NonNull PageOwner owner) {
        if (owner != pageOwner) {
            // Not the page on the panel any more: another screen's took its place.
            return;
        }
        pageOwner = null;
        page = null;
        if (started) {
            state.setPage(null);
        }
    }

    /** A tap on the page's stop (CompanionDisplayManager.PageActions). */
    @Override
    public void onPageTap(@NonNull CompanionPage tapped, int stopId) {
        // Marked before anything runs, so the touch mode the tap brings is put down to it.
        pageTapAt = SystemClock.uptimeMillis();
        // The owner of the page tapped, and only while it still is.
        if (pageOwner != null && tapped.equals(page)) {
            pageOwner.onPageTap(stopId);
        }
        reclaimFocus.run();
    }

    @Override
    public boolean pageTappedJustNow() {
        return pageTapAt != 0 && SystemClock.uptimeMillis() - pageTapAt < PAGE_TAP_GRACE_MS;
    }

    // --- The panel's taps (CompanionDisplayManager.BrowsingActions) ---

    @Override
    public void play(Browsing browsing) {
        LibraryApp app = gameFor(browsing);
        if (app != null) {
            // A tile's tap, so another game running asks first, exactly as on the main screen.
            launcher.onTileClicked(app);
        }
        reclaimFocus.run();
    }

    @Override
    public void resume(Browsing browsing) {
        NvApp app = runningFor(browsing);
        if (app != null) {
            launcher.resume(app);
        }
        reclaimFocus.run();
    }

    @Override
    public void quit(Browsing browsing) {
        NvApp app = runningFor(browsing);
        if (app != null) {
            launcher.quit(app);
        }
        reclaimFocus.run();
    }

    @Override
    public void selectHost(String hostUuid) {
        repository.select(hostUuid);
        reclaimFocus.run();
    }

    @Override
    public void addHost() {
        // Opened on the main screen, where its keyboard and the d-pad are.
        context.startActivity(new Intent(context, AddComputerManually.class));
    }

    /**
     * The game a tap names, if it is still the selected host's. A tap that lands after another
     * host was selected does nothing: the launcher would start it on the wrong PC.
     */
    @Nullable
    private LibraryApp gameFor(Browsing browsing) {
        HostLibrary.Snapshot snapshot = repository.getLibrary().getValue();
        if (browsing == null || !browsing.hasGame() || snapshot == null
                || !sameHost(snapshot.hostUuid, browsing.hostUuid)) {
            return null;
        }
        return find(snapshot.apps, browsing.appId);
    }

    /** As {@link #gameFor}, but also the host's running game when it is not in the list. */
    @Nullable
    private NvApp runningFor(Browsing browsing) {
        LibraryApp listed = gameFor(browsing);
        if (listed != null) {
            return listed.app;
        }
        HostLibrary.Snapshot snapshot = repository.getLibrary().getValue();
        if (browsing == null || !browsing.hasGame() || snapshot == null || snapshot.running == null
                || !sameHost(snapshot.hostUuid, browsing.hostUuid)) {
            return null;
        }
        // A hidden game or a special is not on the list, yet can still be the one running.
        return snapshot.running.getAppId() == browsing.appId ? snapshot.running : null;
    }

    // --- Helpers ---

    @Nullable
    private static LibraryApp find(@Nullable List<LibraryApp> apps, int appId) {
        if (apps == null || appId == Browsing.NO_APP) {
            return null;
        }
        for (LibraryApp app : apps) {
            if (app.appId() == appId) {
                return app;
            }
        }
        return null;
    }

    @Nullable
    private static AppMetadata metadataFor(@Nullable Map<String, AppMetadata> metadata, NvApp app) {
        String uuid = app.getAppUUID();
        if (metadata == null || uuid == null) {
            return null;
        }
        return metadata.get(uuid.toUpperCase(Locale.ROOT));
    }

    private static boolean sameHost(@Nullable String a, @Nullable String b) {
        return a != null && a.equalsIgnoreCase(b);
    }
}
