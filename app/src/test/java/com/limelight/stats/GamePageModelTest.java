package com.limelight.stats;

import com.limelight.library.GameActionsSheet.Item;
import com.limelight.library.GameActionsSheet.State;
import com.limelight.library.LibraryApp;
import com.limelight.nvstream.http.GameStats;
import com.limelight.nvstream.http.NvApp;

import org.json.JSONArray;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.TimeZone;

import static com.limelight.nvstream.http.TestGameStats.achievements;
import static com.limelight.nvstream.http.TestGameStats.json;
import static com.limelight.nvstream.http.TestGameStats.stats;
import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

/**
 * A game page's decisions: figures in sessions or in launches, the bars only with sessions, the
 * last-played wording, and the buttons and chips the game gets.
 */
// Robolectric for org.json, which the plain unit-test android.jar only stubs.
@Config(sdk = {33})
@RunWith(RobolectricTestRunner.class)
public class GamePageModelTest {
    // 2026-10-08T12:00:00Z
    private static final long NOW = 1_791_460_800_000L;

    private TimeZone savedZone;

    // Days are counted in the device's zone; pinned, so "yesterday" is the same everywhere.
    @Before
    public void pinZone() {
        savedZone = TimeZone.getDefault();
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"));
    }

    @After
    public void restoreZone() {
        TimeZone.setDefault(savedZone);
    }

    private static final GamePageModel.Words WORDS = new GamePageModel.Words() {
        @Override
        public String today() {
            return "today";
        }

        @Override
        public String yesterday() {
            return "yesterday";
        }

        @Override
        public String daysAgo(int days) {
            return days + " days ago";
        }

        @Override
        public String date(long millis, boolean withYear) {
            return "date(" + millis + (withYear ? ", year" : "") + ")";
        }

        @Override
        public String duration(long seconds) {
            return seconds + "s";
        }

        @Override
        public String lastPlayed(String day) {
            return "Last played " + day;
        }

        @Override
        public String lastPlayedFor(String day, String duration) {
            return "Last played " + day + ", for " + duration;
        }

        @Override
        public String sessionBefore(String day, String duration) {
            return "session before this: " + day + ", " + duration;
        }
    };

    private static GameStats withSessions() {
        long[] weeks = {0, 45, 120, 210, 95, 0, 180, 300, 260, 140, 320, 250};
        JSONArray array = new JSONArray();
        for (long w : weeks) {
            array.put(w);
        }
        return stats(json("activity", true, "playtime_seconds", 138_000L, "play_count", 30,
                "sessions", 27, "average_seconds", 5_100L, "weeks", array,
                "last_session", json("start", "2026-10-07T20:00:00Z", "seconds", 7_500L)));
    }

    private static GameStats launchesOnly(long playCount, String lastActivity) {
        return stats(json("activity", false, "playtime_seconds", 147_900L,
                "play_count", playCount, "last_activity", lastActivity));
    }

    private static GameStats sessionOn(String start) {
        return stats(json("activity", true, "playtime_seconds", 3_600L, "sessions", 1,
                "average_seconds", 3_600L,
                "last_session", json("start", start, "seconds", 3_600L)));
    }

    private static LibraryApp game(int id, boolean running, boolean hidden) {
        return new LibraryApp(new NvApp("Game " + id, "uuid-" + id, id, false), running, hidden);
    }

    private static State state(int running, boolean companion) {
        return new State(running, false, companion, true, true);
    }

    // --- Figures ---

    @Test
    public void sessions_countSessions_withBars() {
        GamePageModel.Figures f = GamePageModel.figures(withSessions());

        assertNotNull(f);
        assertEquals(GamePageModel.Unit.SESSIONS, f.unit);
        assertEquals(138_000L, f.totalSeconds);
        assertEquals(27, f.count);
        assertEquals(5_100L, f.averageSeconds);
        assertTrue(f.showsBars());
        assertEquals(12, f.weeks.length);
        assertEquals(250, f.thisWeekSeconds());
    }

    @Test
    public void noSessions_countLaunches_withoutBars() {
        GamePageModel.Figures f = GamePageModel.figures(launchesOnly(16, null));

        assertNotNull(f);
        assertEquals(GamePageModel.Unit.LAUNCHES, f.unit);
        assertEquals(16, f.count);
        assertEquals(147_900L / 16, f.averageSeconds);
        assertFalse(f.showsBars());
        assertEquals(0, f.thisWeekSeconds());
    }

    @Test
    public void aStraySessionCountWithoutActivity_isNotTrusted() {
        GameStats s = stats(json("activity", false, "playtime_seconds", 3_600L,
                "play_count", 2, "sessions", 9));
        assertEquals(GamePageModel.Unit.LAUNCHES, GamePageModel.figures(s).unit);
    }

