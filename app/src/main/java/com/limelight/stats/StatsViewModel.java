package com.limelight.stats;

import androidx.annotation.Nullable;
import androidx.lifecycle.ViewModel;

import com.limelight.nvstream.http.AppStats;

import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * What the Stats section is looking at, kept apart from its views: the period asked for, the
 * page last shown and its picked bar, a month on its way from the year chart, and the thread the
 * pages are asked for on.
 *
 * <p>StatsActivity kept these as fields and lost them with the screen; the section's views can
 * go and come back (a restore), and the page on screen should come back with them rather than
 * start again from this week behind a spinner.
 *
 * <h3>Target and shown</h3>
 *
 * The <em>target</em> ({@link #range()}, {@link #offset()}) is the period asked for: the bumpers,
 * X and Y and the remote move it at once. The <em>shown</em> page ({@link #shown()}) is the last
 * answer drawn, and carries its own range and offset. The two differ while an answer is on its
 * way, and stay apart when it fails, so whatever describes the page on screen (the crumb, the
 * summary, which bars can be picked) reads the shown page, never the target.
 *
 * <h3>The picked bar</h3>
 *
 * Each shown page has one picked bar ({@link #selectedBar()}): today's when the page holds today,
 * else its last bar that has happened ({@link StatsDrill#defaultBar}). Moving across bars costs
 * no request; it only moves this. Climbing from a month to its year picks that month on the year
 * page once it lands, rather than today's.
 *
 * <h3>The drill</h3>
 *
 * A on a month of the year chart asks for that month without moving the target
 * ({@link #startDrill}): the year stays the page, with a spinner in its tooltip, until the month
 * lands ({@link #takeDrill} on its answer, which moves the target to that month). If it fails the
 * target never moved, and the year simply stays. Any later request supersedes it:
 *
 * <pre>
 *   startDrill(Oct) ──▶ pendingDrill {0, r5}           target still YEAR 0
 *      r5 lands      ──▶ takeDrill(r5, true)  ──▶ target MONTH 0, shown = October
 *      r5 fails      ──▶ takeDrill(r5, false) ──▶ target YEAR 0, shown = the year (a toast)
 *      RB first      ──▶ nextRequest() = r6, pendingDrill null; r5's answer is stale
 * </pre>
 *
 * <h3>Per host</h3>
 *
 * All of it belongs to one host. The main screen's Stats follows the selected host, so on a
 * switch {@link #forHost} starts over from this week, and any answer still on its way for the
 * old host is dropped by {@link #isLatest}:
 *
 * <pre>
 *   forHost(B) while on A ──▶ range WEEK, offset 0, shown null, request++  (A's answer is stale)
 *   forHost(A) while on A ──▶ nothing changes
 * </pre>
 *
 * <h3>Requests</h3>
 *
 * Only the latest request's answer is shown: stepping quickly through periods fires several, and
 * they need not come back in order. Each one takes a number from {@link #nextRequest()}.
 *
 * Main thread only, apart from the executor's own work.
 */
public class StatsViewModel extends ViewModel {

    /** A month asked for from the year chart, on its way. Immutable. */
    static final class PendingDrill {
        /** The month's offset, as the host counts months. */
        final int monthOffset;
        /** The request that asked for it. */
        final int request;
        /** The year page's bar it was opened from, which the tooltip's spinner sits over. */
        final int bar;

        PendingDrill(int monthOffset, int request, int bar) {
            this.monthOffset = monthOffset;
            this.request = request;
            this.bar = bar;
        }
    }

    private final ExecutorService loader = Executors.newSingleThreadExecutor();

    @Nullable
    private String hostUuid;
    private String range = AppStats.RANGE_WEEK;
    private int offset;
    @Nullable
    private AppStats shown;
    private int selectedBar = -1;
    @Nullable
    private PendingDrill pendingDrill;
    // The bar to pick when the page for this range and offset lands: the month climbed from.
    @Nullable
    private String landingRange;
    private int landingOffset;
    private int landingBar = -1;
    private int request;

    /** Where the pages are fetched; shut down with the model. */
    ExecutorService loader() {
        return loader;
    }

    /**
     * Makes this the host the state is about, starting over when it is another one.
     *
     * @return whether it was another host, and the state was reset
     */
    boolean forHost(@Nullable String uuid) {
        if (Objects.equals(uuid, hostUuid)) {
            return false;
        }
        hostUuid = uuid;
        range = AppStats.RANGE_WEEK;
        offset = 0;
        shown = null;
        selectedBar = -1;
        pendingDrill = null;
        landingRange = null;
        request++;
        return true;
    }

    /** The range asked for. */
    String range() {
        return range;
    }

    /** A new range starts from the period under way, as CouchPilot does. */
    void setRange(String range) {
        this.range = range;
        offset = 0;
        landingRange = null;
    }

    /** The offset asked for. */
    int offset() {
        return offset;
    }

    void setOffset(int offset) {
        this.offset = offset;
        landingRange = null;
    }

    /** The page on screen; null until the first one arrives. */
    @Nullable
    AppStats shown() {
        return shown;
    }

    /**
     * Makes a page the one on screen, and picks its bar: the month climbed from when this is the
     * year climbed to, its default otherwise.
     */
    void setShown(@Nullable AppStats page) {
        shown = page;
        if (page == null) {
            selectedBar = -1;
            return;
        }
        if (landingRange != null && landingRange.equals(page.getRange())
                && landingOffset == page.getOffset()) {
            selectedBar = landingBar;
            landingRange = null;
        } else {
            selectedBar = StatsDrill.defaultBar(page);
        }
    }

    /** The shown page's picked bar, or -1 when it has none. */
    int selectedBar() {
        return selectedBar;
    }

    void setSelectedBar(int bar) {
        selectedBar = bar;
    }

    /**
     * Climbs from a month to its year: the target becomes that year, and the month is picked on
     * it once it lands.
     */
    void climbTo(int yearOffset, int monthIndex) {
        range = AppStats.RANGE_YEAR;
        offset = yearOffset;
        landingRange = AppStats.RANGE_YEAR;
        landingOffset = yearOffset;
        landingBar = monthIndex;
    }

    /** Numbers a new request; any earlier one's answer, a drill's too, is stale from now on. */
    int nextRequest() {
        pendingDrill = null;
        return ++request;
    }

    boolean isLatest(int request) {
        return request == this.request;
    }

    /**
     * Asks for a month of the year on screen, leaving the target on the year until it lands.
     *
     * @param bar the year page's bar it is opened from
     * @return the request's number
     */
    int startDrill(int monthOffset, int bar) {
        int mine = nextRequest();
        pendingDrill = new PendingDrill(monthOffset, mine, bar);
        return mine;
    }

    /** The month on its way from the year chart, or null. */
    @Nullable
    PendingDrill pendingDrill() {
        return pendingDrill;
    }

    /**
     * Settles the drill a request was for, if it was one and is still awaited.
     *
     * @param landed whether its month arrived; the target then moves to it
     * @return the drill, or null when the request was not the pending drill
     */
    @Nullable
    PendingDrill takeDrill(int request, boolean landed) {
        PendingDrill drill = pendingDrill;
        if (drill == null || drill.request != request) {
            return null;
        }
        pendingDrill = null;
        if (landed) {
            range = AppStats.RANGE_MONTH;
            offset = drill.monthOffset;
            landingRange = null;
        }
        return drill;
    }

    @Override
    protected void onCleared() {
        loader.shutdownNow();
    }
}
