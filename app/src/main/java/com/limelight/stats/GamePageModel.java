package com.limelight.stats;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.limelight.library.GameActionsSheet;
import com.limelight.library.LibraryApp;
import com.limelight.nvstream.http.GameStats;
import com.limelight.nvstream.http.Iso8601;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * The decisions behind a game's page, made from the data alone so they can be tested without a
 * screen: which figures to show and in which unit, whether the twelve-week bars appear, how the
 * last-played line reads, which buttons and which chips the game gets.
 *
 * <h3>Figures</h3>
 *
 * Sessions exist only where the GameActivity extension recorded them; Playnite's totals always
 * do. So the figures count sessions when there are some, and fall back to Playnite's launches
 * otherwise, without the bars (there are no weeks without sessions):
 *
 * <pre>
 *   activity and sessions &gt; 0   total · sessions · per session   + 12-week bars
 *   otherwise                    total · launches · per launch    (per launch hidden at 0)
 *   no play time                 no figures at all
 * </pre>
 *
 * <h3>Buttons and chips</h3>
 *
 * The chips are the actions sheet's rows ({@link GameActionsSheet#itemsFor}) minus what the
 * buttons already do (start, resume, quit) and minus the row that opened this page (Stats), in
 * the sheet's order. So the sheet's rules carry over unchanged: Hide is missing while the game
 * runs, the companion app only where there is a second screen. A game the library does not list
 * (a statistics row for a game outside the catalogue) gets neither buttons nor chips: there is
 * nothing to start and nothing to act on.
 */
public final class GamePageModel {

    /** What the second and third figures count. */
    public enum Unit {
        SESSIONS,
        LAUNCHES
    }

    /** The three figures and the bars, or the lack of them. */
    public static final class Figures {
        public final long totalSeconds;
        public final Unit unit;
        public final long count;
        /** Per session or per launch; -1 when there is nothing to divide by (0 launches). */
        public final long averageSeconds;
        /** The twelve weeks, oldest first, this week last; null when the bars are not shown. */
        @Nullable
        public final long[] weeks;

        Figures(long totalSeconds, Unit unit, long count, long averageSeconds, @Nullable long[] weeks) {
            this.totalSeconds = totalSeconds;
            this.unit = unit;
            this.count = count;
            this.averageSeconds = averageSeconds;
            this.weeks = weeks;
        }

        public boolean showsBars() {
            return weeks != null;
        }

        /** Seconds played this week; 0 when the bars are not shown. */
        public long thisWeekSeconds() {
            return weeks == null || weeks.length == 0 ? 0 : weeks[weeks.length - 1];
        }
    }

    /** How long ago the game was last played, in the words the line uses. */
    public enum Day {
        TODAY,
        YESTERDAY,
        /** 2 to 6 days ago: "3 days ago". */
        DAYS_AGO,
        /** A week or more: the date, with the year once it is that old. */
        DATED
    }

    /** When the game was last played, and for how long when that is known. */
    public static final class LastPlayed {
        public final Day day;
        public final int days;
        public final long millis;
        /** The last session's length; -1 for Playnite's last activity, which has none. */
        public final long sessionSeconds;

        LastPlayed(Day day, int days, long millis, long sessionSeconds) {
            this.day = day;
            this.days = days;
            this.millis = millis;
            this.sessionSeconds = sessionSeconds;
        }

        public boolean hasDuration() {
            return sessionSeconds >= 0;
        }

        /** A date old enough to name its year (as the old sheet's rule: past 300 days). */
        public boolean withYear() {
            return days > 300;
        }
    }

    /** The words of the last-played line; resources in production, plain text in a test. */
    public interface Words {
        String today();

        String yesterday();

        String daysAgo(int days);

        String date(long millis, boolean withYear);

        String duration(long seconds);

        /** "Last played yesterday". */
        String lastPlayed(String day);

        /** "Last played yesterday, for 2h 05m". */
        String lastPlayedFor(String day, String duration);

        /** While the game runs, the session recorded last is the one before this: say so. */
        String sessionBefore(String day, String duration);
    }

    /** The buttons beside the cover. */
    public enum Primary {
        /** Not running here: Play, which taps as a tile would (another game running asks first). */
        PLAY,
        /** Running here: Resume, and Quit game beside it. */
        RESUME_QUIT,
        /** Not in the library: nothing to start. */
        NONE
    }