    @Test
    public void sessionsCoveringOnlyPartOfTheTotal_countLaunches_keepingTheBars() {
        // Sea of Stars: 6h 50m in Playnite, 21 sessions of 2 min since GameActivity came.
        GameStats s = stats(json("activity", true, "playtime_seconds", 24_600L, "play_count", 40,
                "sessions", 21, "average_seconds", 120L, "weeks", new JSONArray()));
        GamePageModel.Figures f = GamePageModel.figures(s);

        assertEquals(GamePageModel.Unit.LAUNCHES, f.unit);
        assertEquals(24_600L, f.totalSeconds);
        assertEquals(40, f.count);
        assertEquals(24_600L / 40, f.averageSeconds);
        assertTrue(f.showsBars());
    }

    @Test
    public void sessionsWithoutAnyLaunchCounted_stayInSessions() {
        GameStats s = stats(json("activity", true, "playtime_seconds", 24_600L, "play_count", 0,
                "sessions", 21, "average_seconds", 120L));
        assertEquals(GamePageModel.Unit.SESSIONS, GamePageModel.figures(s).unit);
    }

    @Test
    public void noLaunches_hidesTheAverage() {
        GamePageModel.Figures f = GamePageModel.figures(launchesOnly(0, null));
        assertEquals(-1, f.averageSeconds);
    }

    @Test
    public void noPlayTime_noFigures() {
        assertNull(GamePageModel.figures(null));
        assertNull(GamePageModel.figures(stats(json("playtime_seconds", 0L))));
    }

    // --- Last played ---

    @Test
    public void lastPlayed_today() {
        GamePageModel.LastPlayed last = GamePageModel.lastPlayed(sessionOn("2026-10-08T09:00:00Z"), NOW);
        assertEquals(GamePageModel.Day.TODAY, last.day);
        assertEquals("Last played today, for 3600s", GamePageModel.lastPlayedLine(last, false, WORDS));
    }

    @Test
    public void lastPlayed_yesterday_whileRunning_isTheSessionBeforeThis() {
        GamePageModel.LastPlayed last = GamePageModel.lastPlayed(withSessions(), NOW);
        assertEquals(GamePageModel.Day.YESTERDAY, last.day);
        assertEquals("session before this: yesterday, 7500s",
                GamePageModel.lastPlayedLine(last, true, WORDS));
        assertEquals("Last played yesterday, for 7500s",
                GamePageModel.lastPlayedLine(last, false, WORDS));
    }

    @Test
    public void lastPlayed_underAWeek_countsDays() {
        GamePageModel.LastPlayed last = GamePageModel.lastPlayed(sessionOn("2026-10-05T22:00:00Z"), NOW);
        assertEquals(GamePageModel.Day.DAYS_AGO, last.day);
        assertEquals(3, last.days);
        assertEquals("Last played 3 days ago, for 3600s", GamePageModel.lastPlayedLine(last, false, WORDS));
    }

    @Test
    public void lastPlayed_aWeekOrMore_isDated_withTheYearOnlyWhenOld() {
        GamePageModel.LastPlayed recent = GamePageModel.lastPlayed(sessionOn("2026-09-20T10:00:00Z"), NOW);
        assertEquals(GamePageModel.Day.DATED, recent.day);
        assertFalse(recent.withYear());
        assertTrue(GamePageModel.lastPlayedLine(recent, false, WORDS).startsWith("Last played date("));

        GamePageModel.LastPlayed old = GamePageModel.lastPlayed(sessionOn("2025-08-01T10:00:00Z"), NOW);
        assertTrue(old.withYear());
        assertTrue(GamePageModel.lastPlayedLine(old, false, WORDS).contains(", year)"));
    }

    @Test
    public void lastPlayed_fromPlaynite_hasNoDuration() {
        GamePageModel.LastPlayed last = GamePageModel.lastPlayed(
                launchesOnly(16, "2026-10-05T18:00:00Z"), NOW);
        assertFalse(last.hasDuration());
        assertEquals("Last played 3 days ago", GamePageModel.lastPlayedLine(last, true, WORDS));
    }

    @Test
    public void lastPlayed_aSessionOfNoMinutes_hasNoDuration() {
        // Minecraft Dungeons: GameActivity's one session lasted seconds, Playnite counted 13 min.
        GameStats s = stats(json("activity", true, "playtime_seconds", 780L, "play_count", 1,
                "sessions", 1, "average_seconds", 20L,
                "last_session", json("start", "2026-10-05T22:00:00Z", "seconds", 20L)));
        GamePageModel.LastPlayed last = GamePageModel.lastPlayed(s, NOW);
        assertFalse(last.hasDuration());
        assertEquals("Last played 3 days ago", GamePageModel.lastPlayedLine(last, false, WORDS));
    }

