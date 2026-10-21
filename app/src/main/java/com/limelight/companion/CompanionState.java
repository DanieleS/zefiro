package com.limelight.companion;

import android.graphics.Bitmap;

import com.limelight.LimeLog;
import com.limelight.binding.video.PerfStats;
import com.limelight.nvstream.http.AppMetadata;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * The companion screen's state, as two independent regions.
 *
 * There is one companion panel, so there is one state, and it outlives any single
 * Presentation: re-hosting the surface on another Activity does not lose the current stats.
 *
 * Producers push into it and the surface redraws; nothing polls. Main thread only.
 *
 * <h3>Why two regions rather than one enum</h3>
 *
 * "What is there to show" and "who owns the screen" answer different questions and change on
 * different events, so they are kept apart. A companion app taking the screen while a stream runs
 * does not stop the stream, and when the app closes the panel must come back to the stats — not to
 * whatever the library was last spotlighting. Folding the two into a single enum forces every
 * content event to be repeated once per owner, and invites exactly the class of bug this replaces:
 * inferring the mode from whether a payload happens to be null.
 *
 * <pre>
 * ┌─ content ────────────────────────────────────────────────┐
 * │   IDLE ──enterBrowsing──▶ BROWSING ──leaveBrowsing──▶ IDLE│
 * │   IDLE ──enterStreaming─▶ STREAMING ─leaveStreaming─▶ IDLE│
 * └──────────────────────────────────────────────────────────┘
 * ┌─ owner ──────────────────────────────────────────────────┐
 * │   PANEL ◀──▶ OFF            (the user's own show/hide)    │
 * │   PANEL ◀──▶ COMPANION_APP  (an app borrows the screen)   │
 * └──────────────────────────────────────────────────────────┘
 * </pre>
 *
 * The panel is on screen exactly when {@code owner == PANEL}; what it draws depends only on
 * {@code content}. Nothing is inferred from the payloads.
 *
 * <h3>Browsing</h3>
 *
 * While the main screen is up, BROWSING carries a {@link Browsing}: what the d-pad is on up
 * there, already resolved to the words and art the panel draws, plus the hosts row. Which of the
 * three kinds it is follows the section on the main screen:
 *
 * <pre>
 *   GAME        a game is focused (hero, shelf, See all, Search): details, Play or Resume/Quit
 *   HOST        a host is (the Library's chip, a PC card, or no game at all): its state
 *   IDLE_HOSTS  nothing the panel can describe (Stats): the app name
 *               ── every kind draws the hosts row under it
 * </pre>
 *
 * The state holds values only, never callbacks: the panel's Play and host taps go back through
 * {@link CompanionDisplayManager#setBrowsingActions}, to whichever screen registered them. A
 * value can outlive the screen that pushed it (the panel is re-hosted on its own schedule); a
 * callback held here would outlive it too, and could start a game from a screen that is gone.
 */
public class CompanionState {

    /** What there is to show. Driven by whichever screen is in the foreground. */
    public enum Content { IDLE, BROWSING, STREAMING }

    /** Who the secondary screen belongs to right now. */
    public enum Owner {
        /** Our panel is up. */
        PANEL,
        /** A companion app was launched onto the screen and is holding it. */
        COMPANION_APP,
        /** The user hid the panel by hand; remembered until they ask for it back. */
        OFF
    }

    public interface Listener {
        void onCompanionStateChanged();
    }

    /** Told when the owner region moves, so the panel can be attached or torn down. */
    public interface OwnerListener {
        void onCompanionOwnerChanged();
    }

    private static final CompanionState INSTANCE = new CompanionState();

    private Content content = Content.IDLE;
    private Owner owner = Owner.PANEL;

    /** Null when not streaming, or streaming with the stats switched off. */
    private PerfStats stats;
    private Listener listener;
    private OwnerListener ownerListener;

    /** What the main screen is on while browsing; null when nothing is being browsed. */
    private Browsing browsing;
    /** The hosts row under the browsing surface. Never null; empty when there is none to draw. */
    private List<HostChip> hosts = Collections.emptyList();

    private CompanionState() {
    }

    public static CompanionState getInstance() {
        return INSTANCE;
    }

    /** The surface registers while it is on screen. At most one draws at a time. */
    public void setListener(Listener listener) {
        this.listener = listener;
    }

    /**
     * Deregister, but only if {@code who} is still the one registered. While the surface is
     * being re-hosted the incoming panel registers before the outgoing one tears down, so the
     * outgoing panel must not clear a listener that is no longer its own.
     */
    public void clearListener(Listener who) {
        if (listener == who) {
            listener = null;
        }
    }

    // --- The content region ---

    public Content getContent() {
        return content;
    }

    /** The library went away. */
    public void leaveBrowsing() {
        if (content == Content.BROWSING) {
            setContent(Content.IDLE);
        }
    }

    /** A stream connected. */
    public void enterStreaming() {
        setContent(Content.STREAMING);
    }

    /** The stream ended; its stats and telemetry go with it. */
    public void leaveStreaming() {
        if (content == Content.STREAMING) {
            stats = null;
            // Both belong to the game that just ended. Left behind, the panel would keep drawing a
            // view fed by a stream that has stopped — the last frame of a dead game, forever.
            telemetryView = null;
            telemetryStream = null;
            // The panel's choices for this stream end with it: the next game starts on its own
            // view (if it has one), and a view that crashed this time is given another chance.
            statsOverTelemetry = false;
            crashedTelemetryView = null;
            streamSession = null;
            setContent(Content.IDLE);
        }
    }

    private void setContent(Content next) {
        if (content == next) {
            return;
        }
        // Browsing and streaming never overlap: the library is stopped while a game runs. Landing
        // here means a producer pushed from a screen that should have been gone, which is worth
        // hearing about rather than silently drawing the wrong surface.
        if (content == Content.STREAMING && next == Content.BROWSING) {
            LimeLog.warning("CompanionState: refusing BROWSING while STREAMING");
            return;
        }
        content = next;
        notifyChanged();
    }

    public void setStats(PerfStats stats) {
        this.stats = stats;
        notifyChanged();
    }

    public PerfStats getStats() {
        return stats;
    }

    // --- Telemetry ---
    //
    // Only the two facts that change rarely live here: whether this game has a view, and the stream
    // to follow. The frames themselves do not — they arrive up to twenty times a second, and pushing
    // each one through notifyChanged() would redraw the whole surface to deliver a number. A frame
    // goes straight into the view that is already on screen, the way a video frame does.

    /** The HTML view for the running game, or null when it has none installed. */
    private String telemetryView;
    private TelemetryStream telemetryStream;

    /**
     * Give the panel a view to draw for the running game. Null means this game has none, and the
     * panel falls back to the stats.
     */
    public void setTelemetryView(String html) {
        if (telemetryView == null ? html == null : telemetryView.equals(html)) {
            return;
        }
        telemetryView = html;
        notifyChanged();
    }

    public String getTelemetryView() {
        return telemetryView;
    }

    /**
     * The stream the panel should follow. Held here because the panel is created and destroyed as
     * the surface moves between activities, while the stream outlives any one of them.
     *
     * <p>A panel already on screen has to hear about it: the stream starts after the session has
     * connected, which is well after the panel was hosted for it.
     */
    public void setTelemetryStream(TelemetryStream stream) {
        if (telemetryStream == stream) {
            return;
        }
        telemetryStream = stream;
        notifyChanged();
    }

    public TelemetryStream getTelemetryStream() {
        return telemetryStream;
    }

    // --- The panel's own choices for the running stream ---
    //
    // Held here rather than on the panel because the panel is re-hosted on every activity change
    // (a Settings round-trip mid-stream builds a new one), and a choice the user made must not be
    // undone by that. Both last until leaveStreaming().

    /**
     * Whether the full stats are showing in place of the game's telemetry view: the strip under a
     * view only fits frame rate and latency, so its stats button swaps the view for the full stats,
     * and the same button (or "Game") swaps back.
     */
    private boolean statsOverTelemetry;

    /** A view whose renderer died, so it is not loaded again this stream; null when none has. */
    private String crashedTelemetryView;

    public boolean isStatsOverTelemetry() {
        return statsOverTelemetry;
    }

    public void setStatsOverTelemetry(boolean over) {
        if (statsOverTelemetry == over) {
            return;
        }
        statsOverTelemetry = over;
        notifyChanged();
    }

    /**
     * The view's renderer crashed or was killed. Whatever took it down would most likely do it
     * again, so for the rest of this stream that view gives way to the stats.
     */
    public void markTelemetryViewCrashed(String html) {
        if (html == null || html.equals(crashedTelemetryView)) {
            return;
        }
        crashedTelemetryView = html;
        notifyChanged();
    }

    /** The telemetry view to draw: the game's, unless it crashed this stream. Null for none. */
    public String getUsableTelemetryView() {
        return telemetryView != null && !telemetryView.equals(crashedTelemetryView)
                ? telemetryView : null;
    }

    // --- What the stream is ---

    /** Null until the stream has connected, and again once it ends. */
    private StreamSession streamSession;

    /** What the running stream is, for the stats header and the strip's session length. */
    public void setStreamSession(StreamSession session) {
        if (Objects.equals(streamSession, session)) {
            return;
        }
        streamSession = session;
        notifyChanged();
    }

    public StreamSession getStreamSession() {
        return streamSession;
    }

    // --- The owner region ---

    public Owner getOwner() {
        return owner;
    }

    /** Whether our panel is the thing on the secondary screen right now. */
    public boolean isPanelOwner() {
        return owner == Owner.PANEL;
    }

    public void setOwner(Owner next) {
        if (owner == next) {
            return;
        }
        owner = next;
        if (ownerListener != null) {
            ownerListener.onCompanionOwnerChanged();
        }
    }

    /**
     * Registered by the display manager, which attaches and tears down the panel. Kept apart from
     * {@link Listener} because that one belongs to whichever surface is currently drawing, and is
     * swapped mid-flight while the panel is re-hosted.
     */
    public void setOwnerListener(OwnerListener ownerListener) {
        this.ownerListener = ownerListener;
    }

    /**
     * Point the browsing surface at what the main screen is on. Pushing the browsing that is
     * already shown changes nothing: the main screen re-resolves on every poll, and an unchanged
     * answer must not redraw the panel (or rewind its description) each time.
     */
    public void setBrowsing(Browsing next) {
        if (next == null) {
            clearBrowsing();
            return;
        }
        boolean same = next.equals(browsing);
        browsing = next;
        // Spotlighting something is what "browsing" means; no separate call to keep in step.
        setContent(Content.BROWSING);
        if (!same) {
            notifyChanged();
        }
    }

    /**
     * The hosts row: every known PC, the selected one marked. Kept apart from {@link Browsing}
     * because it changes on its own clock (each poll of each host), and a focus move must not
     * have to rebuild it.
     */
    public void setHosts(List<HostChip> next) {
        List<HostChip> copy = next == null || next.isEmpty()
                ? Collections.<HostChip>emptyList()
                : Collections.unmodifiableList(new ArrayList<>(next));
        if (copy.equals(hosts)) {
            return;
        }
        hosts = copy;
        notifyChanged();
    }

    public List<HostChip> getHosts() {
        return hosts;
    }

    public Browsing getBrowsing() {
        return browsing;
    }

    /** Stop browsing, and leave the browsing surface with it, hosts row and all. */
    public void clearBrowsing() {
        leaveBrowsing();
        if (browsing == null && hosts.isEmpty()) {
            return;
        }
        browsing = null;
        hosts = Collections.emptyList();
        notifyChanged();
    }

    /** The browsed title, or null when nothing is being browsed. */
    public String getBrowsingTitle() {
        return browsing != null ? browsing.title : null;
    }

    private void notifyChanged() {
        if (listener != null) {
            listener.onCompanionStateChanged();
        }
    }

    /**
     * What the main screen is on, as the panel draws it: resolved up there, where the host's
     * games and metadata are, so the panel needs nothing but this. Immutable.
     *
     * <p>Two are equal when they would draw the same; the backdrop by identity, since the cache
     * hands out the same bitmap for the same game.
     */
    public static final class Browsing {

        public enum Kind { GAME, HOST, IDLE_HOSTS }

        /** {@link #appId} when there is no game to act on. */
        public static final int NO_APP = 0;

        public final Kind kind;
        /** The host the game (or the host itself) is on; null for IDLE_HOSTS. */
        public final String hostUuid;
        /** The game Play, Resume and Quit act on, or {@link #NO_APP} for none. */
        public final int appId;
        /** The heading; null draws the app's own name. */
        public final String title;
        /** One line under it ("2020 · Action, RPG · 88%", "Online"); null or empty for none. */
        public final String meta;
        /** The host's description, as the HTML Playnite stores; null for none. */
        public final String descriptionHtml;
        /** The art behind it all; null until (or unless) there is some. */
        public final Bitmap backdrop;
        /** Whether {@link #appId} is the game the host is running: Resume and Quit, not Play. */
        public final boolean running;
        /** Where the game is in the list it was focused in, from 0; -1 when that is not told. */
        public final int position;
        /** How many games that list has; 0 when not told. */
        public final int count;

        public Browsing(Kind kind, String hostUuid, int appId, String title, String meta,
                        String descriptionHtml, Bitmap backdrop, boolean running) {
            this(kind, hostUuid, appId, title, meta, descriptionHtml, backdrop, running, -1, 0);
        }

        /** As the other, with the game's place in its list ("Focused · 4 of 12"). */
        public Browsing(Kind kind, String hostUuid, int appId, String title, String meta,
                        String descriptionHtml, Bitmap backdrop, boolean running, int position,
                        int count) {
            if (kind == null) {
                throw new IllegalArgumentException("kind must not be null");
            }
            this.kind = kind;
            this.hostUuid = hostUuid;
            this.appId = appId;
            this.title = title;
            this.meta = meta;
            this.descriptionHtml = descriptionHtml;
            this.backdrop = backdrop;
            this.running = running;
            boolean placed = position >= 0 && position < count;
            this.position = placed ? position : -1;
            this.count = placed ? count : 0;
        }

        /** Whether the game's place in its list is known. */
        public boolean hasPosition() {
            return position >= 0;
        }

        /** Nothing in particular: the app's name over the hosts row. */
        public static Browsing idleHosts() {
            return new Browsing(Kind.IDLE_HOSTS, null, NO_APP, null, null, null, null, false);
        }

        public Browsing withBackdrop(Bitmap next) {
            return next == backdrop ? this
                    : new Browsing(kind, hostUuid, appId, title, meta, descriptionHtml, next, running,
                            position, count);
        }

        /** Whether the panel has a game here to play, resume or quit. */
        public boolean hasGame() {
            return appId != NO_APP && hostUuid != null;
        }

        /**
         * "2020 · Action, RPG · 88%": only the parts the host has. Empty for no metadata.
         * Plain Java rather than TextUtils, so the state stays testable off the device.
         */
        public static String metaLine(AppMetadata meta) {
            if (meta == null) {
                return "";
            }
            List<String> parts = new ArrayList<>(3);
            String year = meta.getReleaseYear();
            if (year != null && !year.isEmpty()) {
                parts.add(year);
            }
            List<String> genres = meta.getGenres();
            if (genres != null && !genres.isEmpty()) {
                parts.add(join(", ", genres.subList(0, Math.min(3, genres.size()))));
            }
            int score = meta.getCommunityScore();
            if (score >= 0) {
                parts.add(score + "%");
            }
            return join("  ·  ", parts);
        }

        private static String join(String separator, List<String> parts) {
            StringBuilder out = new StringBuilder();
            for (String part : parts) {
                if (out.length() > 0) {
                    out.append(separator);
                }
                out.append(part);
            }
            return out.toString();
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) {
                return true;
            }
            if (!(o instanceof Browsing)) {
                return false;
            }
            Browsing that = (Browsing) o;
            return kind == that.kind
                    && appId == that.appId
                    && running == that.running
                    && position == that.position
                    && count == that.count
                    && backdrop == that.backdrop
                    && Objects.equals(hostUuid, that.hostUuid)
                    && Objects.equals(title, that.title)
                    && Objects.equals(meta, that.meta)
                    && Objects.equals(descriptionHtml, that.descriptionHtml);
        }

        @Override
        public int hashCode() {
            return Objects.hash(kind, hostUuid, appId, title, meta, descriptionHtml,
                    System.identityHashCode(backdrop), running, position, count);
        }

        @Override
        public String toString() {
            return kind + " " + title + (running ? " [running]" : "");
        }
    }

    /**
     * The running stream as the panel names it: "Hades II · Taverna", "HEVC · HDR", and when it
     * started. Values only, read off Game when the stream connects. Immutable.
     */
    public static final class StreamSession {
        /** The game's name; null when the launch did not say. */
        public final String game;
        /** The PC's name; null when the launch did not say. */
        public final String host;
        /** The video codec as people name it ("HEVC · HDR"); null while unknown. */
        public final String codec;
        /** {@code SystemClock.uptimeMillis()} when the stream started; 0 while unknown. */
        public final long startUptimeMs;
        /** The frame rate the stream was asked for, for the health dot; 0 when unknown. */
        public final float targetFps;

        public StreamSession(String game, String host, String codec, long startUptimeMs,
                             float targetFps) {
            this.game = game;
            this.host = host;
            this.codec = codec;
            this.startUptimeMs = startUptimeMs;
            this.targetFps = targetFps;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) {
                return true;
            }
            if (!(o instanceof StreamSession)) {
                return false;
            }
            StreamSession that = (StreamSession) o;
            return startUptimeMs == that.startUptimeMs
                    && Float.compare(targetFps, that.targetFps) == 0
                    && Objects.equals(game, that.game)
                    && Objects.equals(host, that.host)
                    && Objects.equals(codec, that.codec);
        }

        @Override
        public int hashCode() {
            return Objects.hash(game, host, codec, startUptimeMs, targetFps);
        }

        @Override
        public String toString() {
            return game + " on " + host + " (" + codec + ")";
        }
    }

    /** One PC on the hosts row: enough to draw its chip and to select it. Immutable. */
    public static final class HostChip {
        public final String uuid;
        public final String name;
        public final boolean online;
        public final boolean selected;

        public HostChip(String uuid, String name, boolean online, boolean selected) {
            if (uuid == null) {
                throw new IllegalArgumentException("uuid must not be null");
            }
            this.uuid = uuid;
            this.name = name;
            this.online = online;
            this.selected = selected;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) {
                return true;
            }
            if (!(o instanceof HostChip)) {
                return false;
            }
            HostChip that = (HostChip) o;
            return online == that.online
                    && selected == that.selected
                    && uuid.equals(that.uuid)
                    && Objects.equals(name, that.name);
        }

        @Override
        public int hashCode() {
            return Objects.hash(uuid, name, online, selected);
        }

        @Override
        public String toString() {
            return name + (selected ? " [selected]" : "") + (online ? "" : " [offline]");
        }
    }
}
