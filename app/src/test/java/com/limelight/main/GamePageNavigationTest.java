package com.limelight.main;

import android.os.Looper;
import android.view.View;
import android.widget.TextView;

import androidx.fragment.app.Fragment;

import com.google.android.material.navigationrail.NavigationRailView;
import com.limelight.MainScreenTestEnvironment;
import com.limelight.R;
import com.limelight.TestLogSuppressor;
import com.limelight.computers.HostLibrary;
import com.limelight.computers.SnapshotFixtures;
import com.limelight.library.LibraryFragment;
import com.limelight.stats.GamePageArgs;
import com.limelight.stats.GamePageFragment;
import com.limelight.stats.StatsFragment;

import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

/**
 * A game's page is pushed over the section it was opened from: onto Stats' stack from Stats, onto
 * the Library's from PCs (which has no stack), and back pops it, leaving the section as it was.
 */
@Config(sdk = {33}, qualifiers = "w900dp-h500dp-land",
        shadows = {com.limelight.shadows.ShadowMoonBridge.class,
                com.limelight.shadows.ShadowGameManager.class})
@RunWith(RobolectricTestRunner.class)
public class GamePageNavigationTest {

    @BeforeClass
    public static void suppressInvalidIdLogs() {
        TestLogSuppressor.install();
    }

    @Before
    public void prepareEnvironment() {
        MainScreenTestEnvironment.prepare();
    }

    private static void idle() {
        Shadows.shadowOf(Looper.getMainLooper()).idle();
    }

    private static MainActivity start() {
        MainScreenTestEnvironment.rememberLastHost("host-last");
        MainActivity activity = Robolectric.buildActivity(MainActivity.class).setup().get();
        idle();
        return activity;
    }

    // The page is about a game of the host the library shows: one of another host would leave.
    private static GamePageArgs argsFor(MainActivity activity) {
        HostLibrary.Snapshot snapshot = activity.hostRepository().getLibrary().getValue();
        String host = snapshot != null ? snapshot.hostUuid : "host-last";
        return new GamePageArgs(host, "APP-UUID", 0, "Sea of Stars", "Stats · This month");
    }

    private static NavigationRailView rail(MainActivity activity) {
        return activity.findViewById(R.id.navRail);
    }

    private static StackedSectionFragment section(MainActivity activity, Section section) {
        return (StackedSectionFragment) activity.getSupportFragmentManager()
                .findFragmentByTag(section.tag());
    }

    private static Fragment top(StackedSectionFragment section) {
        return section.getChildFragmentManager().findFragmentById(R.id.sectionStackContainer);
    }

    @Test
    public void fromStats_pushesOntoTheStatsStack_andBackPopsIt() {
        MainActivity activity = start();
        activity.onLibrary(SnapshotFixtures.withStats("host-1", true));
        rail(activity).findViewById(R.id.nav_stats).performClick();
        idle();
        StackedSectionFragment stats = section(activity, Section.STATS);

        activity.openGamePage(argsFor(activity));
        idle();

        assertEquals(1, stats.depth());
        assertTrue(top(stats) instanceof GamePageFragment);
        assertEquals(R.id.nav_stats, rail(activity).getSelectedItemId());

        activity.getOnBackPressedDispatcher().onBackPressed();
        idle();

        assertEquals(0, stats.depth());
        assertTrue(top(stats) instanceof StatsFragment);
        assertEquals(R.id.nav_stats, rail(activity).getSelectedItemId());
    }

    @Test
    public void fromPcs_switchesToTheLibrary_andPushesThere() {
        MainActivity activity = start();
        rail(activity).findViewById(R.id.nav_pcs).performClick();
        idle();

        activity.openGamePage(argsFor(activity));
        idle();

        assertEquals(R.id.nav_library, rail(activity).getSelectedItemId());
        LibraryFragment library = (LibraryFragment) section(activity, Section.LIBRARY);
        assertEquals(1, library.depth());
        assertTrue(top(library) instanceof GamePageFragment);

        activity.getOnBackPressedDispatcher().onBackPressed();
        idle();

        assertEquals(0, library.depth());
        assertEquals(R.id.nav_library, rail(activity).getSelectedItemId());
    }

    @Test
    public void aSecondOpen_whileAPageIsUp_isIgnored() {
        MainActivity activity = start();
        StackedSectionFragment library = section(activity, Section.LIBRARY);

        activity.openGamePage(argsFor(activity));
        activity.openGamePage(argsFor(activity));
        idle();

        assertEquals(1, library.depth());
    }

    @Test
    public void anotherGame_replacesThePageOnTop() {
        MainActivity activity = start();
        StackedSectionFragment library = section(activity, Section.LIBRARY);
        GamePageArgs first = argsFor(activity);

        activity.openGamePage(first);
        idle();
        // An unlock toast for another game, tapped over the first one's page.
        activity.openGamePage(new GamePageArgs(first.hostUuid, "OTHER-UUID", 0, "Hades", null));
        idle();

        assertEquals(1, library.depth());
        GamePageArgs shown = ((GamePageFragment) top(library)).args();
        assertEquals("OTHER-UUID", shown.appUuid);

        activity.getOnBackPressedDispatcher().onBackPressed();
        idle();

        assertEquals(0, library.depth());
    }

    @Test
    public void anEmptyCrumb_namesTheSectionThePageLandsOn() {
        MainActivity activity = start();
        rail(activity).findViewById(R.id.nav_pcs).performClick();
        idle();

        activity.openGamePage(argsFor(activity).withOrigin(null));
        idle();

        LibraryFragment library = (LibraryFragment) section(activity, Section.LIBRARY);
        TextView origin = top(library).requireView().findViewById(R.id.gamePageOrigin);
        assertEquals(activity.getString(R.string.nav_library), origin.getText().toString());
    }

    @Test
    @Config(qualifiers = "w400dp-h800dp")
    public void compactWindow_movesTheButtonsAndTheBodyUnderTheGame() {
        MainActivity activity = start();
        StackedSectionFragment library = section(activity, Section.LIBRARY);

        activity.openGamePage(argsFor(activity));
        idle();

        View page = top(library).requireView();
        View column = page.findViewById(R.id.gamePageColumn);
        assertSame(column, page.findViewById(R.id.gamePageButtons).getParent());
        assertSame(column, page.findViewById(R.id.gamePageBody).getParent());
        assertEquals(View.VISIBLE, page.findViewById(R.id.gamePageHost).getVisibility());
    }

    @Test
    public void unreachableHost_showsTheRetryRow() {
        MainActivity activity = start();
        StackedSectionFragment library = section(activity, Section.LIBRARY);

        activity.openGamePage(argsFor(activity));
        idle();

        View page = top(library).requireView();
        View status = page.findViewById(R.id.gamePageStatus);
        assertEquals(View.VISIBLE, status.getVisibility());
        assertTrue(status.isFocusable());
        assertEquals(activity.getString(R.string.game_stats_failed),
                ((TextView) page.findViewById(R.id.gamePageStatusText)).getText().toString());
        // No game in the library: neither buttons nor chips.
        assertEquals(View.GONE, page.findViewById(R.id.gamePageButtons).getVisibility());
        assertEquals(View.GONE, page.findViewById(R.id.gamePageChips).getVisibility());
    }
}