    @Test
    public void lastPlayed_absentWithoutADate() {
        assertNull(GamePageModel.lastPlayed(launchesOnly(16, null), NOW));
        assertNull(GamePageModel.lastPlayed(null, NOW));
    }

    // --- Buttons and chips ---

    @Test
    public void primary_followsTheLibrary() {
        assertEquals(GamePageModel.Primary.PLAY, GamePageModel.primary(game(1, false, false)));
        assertEquals(GamePageModel.Primary.RESUME_QUIT, GamePageModel.primary(game(1, true, false)));
        assertEquals(GamePageModel.Primary.NONE, GamePageModel.primary(null));
    }

    @Test
    public void chips_areTheSheetsRows_minusWhatTheButtonsDo_andStats() {
        List<Item> idle = GamePageModel.chips(game(1, false, false), state(0, true), true);
        assertEquals(Arrays.asList(Item.START_VIRTUAL_DISPLAY, Item.HIDE, Item.COMPANION_APP,
                Item.DETAILS, Item.PIN_SHORTCUT, Item.EXPORT_LAUNCHER), idle);

        List<Item> another = GamePageModel.chips(game(1, false, false), state(2, false), true);
        assertArrayEquals(new Item[]{Item.HIDE, Item.DETAILS, Item.PIN_SHORTCUT,
                Item.EXPORT_LAUNCHER}, another.toArray());
    }

    @Test
    public void chips_offerAVirtualDisplay_onlyWhereTheHostHasOne() {
        List<Item> without = GamePageModel.chips(game(1, false, false), state(0, false), false);
        assertFalse(without.contains(Item.START_VIRTUAL_DISPLAY));
        assertEquals(Item.HIDE, without.get(0));

        // Preferring the virtual display, Play starts there: the chip is the primary display.
        State preferVirtual = new State(0, true, false, true, true);
        List<Item> other = GamePageModel.chips(game(1, false, false), preferVirtual, false);
        assertEquals(Item.START_PRIMARY_DISPLAY, other.get(0));
    }

    @Test
    public void chips_haveNoHide_whileTheGameRuns() {
        List<Item> running = GamePageModel.chips(game(1, true, false), state(1, true), true);
        assertFalse(running.contains(Item.HIDE));
        assertEquals(Arrays.asList(Item.COMPANION_APP, Item.DETAILS, Item.PIN_SHORTCUT,
                Item.EXPORT_LAUNCHER), running);
    }

    @Test
    public void notInTheLibrary_noButtonsNoChips() {
        assertEquals(GamePageModel.Primary.NONE, GamePageModel.primary(null));
        assertTrue(GamePageModel.chips(null, state(0, true), true).isEmpty());
    }

    @Test
    public void figures_addThePlayPlayniteDoesNotCountYet() {
        GamePageModel.Figures f = GamePageModel.figures(launchesOnly(16, null), 420);
        assertEquals(147_900L + 420, f.totalSeconds);
        assertEquals((147_900L + 420) / 16, f.averageSeconds);
    }

    // --- Read against the answer before ---

    @Test
    public void settle_a404OnPlayTheLibraryKnows_isUnreadable_notNothing() {
        GamePageData d = GamePageData.settle(null, null, false, false, null, true);
        assertEquals(GamePageData.State.FAILED, d.state);
        assertTrue(d.unreadable);
        assertTrue(d.statsFailed);
        assertTrue(d.achievementsFailed);
    }

    @Test
    public void settle_a404OnAGameNeverPlayed_isNothing() {
        GamePageData d = GamePageData.settle(null, null, false, false, null, false);
        assertEquals(GamePageData.State.NOTHING, d.state);
        assertFalse(d.unreadable);
    }

    @Test
    public void settle_a404AfterAnAnswer_keepsTheAnswerBefore() {
        GamePageData before = GamePageData.settle(withSessions(),
                achievements(json("total", 2, "unlocked", 1)), false, false, null, true);
        GamePageData d = GamePageData.settle(null, null, false, false, before, true);
        assertEquals(GamePageData.State.READY, d.state);
        assertSame(before.stats, d.stats);
        assertSame(before.achievements, d.achievements);
        // The 404 is kept quietly; a network failure keeps it too, but still says it failed,
        // so the page can offer the retry over the figures kept.
        assertFalse(d.statsFailed);
        GamePageData failed = GamePageData.settle(null, null, true, true, before, true);
        assertSame(before.stats, failed.stats);
        assertEquals(GamePageData.State.READY, failed.state);
        assertTrue(failed.statsFailed);
    }

