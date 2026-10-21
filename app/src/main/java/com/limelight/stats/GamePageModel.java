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
 * do. The total is always Playnite's, and the two figures beside it must come from the same
 * source: sessions only while GameActivity saw (nearly) all of that time, Playnite's launches
 * otherwise. A game GameActivity began following late would else read "6h 50m · 21 sessions ·
 * 2 min per session", the total from one source and the sessions from the other. The bars are
 * GameActivity's weeks and say so ("Last 12 weeks"), so they stay whenever there are sessions:
 *
 * <pre>
 *   sessions &gt; 0, tracked time covers the total   total · sessions · per session   + bars
 *   sessions &gt; 0, tracked time falls short         total · launches · per launch    + bars
 *   no sessions                                      total · launches · per launch
 *   no play time                                     no figures at all
 * </pre>
 *
 * (per launch is hidden at 0 launches; with sessions but no launch counted, the sessions stay.)
 *
 * <h3>Buttons and chips</h3>
 *
 * The chips are the actions sheet's rows ({@link GameActionsSheet#itemsFor}) minus what the
 * buttons already do (start, resume, quit) and minus the row that opened this page (Stats), in
 * the sheet's order. So the sheet's rules carry over unchanged: Hide is missing while the game
 * runs, the companion app only where there is a second screen. The start in the other display
 * stays a chip (Play starts in the preferred one); a start in a virtual display only when the
 * host has one to give. A game the library does not list (a statistics row for a game outside
 * the catalogue) gets neither buttons nor chips: there is nothing to start and nothing to act on.
 *
 * <h3>One screen or two</h3>
 *
 * Where the lower half goes ({@link #bottom}) follows whether the companion panel is showing,
 * and is decided again each time it comes or goes, from the same answer: the page keeps its
 * data, filter and focus, only the lower half moves.
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

    /** What the companion panel shows of the page: nothing (no panel), or one of three pages. */
    public enum Bottom {
        /** No panel: the achievements are inline, under the game, and the chips stay on top. */
        NONE,
        /** Asked, not answered yet, or failed: one status row, the retry when it failed. */
        STATUS,
        /** The game's achievements. */
        LIST,
        /** No achievements tracked: the actions as big rows, and the chips leave the top. */
        ACTIONS
    }

    // A tracked total this close to Playnite's is the same time: the host rounds the average.
    private static final double TRACKED_SHARE = 0.9;

    private GamePageModel() {
    }

    /**
     * The panel's page for this answer, or {@link Bottom#NONE} while no panel is showing.
     *
     * @param panelShowing {@code CompanionDisplayManager.panelShowing()} now
     */
    @NonNull
    public static Bottom bottom(boolean panelShowing, @Nullable GamePageData d) {
        if (!panelShowing) {
            return Bottom.NONE;
        }
        if (d == null || d.state == GamePageData.State.LOADING) {
            return Bottom.STATUS;
        }
        if (d.hasAchievements()) {
            return Bottom.LIST;
        }
        // Not reached for the achievements: the retry, not a claim that there are none.
        if (d.state == GamePageData.State.FAILED || d.achievementsFailed) {
            return Bottom.STATUS;
        }
        return Bottom.ACTIONS;
    }

    /** Whether the chips are drawn on the page itself: not while the panel lists them as rows. */
    public static boolean chipsOnTop(@NonNull Bottom bottom) {
        return bottom != Bottom.ACTIONS;
    }

    /** Whether the achievements block is drawn inline: only with no panel to draw them. */
    public static boolean achievementsInline(@NonNull Bottom bottom) {
        return bottom == Bottom.NONE;
    }

    /** The figures for a game's statistics, or null when there is no play time to show. */
    @Nullable
    public static Figures figures(@Nullable GameStats s) {
        if (s == null || s.getPlaytimeSeconds() <= 0) {
            return null;
        }
        long total = s.getPlaytimeSeconds();
        long launches = s.getPlayCount();
        // Without GameActivity the host reports no sessions; a stray count is not trusted either.
        boolean sessions = s.hasActivity() && s.getSessions() > 0;
        long[] weeks = sessions ? s.getWeeks() : null;
        if (sessions && (launches <= 0 || tracksTheTotal(s))) {
            return new Figures(total, Unit.SESSIONS, s.getSessions(), s.getAverageSeconds(), weeks);
        }
        return new Figures(total, Unit.LAUNCHES, launches,
                launches > 0 ? total / launches : -1, weeks);
    }

    // Whether GameActivity's sessions add up to (nearly) Playnite's total: then counting them
    // beside it, and dividing by them, describes the same time.
    private static boolean tracksTheTotal(@NonNull GameStats s) {
        double tracked = (double) s.getSessions() * s.getAverageSeconds();
        return tracked >= TRACKED_SHARE * s.getPlaytimeSeconds();
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

    /**
     * The chips, in the sheet's order; none for a game the library does not list.
     *
     * @param virtualDisplay whether the host can start a game in a virtual display, without
     *                       which "Start in virtual display" is not offered
     */
    @NonNull
    public static List<GameActionsSheet.Item> chips(@Nullable LibraryApp app,
                                                   @NonNull GameActionsSheet.State state,
                                                   boolean virtualDisplay) {
        if (app == null) {
            return Collections.emptyList();
        }
        List<GameActionsSheet.Item> chips = new ArrayList<>();
        for (GameActionsSheet.Item item : GameActionsSheet.itemsFor(app, state)) {
            if (item == GameActionsSheet.Item.START_VIRTUAL_DISPLAY && !virtualDisplay) {
                continue;
            }
            if (!isPrimary(item) && item != GameActionsSheet.Item.STATS) {
                chips.add(item);
            }
        }
        return Collections.unmodifiableList(chips);
    }

    /**
     * The line of metadata under the title: "2023 · RPG · Sabotage Studio", the year, up to
     * three genres and the first developer, only the parts the host has. Empty for none. No
     * score: a bare percentage there reads as nothing in particular.
     */
    @NonNull
    public static String metaLine(@Nullable String year, @Nullable List<String> genres,
                                  @Nullable List<String> developers) {
        List<String> parts = new ArrayList<>(3);
        if (year != null && !year.isEmpty()) {
            parts.add(year);
        }
        if (genres != null && !genres.isEmpty()) {
            parts.add(join(", ", genres.subList(0, Math.min(3, genres.size()))));
        }
        if (developers != null && !developers.isEmpty() && !developers.get(0).isEmpty()) {
            parts.add(developers.get(0));
        }
        return join(" · ", parts);
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

    // What the buttons beside the cover already offer, in one form or another. A start in the
    // other display is not: Play starts in the preferred one, so that stays a chip.
    private static boolean isPrimary(GameActionsSheet.Item item) {
        switch (item) {
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
