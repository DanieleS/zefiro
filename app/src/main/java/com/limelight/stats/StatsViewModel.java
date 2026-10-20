package com.limelight.stats;

import androidx.annotation.Nullable;
import androidx.lifecycle.ViewModel;

import com.limelight.nvstream.http.AppStats;

import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * What the Stats section is looking at, kept apart from its views: the range, how many periods
 * back, the page last shown, and the thread the pages are asked for on.
 *
 * <p>StatsActivity kept these as fields and lost them with the screen; the section's views can
 * go and come back (a restore), and the page on screen should come back with them rather than
 * start again from this week behind a spinner.
 *
 * <h3>Per host</h3>
 *
 * All of it belongs to one host. The main screen's Stats follows the selected host, so on a
 * switch {@link #forHost} starts over from this week, and any answer still on its way for the
 * old host is dropped by {@link #isLatest}:
 *
 * <pre>
 *   forHost(B) while on A ──▶ range WEEK, offset 0, stats null, request++  (A's answer is stale)
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

    private final ExecutorService loader = Executors.newSingleThreadExecutor();

    @Nullable
    private String hostUuid;
    private String range = AppStats.RANGE_WEEK;
    private int offset;
    @Nullable
    private AppStats stats;
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
        stats = null;
        request++;
        return true;
    }

    String range() {
        return range;
    }

    /** A new range starts from the period under way, as CouchPilot does. */
    void setRange(String range) {
        this.range = range;
        offset = 0;
    }

    int offset() {
        return offset;
    }

    void setOffset(int offset) {
        this.offset = offset;
    }

    /** The page on screen; null until the first one arrives. */
    @Nullable
    AppStats stats() {
        return stats;
    }

    void setStats(@Nullable AppStats stats) {
        this.stats = stats;
    }

    /** Numbers a new request; any earlier one's answer is stale from now on. */
    int nextRequest() {
        return ++request;
    }

    boolean isLatest(int request) {
        return request == this.request;
    }

    @Override
    protected void onCleared() {
        loader.shutdownNow();
    }
}
