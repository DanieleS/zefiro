package com.limelight.stats;

import android.content.res.Resources;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.limelight.R;
import com.limelight.companion.CompanionPage;
import com.limelight.companion.CompanionPageRenderer;
import com.limelight.main.PageFocus;
import com.limelight.nvstream.http.AppStats;
import com.limelight.ui.GamepadHintsView;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * The Stats section's lower half on the companion panel, after the approved Stats mock (C)'s
 * Thor bottom screen: the remote (Week / Month / Year, then the period as three big buttons),
 * the lists that do not belong to a bar behind two tabs ("Unlocks 64", "Pick up again 4"), the
 * library's four totals, and the pad's hints.
 *
 * <h3>Modes</h3>
 *
 * <pre>
 *   FULL          a tracked page: remote, tabs and the active tab's rows, totals
 *   REMOTE_ONLY   no page (the PC did not answer: only range and period), or an untracked one
 *                 (its lists are up on the main screen; range, period and the totals stay here)
 * </pre>
 *
 * A tab whose list is empty is not drawn, and the other one is active; with both empty there are
 * no tabs, and the panel says there is nothing to pick up again.
 *
 * <h3>Stops</h3>
 *
 * Drawn top to bottom as the mock lays them out, walked in the order its "Two screens, one
 * focus" note gives, so DOWN from the main screen's strip lands on the tabs:
 *
 * <pre>
 *   row 0    the tabs shown                     0 UNLOCKS, 1 RESUME   (STOP_TAB + ordinal)
 *   row 1…   the active tab's rows              100, 101, …           (STOP_ROW + index)
 *   then     Week / Month / Year                10, 11, 12            (STOP_RANGE + index)
 *   last     previous, next                     20, 21                (disabled at the bounds)
 * </pre>
 *
 * The current period sits between previous and next and is not a stop: it is what they move.
 *
 * <p>A value, like every {@link CompanionPage}: lists are copied, rows are values, and nothing
 * here acts. Taps name stops and reach the Stats screen through the panel's page actions.
 */
public final class StatsRemotePage extends CompanionPage {

    /** What the page holds; see the class comment. */
    public enum Mode { FULL, REMOTE_ONLY }

    /** The lists behind the tabs. */
    public enum Tab { UNLOCKS, RESUME }

    /** The ranges, in the segments' order. */
    public static final String[] RANGES = {
            AppStats.RANGE_WEEK, AppStats.RANGE_MONTH, AppStats.RANGE_YEAR};

    /** The first tab's stop; the other follows by ordinal. */
    public static final int STOP_TAB = 0;
    /** The first range segment's stop; the others follow by index in {@link #RANGES}. */
    public static final int STOP_RANGE = 10;
    public static final int STOP_PREVIOUS = 20;
    public static final int STOP_NEXT = 21;
    /** The first row's stop; the others follow by index in the active tab's list. */
    public static final int STOP_ROW = 100;

    /** An achievement unlocked in the period: "Wheels Champion · Sea of Stars", and when. */
    public static final class UnlockRow {
        @NonNull
        public final String name;
        @NonNull
        public final String game;
        @NonNull
        public final String when;
        /** The game's UUID, which its page opens by. */
        @Nullable
        public final String appUuid;

        public UnlockRow(@NonNull String name, @NonNull String game, @NonNull String when,
                         @Nullable String appUuid) {
            this.name = name;
            this.game = game;
            this.when = when;
            this.appUuid = appUuid;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) {
                return true;
            }
            if (!(o instanceof UnlockRow)) {
                return false;
            }
            UnlockRow that = (UnlockRow) o;
            return name.equals(that.name) && game.equals(that.game) && when.equals(that.when)
                    && Objects.equals(appUuid, that.appUuid);
        }

