package com.limelight.stats;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.limelight.nvstream.http.GameAchievements;
import com.limelight.nvstream.http.GameStats;

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
 */
public final class GamePageData {

    public enum State {
        LOADING,
        READY,
        FAILED,
        NOTHING
    }

    private static final GamePageData LOADING =
            new GamePageData(State.LOADING, null, null, false, false);

    @NonNull
    public final State state;
    @Nullable
    public final GameStats stats;
    @Nullable
    public final GameAchievements achievements;
    public final boolean statsFailed;
    public final boolean achievementsFailed;

    private GamePageData(@NonNull State state, @Nullable GameStats stats,
                         @Nullable GameAchievements achievements, boolean statsFailed,
                         boolean achievementsFailed) {
        this.state = state;
        this.stats = stats;
        this.achievements = achievements;
        this.statsFailed = statsFailed;
        this.achievementsFailed = achievementsFailed;
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
        State state;
        if (hasPlay(stats) || hasAchievements(achievements)) {
            state = State.READY;
        } else if (statsFailed || achievementsFailed) {
            state = State.FAILED;
        } else {
            state = State.NOTHING;
        }
        return new GamePageData(state, stats, achievements, statsFailed, achievementsFailed);
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