    @Test
    public void settle_aNewSessionPlayniteHasNotCounted_isAddedToTheTotal() {
        GameStats running = stats(json("activity", true, "playtime_seconds", 24_600L,
                "play_count", 43, "sessions", 10, "average_seconds", 600L,
                "last_session", json("start", "2026-10-08T09:00:00Z", "seconds", 300L)));
        GameStats ended = stats(json("activity", true, "playtime_seconds", 24_600L,
                "play_count", 43, "sessions", 11, "average_seconds", 600L,
                "last_session", json("start", "2026-10-08T10:00:00Z", "seconds", 420L)));
        GamePageData before = GamePageData.settle(running, null, false, false, null, true);
        GamePageData after = GamePageData.settle(ended, null, false, false, before, true);
        assertEquals(420L, after.unsyncedSeconds);
        // Asked again with nothing new: still not counted, still added.
        assertEquals(420L, GamePageData.settle(ended, null, false, false, after, true).unsyncedSeconds);
        // Playnite caught up: its total alone.
        GameStats synced = stats(json("activity", true, "playtime_seconds", 25_020L,
                "play_count", 43, "sessions", 11, "average_seconds", 600L,
                "last_session", json("start", "2026-10-08T10:00:00Z", "seconds", 420L)));
        assertEquals(0L, GamePageData.settle(synced, null, false, false, after, true).unsyncedSeconds);
    }

    // --- Metadata ---

    @Test
    public void metaLine_yearGenresStudio_withoutTheScore() {
        assertEquals("2023 · RPG · Sabotage Studio", GamePageModel.metaLine("2023",
                Arrays.asList("RPG"), Arrays.asList("Sabotage Studio", "Other")));
        assertEquals("2016 · Shooter, Simulator…", GamePageModel.metaLine("2016",
                Arrays.asList("Shooter", "Simulator", "Adventure", "Survival"), null));
        assertEquals("2020 · Role-playing (RPG), Adventure · Mojang Studios",
                GamePageModel.metaLine("2020", Arrays.asList("Role-playing (RPG)", "Adventure"),
                        Arrays.asList("Mojang Studios")));
    }

    @Test
    public void metaLine_leavesNoGapForAMissingPart() {
        assertEquals("Hello Games", GamePageModel.metaLine("",
                Collections.<String>emptyList(), Arrays.asList("Hello Games")));
        assertEquals("", GamePageModel.metaLine(null, null, null));
    }

    // --- One screen or two ---

    @Test
    public void noPanel_everythingInline_whateverTheAnswer() {
        for (GamePageData d : Arrays.asList(null, GamePageData.loading(), withAchievements(),
                GamePageData.from(null, null, false, false), GamePageData.unreachable())) {
            GamePageModel.Bottom bottom = GamePageModel.bottom(false, d);
            assertEquals(GamePageModel.Bottom.NONE, bottom);
            assertTrue(GamePageModel.achievementsInline(bottom));
            assertTrue(GamePageModel.chipsOnTop(bottom));
        }
    }

    @Test
    public void panel_takesTheLowerHalf_byTheAnswer() {
        assertEquals(GamePageModel.Bottom.STATUS, GamePageModel.bottom(true, null));
        assertEquals(GamePageModel.Bottom.STATUS, GamePageModel.bottom(true, GamePageData.loading()));
        assertEquals(GamePageModel.Bottom.STATUS, GamePageModel.bottom(true, GamePageData.unreachable()));
        assertEquals(GamePageModel.Bottom.LIST, GamePageModel.bottom(true, withAchievements()));
        GamePageModel.Bottom none = GamePageModel.bottom(true, GamePageData.from(null, null, false, false));
        assertEquals(GamePageModel.Bottom.ACTIONS, none);
        assertFalse(GamePageModel.chipsOnTop(none));
        assertFalse(GamePageModel.achievementsInline(GamePageModel.bottom(true, withAchievements())));
    }

    @Test
    public void thePanelGoingAndComing_movesOnlyTheLowerHalf() {
        // The user turns the Thor's lower screen off and on again over the same answer: the
        // achievements come up inline and go back down, the chips come up for a game without.
        GamePageData list = withAchievements();
        GamePageData nothing = GamePageData.from(null, null, false, false);
        boolean[] panel = {true, false, true};
        GamePageModel.Bottom[] listWay = {GamePageModel.Bottom.LIST, GamePageModel.Bottom.NONE,
                GamePageModel.Bottom.LIST};
        boolean[] chipsWay = {false, true, false};
        for (int i = 0; i < panel.length; i++) {
            assertEquals(listWay[i], GamePageModel.bottom(panel[i], list));
            assertEquals(chipsWay[i], GamePageModel.chipsOnTop(GamePageModel.bottom(panel[i], nothing)));
        }
    }

    private static GamePageData withAchievements() {
        return GamePageData.from(null, achievements(json("total", 2, "unlocked", 1)),
                false, false);
    }
}
