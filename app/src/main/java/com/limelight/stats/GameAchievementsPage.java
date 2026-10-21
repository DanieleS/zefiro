package com.limelight.stats;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.limelight.companion.CompanionPage;
import com.limelight.companion.CompanionPageRenderer;
import com.limelight.main.PageFocus;
import com.limelight.nvstream.http.Achievement;
import com.limelight.nvstream.http.GameAchievements;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * A game page's lower half on the companion panel: the game's achievements, as the
 * {@link AchievementsBlockView} draws them inline (count, meter, All / Unlocked / Locked, the
 * rows), or, while there is no list to show yet, one row saying why (loading, or the PC could
 * not be reached, which retries).
 *
 * <h3>Stops</h3>
 *
 * <pre>
 *   LIST     row 0   the filter's segments       0  1  2      (STOP_FILTER + ordinal)
 *            row 1…  one per achievement shown   100, 101, …  (STOP_ROW + position)
 *   LOADING  one row                             50           (STOP_STATUS)
 *   FAILED   one row, A or a tap retries         50           (STOP_STATUS)
 * </pre>
 *
 * Row stops number the list as the filter leaves it, so the same stop is another achievement
 * under another filter: a filter change moves a d-pad that was on a row up to the filter
 * ({@link #focusAfterFilter}).
 *
 * <p>A value, like every {@link CompanionPage}: the revealed ids are copied, the answer and the
 * session are compared by identity (one answer is one object for as long as it stands), and
 * {@code nowMillis} is the moment NEW is judged against, fixed when the answer came, so a page
 * published again for a focus move is equal to the last one but for its stop.
 */
public final class GameAchievementsPage extends CompanionPage {

    /** What the page shows. */
    public enum Mode { LIST, LOADING, FAILED }

    /** The first filter segment's stop; the others follow by ordinal. */
    public static final int STOP_FILTER = 0;
    /** The status row's stop, while loading or failed. */
    public static final int STOP_STATUS = 50;
    /** The first row's stop; the others follow by position in the filtered list. */
    public static final int STOP_ROW = 100;

    @NonNull
    public final String title;
    @NonNull
    public final Mode mode;
    @Nullable
    public final GameAchievements data;
    @NonNull
    public final AchievementFilter.Filter filter;
    @NonNull
    public final Set<String> revealed;
    @Nullable
    public final HostSession session;
    public final long nowMillis;
    /** FAILED although the host answered: "Couldn't read the statistics." (GamePageData#unreadable). */
    public final boolean unreadable;
    // What the filter keeps, in the host's order: the rows, and what their stops name.
    @NonNull
    private final List<Achievement> shown;

    private GameAchievementsPage(@NonNull String title, @NonNull Mode mode,
                                 @Nullable GameAchievements data,
                                 @NonNull AchievementFilter.Filter filter,
                                 @NonNull Set<String> revealed, @Nullable HostSession session,
                                 long nowMillis, boolean unreadable, int focusedStop) {
        super(focusedStop);
        this.title = title;
        this.mode = mode;
        this.data = data;
        this.filter = filter;
        this.revealed = revealed;
        this.session = session;
        this.nowMillis = nowMillis;
        this.unreadable = unreadable;
        this.shown = data != null ? AchievementFilter.apply(data.getItems(), filter)
                : Collections.<Achievement>emptyList();
    }

    /**
     * The list.
     *
     * @param revealed  the hidden ones the user revealed, by id; copied
     * @param session   where the icons come from
     * @param nowMillis the time NEW is judged against
     */
    @NonNull
    public static GameAchievementsPage list(@NonNull String title, @NonNull GameAchievements data,
                                            @NonNull AchievementFilter.Filter filter,
                                            @NonNull Set<String> revealed,
                                            @Nullable HostSession session, long nowMillis) {
        return new GameAchievementsPage(title, Mode.LIST, data, filter,
                Collections.unmodifiableSet(new HashSet<>(revealed)), session, nowMillis, false,
                NO_STOP);
    }

    /** "Loading…", while the host has not answered. */
    @NonNull
    public static GameAchievementsPage loading(@NonNull String title) {
        return status(title, Mode.LOADING, false);
    }

    /** "Couldn't reach the PC.", for the achievements; A or a tap asks again. */
    @NonNull
    public static GameAchievementsPage failed(@NonNull String title) {
        return failed(title, false);
    }

    /**
     * As {@link #failed(String)}; "Couldn't read the statistics." instead when the host answered
     * but could not say ({@code unreadable}).
     */
    @NonNull
    public static GameAchievementsPage failed(@NonNull String title, boolean unreadable) {
        return status(title, Mode.FAILED, unreadable);
    }

    private static GameAchievementsPage status(String title, Mode mode, boolean unreadable) {
        return new GameAchievementsPage(title, mode, null, AchievementFilter.Filter.ALL,
                Collections.<String>emptySet(), null, 0, unreadable, NO_STOP);
    }

    // --- Stops ---

    /** The stop of a filter's segment. */
    public static int filterStop(@NonNull AchievementFilter.Filter filter) {
        return STOP_FILTER + filter.ordinal();
    }

    /** The filter a segment's stop chooses, or null when the stop is not a segment. */
    @Nullable
    public static AchievementFilter.Filter filterAt(int stop) {
        int index = stop - STOP_FILTER;
        AchievementFilter.Filter[] all = AchievementFilter.Filter.values();
        return index >= 0 && index < all.length ? all[index] : null;
    }

    /** The stop of the row at this position in the filtered list. */
    public static int rowStop(int position) {
        return STOP_ROW + position;
    }

    /** A row stop's position in the filtered list, or -1 when the stop is not a row. */
    public static int rowPosition(int stop) {
        return stop >= STOP_ROW ? stop - STOP_ROW : -1;
    }

    /**
     * Where the d-pad stands after the filter changed: a segment it was on stays (A on a
     * segment chooses it, and the ring stays on what was pressed); a row, which now names
     * another achievement or none, moves up to the chosen segment; off the page stays off.
     */
    public static int focusAfterFilter(int stop, @NonNull AchievementFilter.Filter chosen) {
        if (stop == NO_STOP || filterAt(stop) != null) {
            return stop;
        }
        return rowPosition(stop) >= 0 ? filterStop(chosen) : stop;
    }

    /** The page's stops, row by row (see the class comment). */
    @NonNull
    public PageFocus stops() {
        List<int[]> rows = new ArrayList<>();
        switch (mode) {
            case LIST:
                AchievementFilter.Filter[] filters = AchievementFilter.Filter.values();
                int[] segments = new int[filters.length];
                for (int i = 0; i < filters.length; i++) {
                    segments[i] = filterStop(filters[i]);
                }
                rows.add(segments);
                for (int i = 0; i < shown.size(); i++) {
                    rows.add(new int[]{rowStop(i)});
                }
                break;
            case LOADING:
            case FAILED:
            default:
                // A stop while loading too: a retry pressed on it keeps the d-pad there while
                // the next answer comes, as the main screen's status row does.
                rows.add(new int[]{STOP_STATUS});
                break;
        }
        return new PageFocus(rows);
    }

    /**
     * Where R2 lands: the first row, or the chosen segment when the filter keeps none (as the
     * inline block's first stop), or the status row.
     */
    public int entryStop() {
        if (mode != Mode.LIST) {
            return STOP_STATUS;
        }
        return shown.isEmpty() ? filterStop(filter) : rowStop(0);
    }

    /** The achievements the filter keeps, in the host's order. Unmodifiable. */
    @NonNull
    public List<Achievement> shown() {
        return shown;
    }

    /** The achievement a row stop names, or null when the stop is not one of the rows. */
    @Nullable
    public Achievement rowAt(int stop) {
        int position = rowPosition(stop);
        return position >= 0 && position < shown.size() ? shown.get(position) : null;
    }

    /** This page under another filter, the d-pad moved as {@link #focusAfterFilter} says. */
    @NonNull
    public GameAchievementsPage withFilter(@NonNull AchievementFilter.Filter chosen) {
        return new GameAchievementsPage(title, mode, data, chosen, revealed, session, nowMillis,
                unreadable, focusAfterFilter(focusedStop(), chosen));
    }

    // --- CompanionPage ---

    @NonNull
    @Override
    public CompanionPageRenderer renderer() {
        return GameAchievementsRenderer.INSTANCE;
    }

    @NonNull
    @Override
    public GameAchievementsPage withFocus(int stop) {
        return new GameAchievementsPage(title, mode, data, filter, revealed, session, nowMillis,
                unreadable, stop);
    }

    @Override
    protected boolean sameContent(@NonNull CompanionPage other) {
        GameAchievementsPage that = (GameAchievementsPage) other;
        return mode == that.mode && filter == that.filter && nowMillis == that.nowMillis
                && unreadable == that.unreadable && data == that.data && session == that.session && title.equals(that.title)
                && revealed.equals(that.revealed);
    }

    @Override
    protected int contentHash() {
        return Objects.hash(title, mode, System.identityHashCode(data), filter, revealed,
                System.identityHashCode(session), nowMillis, unreadable);
    }
}