    private GamePageModel() {
    }

    /** The figures for a game's statistics, or null when there is no play time to show. */
    @Nullable
    public static Figures figures(@Nullable GameStats s) {
        if (s == null || s.getPlaytimeSeconds() <= 0) {
            return null;
        }
        // Without GameActivity the host reports no sessions; a stray count is not trusted either.
        if (s.hasActivity() && s.getSessions() > 0) {
            return new Figures(s.getPlaytimeSeconds(), Unit.SESSIONS, s.getSessions(),
                    s.getAverageSeconds(), s.getWeeks());
        }
        long launches = s.getPlayCount();
        return new Figures(s.getPlaytimeSeconds(), Unit.LAUNCHES, launches,
                launches > 0 ? s.getPlaytimeSeconds() / launches : -1, null);
    }

    /**
     * When the game was last played: GameActivity's last session, with its length, or Playnite's
     * last activity, without one. Null when neither has a date.
     */
    @Nullable
    public static LastPlayed lastPlayed(@Nullable GameStats s, long nowMillis) {
        if (s == null) {
            return null;
        }
        boolean session = s.hasActivity() && s.hasLastSession();
        long millis = Iso8601.parseMillis(session ? s.getLastSessionStart() : s.getLastActivity());
        if (millis == 0) {
            return null;
        }
        int days = StatsFormat.daysAgo(millis, nowMillis);
        Day day;
        if (days <= 0) {
            // A host clock running ahead can date it tomorrow; it is today all the same.
            day = Day.TODAY;
        } else if (days == 1) {
            day = Day.YESTERDAY;
        } else if (days < 7) {
            day = Day.DAYS_AGO;
        } else {
            day = Day.DATED;
        }
        return new LastPlayed(day, days, millis, session ? s.getLastSessionSeconds() : -1);
    }

    /**
     * The last-played line, worded as the old sheet worded it.
     *
     * @param running whether the game is running now, which makes the recorded session the one
     *                before this
     */
    @NonNull
    public static String lastPlayedLine(@NonNull LastPlayed last, boolean running, @NonNull Words words) {
        String day;
        switch (last.day) {
            case TODAY:
                day = words.today();
                break;
            case YESTERDAY:
                day = words.yesterday();
                break;
            case DAYS_AGO:
                day = words.daysAgo(last.days);
                break;
            case DATED:
            default:
                day = words.date(last.millis, last.withYear());
                break;
        }
        if (!last.hasDuration()) {
            return words.lastPlayed(day);
        }
        String duration = words.duration(last.sessionSeconds);
        return running ? words.sessionBefore(day, duration) : words.lastPlayedFor(day, duration);
    }

    /** The buttons for the game as the library has it; null when the library does not list it. */
    @NonNull
    public static Primary primary(@Nullable LibraryApp app) {
        if (app == null) {
            return Primary.NONE;
        }
        return app.running ? Primary.RESUME_QUIT : Primary.PLAY;
    }

    /** The chips, in the sheet's order; none for a game the library does not list. */
    @NonNull
    public static List<GameActionsSheet.Item> chips(@Nullable LibraryApp app,
                                                   @NonNull GameActionsSheet.State state) {
        if (app == null) {
            return Collections.emptyList();
        }
        List<GameActionsSheet.Item> chips = new ArrayList<>();
        for (GameActionsSheet.Item item : GameActionsSheet.itemsFor(app, state)) {
            if (!isPrimary(item) && item != GameActionsSheet.Item.STATS) {
                chips.add(item);
            }
        }
        return Collections.unmodifiableList(chips);
    }

    // What the buttons beside the cover already offer, in one form or another.
    private static boolean isPrimary(GameActionsSheet.Item item) {
        switch (item) {
            case START_PRIMARY_DISPLAY:
            case START_VIRTUAL_DISPLAY:
            case RESUME:
            case QUIT:
            case QUIT_AND_START:
            case QUIT_AND_START_PRIMARY_DISPLAY:
            case QUIT_AND_START_VIRTUAL_DISPLAY:
                return true;
            default:
                return false;
        }
    }
}
