package com.limelight.stats;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.limelight.nvstream.http.GameAchievements;
import com.limelight.nvstream.http.GameStats;
import com.limelight.nvstream.http.Iso8601;

/**
 * What the host answered about one game: its play statistics and its achievements, and whether
 * either request failed. One immutable value per answer, so the page draws from one consistent
 * pair rather than from two requests that may land in either order.
 *
 * <p>Either half can be missing for good reasons (a game SuccessStory doesn't follow, a game
 * GameActivity never saw), so the {@link State} says whether there is anything to draw at all:
 *
 * <pre>
 *   play time or achievements          READY    (draw whichever there is)
 *   neither, and a request failed      FAILED   ("Couldn't reach the PC.", A retries)
 *   neither, both requests answered    NOTHING  ("No statistics or achievements … yet.")
 * </pre>
 *
 * A failed request with nothing from the other one is FAILED rather than NOTHING: "nothing yet"
 * would be a claim about the game the page cannot make while half of it went unanswered, and
 * the retry is what the user needs then. This is the old sheet's rule too.
 *
 * <h3>Read against the answer before</h3>
 *
 * The host answers 404 both for a game it has nothing on and when it cannot read its files just
 * then (while a session runs, most often), so a 404 alone is no proof there is nothing.
 * {@link #settle} reads each answer against the one before (or what the library knows):
 *
 * <pre>
 *   404 or failed, an answer before had it      the one before stays (its figures, its list);
 *                                               failed stays failed, so the page offers the
 *                                               retry over them, a 404 does not
 *   404 on the play time the library knows of   failed, "Couldn't read the statistics." (A
 *                                               retries), the achievements' 404 with it
 *   Playnite's total unchanged, a newer session the session is added to the total: Playnite
 *     recorded by GameActivity                  had not counted it yet (see unsyncedSeconds)
 * </pre>
 */
public final class GamePageData {

    public enum State {
        LOADING,
        READY,
        FAILED,
        NOTHING
    }

    private static final GamePageData LOADING =
            new GamePageData(State.LOADING, null, null, false, false, false, 0);

    @NonNull
    public final State state;
    @Nullable
    public final GameStats stats;
    @Nullable
    public final GameAchievements achievements;
    public final boolean statsFailed;
    public final boolean achievementsFailed;
    /**
     * What failed did so although the host answered: it said it has nothing on a game whose play
     * time the page knows of. "Couldn't read the statistics." rather than "Couldn't reach the
     * PC.".
     */
    public final boolean unreadable;
    /**
     * Seconds of play GameActivity recorded that Playnite's total does not count yet, added to
     * it in the figures: Playnite's total can lag behind a session that just ended.
     */
    public final long unsyncedSeconds;

    private GamePageData(@NonNull State state, @Nullable GameStats stats,
                         @Nullable GameAchievements achievements, boolean statsFailed,
                         boolean achievementsFailed, boolean unreadable, long unsyncedSeconds) {
        this.state = state;
        this.stats = stats;
        this.achievements = achievements;
        this.statsFailed = statsFailed;
        this.achievementsFailed = achievementsFailed;
        this.unreadable = unreadable;
        this.unsyncedSeconds = unsyncedSeconds;
    }

    /** Asked for, no answer yet. */
    @NonNull
    public static GamePageData loading() {
        return LOADING;
    }

    /** No way to ask the host at all (no address yet): the same as both requests failing. */
    @NonNull
    public static GamePageData unreachable() {
        return from(null, null, true, true);
    }

    /** The answer to both requests; the state follows from it (see the class comment). */
    @NonNull
    public static GamePageData from(@Nullable GameStats stats, @Nullable GameAchievements achievements,
                                    boolean statsFailed, boolean achievementsFailed) {
        return of(stats, achievements, statsFailed, achievementsFailed, false, 0);
    }

    /**
     * The answer to both requests, read against the one before (see the class comment).
     *
     * @param before    the last answer with something in it, of this page or an earlier page of
     *                  the same game; null for none
     * @param playKnown whether the library knows the game was played (Playnite's minutes in its
     *                  metadata), so a 404 on its statistics cannot mean there are none
     */
    @NonNull
    public static GamePageData settle(@Nullable GameStats stats, @Nullable GameAchievements achievements,
                                      boolean statsFailed, boolean achievementsFailed,
                                      @Nullable GamePageData before, boolean playKnown) {
        GameStats keptStats = before != null && before.hasPlay() ? before.stats : null;
        GameAchievements keptAchievements = before != null && before.hasAchievements()
                ? before.achievements : null;
        // "Nothing" on a game known to have been played: the host could not say just then.
        boolean withheld = stats == null && !statsFailed && (playKnown || keptStats != null);
        boolean unreadable = withheld && !achievementsFailed;
        long unsynced = 0;
        if (stats == null && keptStats != null) {
            stats = keptStats;
            unsynced = before.unsyncedSeconds;
        } else if (withheld) {
            statsFailed = true;
        } else if (stats != null && keptStats != null) {
            unsynced = unsynced(stats, keptStats, before.unsyncedSeconds);
        }
        if (achievements == null && keptAchievements != null) {
            achievements = keptAchievements;
        } else if (achievements == null && withheld) {
            // Its 404 came with the statistics': no telling it means "none" either.
            achievementsFailed = true;
        }
        return of(stats, achievements, statsFailed, achievementsFailed, unreadable, unsynced);
    }

    // While Playnite's total stands still, every session GameActivity recorded after the one the
    // answer before had as its last is time the total does not count yet. Playnite catching up
    // moves its total, and the extra goes.
    private static long unsynced(@NonNull GameStats now, @NonNull GameStats before, long carried) {
        if (now.getPlaytimeSeconds() != before.getPlaytimeSeconds()) {
            return 0;
        }
        long last = now.hasActivity() && now.hasLastSession()
                ? Iso8601.parseMillis(now.getLastSessionStart()) : 0;
        long previous = before.hasActivity() && before.hasLastSession()
                ? Iso8601.parseMillis(before.getLastSessionStart()) : 0;
        if (last > previous) {
            return carried + Math.max(0, now.getLastSessionSeconds());
        }
        return carried;
    }

    private static GamePageData of(@Nullable GameStats stats, @Nullable GameAchievements achievements,
                                   boolean statsFailed, boolean achievementsFailed,
                                   boolean unreadable, long unsyncedSeconds) {
        State state;
        if (hasPlay(stats) || hasAchievements(achievements)) {
            state = State.READY;
        } else if (statsFailed || achievementsFailed) {
            state = State.FAILED;
        } else {
            state = State.NOTHING;
        }
        return new GamePageData(state, stats, achievements, statsFailed, achievementsFailed,
                unreadable, unsyncedSeconds);
    }

    /** Whether there is play time to show figures for. */
    public boolean hasPlay() {
        return hasPlay(stats);
    }

    /** Whether SuccessStory follows the game: at least one achievement to list. */
    public boolean hasAchievements() {
        return hasAchievements(achievements);
    }

    private static boolean hasPlay(@Nullable GameStats stats) {
        return stats != null && stats.getPlaytimeSeconds() > 0;
    }

    private static boolean hasAchievements(@Nullable GameAchievements achievements) {
        return achievements != null && achievements.getTotal() > 0;
    }
}