        @Override
        public int hashCode() {
            return Objects.hash(name, game, when, appUuid);
        }
    }

    /** A game to pick up again: its name, its hours, and the month it was last played. */
    public static final class ResumeRow {
        @NonNull
        public final String name;
        @NonNull
        public final String hours;
        @NonNull
        public final String lastPlayed;
        @Nullable
        public final String uuid;

        public ResumeRow(@NonNull String name, @NonNull String hours, @NonNull String lastPlayed,
                         @Nullable String uuid) {
            this.name = name;
            this.hours = hours;
            this.lastPlayed = lastPlayed;
            this.uuid = uuid;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) {
                return true;
            }
            if (!(o instanceof ResumeRow)) {
                return false;
            }
            ResumeRow that = (ResumeRow) o;
            return name.equals(that.name) && hours.equals(that.hours)
                    && lastPlayed.equals(that.lastPlayed) && Objects.equals(uuid, that.uuid);
        }

        @Override
        public int hashCode() {
            return Objects.hash(name, hours, lastPlayed, uuid);
        }
    }

    /** The library's four totals, as the main screen's card words them. */
    public static final class Totals {
        @NonNull
        public final String hours;
        @NonNull
        public final String games;
        /** "games, 11 installed". */
        @NonNull
        public final String gamesLabel;
        @NonNull
        public final String neverPlayed;
        @NonNull
        public final String thisYear;

        public Totals(@NonNull String hours, @NonNull String games, @NonNull String gamesLabel,
                      @NonNull String neverPlayed, @NonNull String thisYear) {
            this.hours = hours;
            this.games = games;
            this.gamesLabel = gamesLabel;
            this.neverPlayed = neverPlayed;
            this.thisYear = thisYear;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) {
                return true;
            }
            if (!(o instanceof Totals)) {
                return false;
            }
            Totals that = (Totals) o;
            return hours.equals(that.hours) && games.equals(that.games)
                    && gamesLabel.equals(that.gamesLabel) && neverPlayed.equals(that.neverPlayed)
                    && thisYear.equals(that.thisYear);
        }

        @Override
        public int hashCode() {
            return Objects.hash(hours, games, gamesLabel, neverPlayed, thisYear);
        }
    }

    /** Puts a page together; {@link #lists} makes it {@link Mode#FULL}. */
    public static final class Builder {
        private final int rangeIndex;
        private String previousLabel = "";
        private String currentLabel = "";
        private String nextLabel = "";
        private boolean previousEnabled;
        private boolean nextEnabled;
        private Mode mode = Mode.REMOTE_ONLY;
        private Tab tab = Tab.UNLOCKS;
        private String unlockCount = "";
        private List<UnlockRow> unlocks = Collections.emptyList();
        private String resumeCount = "";
        private List<ResumeRow> resume = Collections.emptyList();
        @Nullable
        private Totals totals;
        private List<GamepadHintsView.Hint> hints = Collections.emptyList();

        /** @param range the range asked for, one of {@link #RANGES} */
        public Builder(@NonNull String range) {
            this.rangeIndex = Math.max(0, Arrays.asList(RANGES).indexOf(range));
        }

        /** The period asked for, between its neighbours, and whether each can be stepped to. */
        @NonNull
        public Builder period(@NonNull String previous, @NonNull String current,
                              @NonNull String next, boolean previousEnabled,
                              boolean nextEnabled) {
            this.previousLabel = previous;
            this.currentLabel = current;
            this.nextLabel = next;
            this.previousEnabled = previousEnabled;
            this.nextEnabled = nextEnabled;
            return this;
        }

        /**
         * The lists, for a tracked page.
         *
         * @param tab         the tab chosen; the other is shown when this one's list is empty
         * @param unlockCount the period's unlocks, all of them ("64"), not only the rows
         */
        @NonNull
        public Builder lists(@NonNull Tab tab, @NonNull String unlockCount,
                             @NonNull List<UnlockRow> unlocks, @NonNull String resumeCount,
                             @NonNull List<ResumeRow> resume) {
            this.mode = Mode.FULL;
            this.tab = tab;
            this.unlockCount = unlockCount;
            this.unlocks = unlocks;
            this.resumeCount = resumeCount;
            this.resume = resume;
            return this;
        }

        @NonNull
        public Builder totals(@Nullable Totals totals) {
            this.totals = totals;
            return this;
        }

        @NonNull
        public Builder hints(@NonNull GamepadHintsView.Hint... hints) {
            this.hints = Arrays.asList(hints);
            return this;
        }

        @NonNull
        public StatsRemotePage build() {
            boolean full = mode == Mode.FULL;
            List<UnlockRow> u = full ? copy(unlocks) : Collections.<UnlockRow>emptyList();
            List<ResumeRow> r = full ? copy(resume) : Collections.<ResumeRow>emptyList();
            Tab shown = null;
            if (full) {
                Tab other = tab == Tab.UNLOCKS ? Tab.RESUME : Tab.UNLOCKS;
                if (!isEmpty(tab, u, r)) {
                    shown = tab;
                } else if (!isEmpty(other, u, r)) {
                    shown = other;
                }
            }
            return new StatsRemotePage(mode, rangeIndex, previousLabel, currentLabel, nextLabel,
                    previousEnabled, nextEnabled, shown, unlockCount, u, resumeCount, r, totals,
                    copy(hints), NO_STOP);
        }

        private static boolean isEmpty(Tab tab, List<UnlockRow> unlocks, List<ResumeRow> resume) {
            return tab == Tab.UNLOCKS ? unlocks.isEmpty() : resume.isEmpty();
        }

        private static <T> List<T> copy(List<T> list) {
            return Collections.unmodifiableList(new ArrayList<>(list));
        }
    }

    @NonNull
    public final Mode mode;
    /** The range asked for, as its index in {@link #RANGES}. */
    public final int rangeIndex;
    @NonNull
    public final String previousLabel;
    @NonNull
    public final String currentLabel;
    @NonNull
    public final String nextLabel;
    public final boolean previousEnabled;
    public final boolean nextEnabled;
    /** The tab drawn active; null when no tab is drawn. */
    @Nullable
    public final Tab tab;
    @NonNull
    public final String unlockCount;
    @NonNull
    public final List<UnlockRow> unlocks;
    @NonNull
    public final String resumeCount;
    @NonNull
    public final List<ResumeRow> resume;
    @Nullable
    public final Totals totals;
    @NonNull
    public final List<GamepadHintsView.Hint> hints;

    private StatsRemotePage(@NonNull Mode mode, int rangeIndex, @NonNull String previousLabel,
                            @NonNull String currentLabel, @NonNull String nextLabel,
                            boolean previousEnabled, boolean nextEnabled, @Nullable Tab tab,
                            @NonNull String unlockCount, @NonNull List<UnlockRow> unlocks,
                            @NonNull String resumeCount, @NonNull List<ResumeRow> resume,
                            @Nullable Totals totals, @NonNull List<GamepadHintsView.Hint> hints,
                            int focusedStop) {
        super(focusedStop);
        this.mode = mode;
        this.rangeIndex = rangeIndex;
        this.previousLabel = previousLabel;
        this.currentLabel = currentLabel;
        this.nextLabel = nextLabel;
        this.previousEnabled = previousEnabled;
        this.nextEnabled = nextEnabled;
        this.tab = tab;
        this.unlockCount = unlockCount;
        this.unlocks = unlocks;
        this.resumeCount = resumeCount;
        this.resume = resume;
        this.totals = totals;
        this.hints = hints;
    }

    // --- The pad's hints ---

    /**
     * The pad's shortcuts for Stats, from its state, on either screen: "A Open October" while
     * the d-pad is on a year's month that can be opened, "B Up" on a month (B climbs), the
     * bumpers stepping the range's periods, X and Y cycling the ranges.
     *
     * @param range     the range asked for
     * @param openMonth the month A would open (the screen knows: the picked bar of the year on
     *                  screen, while the chart has the d-pad), or null when A opens none
     */
    @NonNull
    public static GamepadHintsView.Hint[] hints(@NonNull Resources res, @NonNull String range,
                                                @Nullable String openMonth) {
        List<GamepadHintsView.Hint> list = new ArrayList<>(4);
        if (openMonth != null) {
            list.add(new GamepadHintsView.Hint(res.getString(R.string.stats_hint_open, openMonth),
                    "A"));
        }
        if (AppStats.RANGE_MONTH.equals(range)) {
            list.add(new GamepadHintsView.Hint(res.getString(R.string.stats_hint_up), "B"));
        }
        list.add(new GamepadHintsView.Hint(rangeWord(res, range), "LB", "RB"));
        list.add(new GamepadHintsView.Hint(res.getString(R.string.stats_hint_range), "X", "Y"));
        return list.toArray(new GamepadHintsView.Hint[0]);
    }

    /** "Week", "Month" or "Year". */
    @NonNull
    public static String rangeWord(@NonNull Resources res, @NonNull String range) {
        switch (range) {
            case AppStats.RANGE_YEAR:
                return res.getString(R.string.stats_range_year);
            case AppStats.RANGE_MONTH:
                return res.getString(R.string.stats_range_month);
            default:
                return res.getString(R.string.stats_range_week);
        }
    }

    // --- Stops ---

    public static int tabStop(@NonNull Tab tab) {
        return STOP_TAB + tab.ordinal();
    }

    /** The tab a stop names, or null. */
    @Nullable
    public static Tab tabAt(int stop) {
        int index = stop - STOP_TAB;
        Tab[] all = Tab.values();
        return index >= 0 && index < all.length ? all[index] : null;
    }

    public static int rangeStop(int index) {
        return STOP_RANGE + index;
    }

    /** The range segment's index a stop names, or -1. */
    public static int rangeAt(int stop) {
        int index = stop - STOP_RANGE;
        return index >= 0 && index < RANGES.length ? index : -1;
    }

    public static int rowStop(int index) {
        return STOP_ROW + index;
    }

    /** A row stop's index in the active tab's list, or -1 when the stop is not a row. */
    public static int rowIndex(int stop) {
        return stop >= STOP_ROW ? stop - STOP_ROW : -1;
    }

    /** The tabs drawn, in order: those whose list is not empty, none in REMOTE_ONLY. */
    @NonNull
    public List<Tab> visibleTabs() {
        List<Tab> tabs = new ArrayList<>(2);
        if (tab != null) {
            if (!unlocks.isEmpty()) {
                tabs.add(Tab.UNLOCKS);
            }
            if (!resume.isEmpty()) {
                tabs.add(Tab.RESUME);
            }
        }
        return tabs;
    }

    /** How many rows the active tab draws. */
    public int rowCount() {
        if (tab == null) {
            return 0;
        }
        return tab == Tab.UNLOCKS ? unlocks.size() : resume.size();
    }

    /** The unlock a row stop names, or null when it names none (or the tab is the other). */
    @Nullable
    public UnlockRow unlockAt(int stop) {
        int index = rowIndex(stop);
        return tab == Tab.UNLOCKS && index >= 0 && index < unlocks.size()
                ? unlocks.get(index) : null;
    }

    /** The game to pick up again a row stop names, or null. */
    @Nullable
    public ResumeRow resumeAt(int stop) {
        int index = rowIndex(stop);
        return tab == Tab.RESUME && index >= 0 && index < resume.size()
                ? resume.get(index) : null;
    }

    /** Whether the panel says there is nothing to pick up again: a full page with no list. */
    public boolean saysNothing() {
        return mode == Mode.FULL && tab == null;
    }

    /** The page's stops, row by row (see the class comment). */
    @NonNull
    public PageFocus stops() {
        List<int[]> rows = new ArrayList<>();
        List<Tab> tabs = visibleTabs();
        int[] tabRow = new int[tabs.size()];
        for (int i = 0; i < tabRow.length; i++) {
            tabRow[i] = tabStop(tabs.get(i));
        }
        rows.add(tabRow);
        for (int i = 0; i < rowCount(); i++) {
            rows.add(new int[]{rowStop(i)});
        }
        int[] ranges = new int[RANGES.length];
        for (int i = 0; i < ranges.length; i++) {
            ranges[i] = rangeStop(i);
        }
        rows.add(ranges);
        rows.add(new int[]{STOP_PREVIOUS, STOP_NEXT});
        List<Integer> disabled = new ArrayList<>(2);
        if (!previousEnabled) {
            disabled.add(STOP_PREVIOUS);
        }
        if (!nextEnabled) {
            disabled.add(STOP_NEXT);
        }
        int[] off = new int[disabled.size()];
        for (int i = 0; i < off.length; i++) {
            off[i] = disabled.get(i);
        }
        return new PageFocus(rows, off);
    }

    /**
     * Where the d-pad lands coming down from the main screen: the active tab, or the chosen
     * range when there are no tabs.
     */
    public int entryStop() {
        return tab != null ? tabStop(tab) : rangeStop(rangeIndex);
    }

    /**
     * Where the d-pad should stand on this page, coming from {@code stop} on the one before: the
     * same stop while it is still here, else its nearest kin. A period button that went disabled
     * (stepped to the bound) hands over to the other one, as on the main screen; a row past the
     * end of a shorter list to the last row; anything else that went (a tab, every row) to
     * {@link #entryStop()}. {@link #NO_STOP} stays.
     */
    public int settle(int stop) {
        if (stop == NO_STOP) {
            return NO_STOP;
        }
        PageFocus stops = stops();
        if (stops.isEnabledStop(stop)) {
            return stop;
        }
        if (stop == STOP_PREVIOUS || stop == STOP_NEXT) {
            int other = stop == STOP_PREVIOUS ? STOP_NEXT : STOP_PREVIOUS;
            return stops.isEnabledStop(other) ? other : rangeStop(rangeIndex);
        }
        if (rowIndex(stop) >= 0 && rowCount() > 0) {
            return rowStop(rowCount() - 1);
        }
        return entryStop();
    }

    // --- CompanionPage ---

    @NonNull
    @Override
    public CompanionPageRenderer renderer() {
        return StatsRemoteRenderer.INSTANCE;
    }

    @NonNull
    @Override
    public StatsRemotePage withFocus(int stop) {
        return new StatsRemotePage(mode, rangeIndex, previousLabel, currentLabel, nextLabel,
                previousEnabled, nextEnabled, tab, unlockCount, unlocks, resumeCount, resume,
                totals, hints, stop);
    }

    /** Whether {@code other} draws the same rows: the redraw a focus move does not need. */
    boolean sameRows(@Nullable StatsRemotePage other) {
        return other != null && tab == other.tab && sameList(unlocks, other.unlocks)
                && sameList(resume, other.resume);
    }

    @Override
    protected boolean sameContent(@NonNull CompanionPage other) {
        StatsRemotePage that = (StatsRemotePage) other;
        return mode == that.mode && rangeIndex == that.rangeIndex
                && previousEnabled == that.previousEnabled && nextEnabled == that.nextEnabled
                && tab == that.tab && previousLabel.equals(that.previousLabel)
                && currentLabel.equals(that.currentLabel) && nextLabel.equals(that.nextLabel)
                && unlockCount.equals(that.unlockCount) && resumeCount.equals(that.resumeCount)
                && sameList(unlocks, that.unlocks) && sameList(resume, that.resume)
                && Objects.equals(totals, that.totals) && sameList(hints, that.hints);
    }

    @Override
    protected int contentHash() {
        return Objects.hash(mode, rangeIndex, previousLabel, currentLabel, nextLabel,
                previousEnabled, nextEnabled, tab, unlockCount, unlocks, resumeCount, resume,
                totals, hints);
    }
}
