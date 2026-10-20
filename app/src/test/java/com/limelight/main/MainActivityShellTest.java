package com.limelight.main;

import android.content.Intent;
import android.os.Looper;
import android.view.View;

import androidx.fragment.app.Fragment;
import androidx.test.core.app.ApplicationProvider;

import com.google.android.material.bottomnavigation.BottomNavigationView;
import com.google.android.material.navigationrail.NavigationRailView;
import com.limelight.MainScreenTestEnvironment;
import com.limelight.ProfilesActivity;
import com.limelight.R;
import com.limelight.TestLogSuppressor;
import com.limelight.computers.SnapshotFixtures;
import com.limelight.library.LibraryFragment;
import com.limelight.library.LibraryHomeFragment;
import com.limelight.library.LibraryIntents;
import com.limelight.library.SearchFragment;
import com.limelight.library.SearchSectionFragment;
import com.limelight.library.SeeAllFragment;
import com.limelight.pcs.PcsFragment;
import com.limelight.preferences.AddComputerManually;
import com.limelight.preferences.StreamSettings;
import com.limelight.stats.StatsFragment;
import com.limelight.stats.StatsSectionFragment;

import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

/**
 * The main screen's shell: which navigation shows for the window, what the items do, back, and
 * the focus gate's way back into a section.
 */
@Config(sdk = {33}, shadows = {com.limelight.shadows.ShadowMoonBridge.class,
        com.limelight.shadows.ShadowGameManager.class})
@RunWith(RobolectricTestRunner.class)
public class MainActivityShellTest {

    @BeforeClass
    public static void suppressInvalidIdLogs() {
        TestLogSuppressor.install();
    }

    @Before
    public void prepareEnvironment() {
        // A saved renderer, a game service and a stub computer manager for the repository.
        MainScreenTestEnvironment.prepare();
    }

    /**
     * The main screen as a device that has used it before opens it: on the Library of the host
     * opened last. That host is never polled here, so the Library shows it as not found yet.
     */
    private ActivityController<MainActivity> launch() {
        MainScreenTestEnvironment.rememberLastHost("host-last");
        ActivityController<MainActivity> controller =
                Robolectric.buildActivity(MainActivity.class).setup();
        Shadows.shadowOf(Looper.getMainLooper()).idle();
        return controller;
    }

    /** The main screen on its first run: no host has ever been opened. */
    private ActivityController<MainActivity> launchFirstRun() {
        ActivityController<MainActivity> controller =
                Robolectric.buildActivity(MainActivity.class).setup();
        Shadows.shadowOf(Looper.getMainLooper()).idle();
        return controller;
    }

    private static Fragment section(MainActivity activity, Section section) {
        return activity.getSupportFragmentManager().findFragmentByTag(section.tag());
    }

    /** The first screen of a stacked section, under whatever is pushed over it. */
    private static Fragment root(Fragment section) {
        return section.getChildFragmentManager().getFragments().get(0);
    }

    @Test
    @Config(qualifiers = "w900dp-h500dp-land")
    public void wideWindow_showsTheRail() {
        MainActivity activity = launch().get();

        assertEquals(View.VISIBLE, activity.findViewById(R.id.navRailColumn).getVisibility());
        assertEquals(View.GONE, activity.findViewById(R.id.navBottom).getVisibility());
        Fragment library = section(activity, Section.LIBRARY);
        assertNotNull(library);
        assertTrue(library.isVisible());
    }

    @Test
    @Config(qualifiers = "w400dp-h800dp-port")
    public void compactWindow_showsTheBottomBar() {
        MainActivity activity = launch().get();

        assertEquals(View.GONE, activity.findViewById(R.id.navRailColumn).getVisibility());
        assertEquals(View.VISIBLE, activity.findViewById(R.id.navBottom).getVisibility());
    }

    @Test
    @Config(qualifiers = "w900dp-h500dp-land")
    public void rotation_swapsTheNavigationWithoutRecreating() {
        ActivityController<MainActivity> controller = launch();
        MainActivity before = controller.get();

        RuntimeEnvironment.setQualifiers("w400dp-h800dp-port");
        controller.configurationChange(before.getResources().getConfiguration());
        Shadows.shadowOf(Looper.getMainLooper()).idle();

        assertSame(before, controller.get());
        assertEquals(View.GONE, before.findViewById(R.id.navRailColumn).getVisibility());
        assertEquals(View.VISIBLE, before.findViewById(R.id.navBottom).getVisibility());
    }

    @Test
    @Config(qualifiers = "w900dp-h500dp-land")
    public void settings_startsStreamSettingsAndKeepsTheSelection() {
        MainActivity activity = launch().get();
        NavigationRailView rail = activity.findViewById(R.id.navRail);
        // The rail's own Settings item gives way to the button at its foot.
        assertFalse(rail.getMenu().findItem(R.id.nav_settings).isVisible());

        activity.findViewById(R.id.navRailSettings).performClick();

        Intent next = Shadows.shadowOf(activity).getNextStartedActivity();
        assertNotNull("Settings should open StreamSettings", next);
        assertEquals(StreamSettings.class.getName(), next.getComponent().getClassName());
        assertEquals(R.id.nav_library, rail.getSelectedItemId());
        BottomNavigationView bottom = activity.findViewById(R.id.navBottom);
        assertEquals(R.id.nav_library, bottom.getSelectedItemId());
        assertTrue(section(activity, Section.LIBRARY).isVisible());
    }

    @Test
    @Config(qualifiers = "w400dp-h800dp-port")
    public void settingsInTheBottomBar_startsStreamSettingsAndKeepsTheSelection() {
        MainActivity activity = launch().get();
        BottomNavigationView bottom = activity.findViewById(R.id.navBottom);

        bottom.findViewById(R.id.nav_settings).performClick();

        Intent next = Shadows.shadowOf(activity).getNextStartedActivity();
        assertNotNull("Settings should open StreamSettings", next);
        assertEquals(StreamSettings.class.getName(), next.getComponent().getClassName());
        assertEquals(R.id.nav_library, bottom.getSelectedItemId());
        assertTrue(section(activity, Section.LIBRARY).isVisible());
    }

    @Test
    @Config(qualifiers = "w900dp-h500dp-land")
    public void choosingASection_showsItAndMirrorsTheCheck() {
        MainActivity activity = launch().get();
        NavigationRailView rail = activity.findViewById(R.id.navRail);
        BottomNavigationView bottom = activity.findViewById(R.id.navBottom);

        rail.findViewById(R.id.nav_pcs).performClick();

        assertEquals(R.id.nav_pcs, rail.getSelectedItemId());
        assertEquals(R.id.nav_pcs, bottom.getSelectedItemId());
        assertTrue(section(activity, Section.PCS).isVisible());
        assertTrue(section(activity, Section.LIBRARY).isHidden());
    }

    @Test
    @Config(qualifiers = "w900dp-h500dp-land")
    public void back_fromPcsReturnsToLibrary_thenLeaves() {
        MainActivity activity = launch().get();
        NavigationRailView rail = activity.findViewById(R.id.navRail);
        rail.findViewById(R.id.nav_pcs).performClick();

        activity.getOnBackPressedDispatcher().onBackPressed();

        assertEquals(R.id.nav_library, rail.getSelectedItemId());
        assertTrue(section(activity, Section.LIBRARY).isVisible());
        assertTrue(section(activity, Section.PCS).isHidden());
        assertFalse(activity.isFinishing());

        // At the Library's home back has no work of its own: the system default leaves.
        activity.getOnBackPressedDispatcher().onBackPressed();
        assertTrue(activity.isFinishing());
    }

    @Test
    @Config(qualifiers = "w900dp-h500dp-land")
    public void avatar_startsProfilesActivity() {
        MainActivity activity = launch().get();

        activity.findViewById(R.id.navAvatar).performClick();

        Intent next = Shadows.shadowOf(activity).getNextStartedActivity();
        assertNotNull("The avatar should open the profiles screen", next);
        assertEquals(ProfilesActivity.class.getName(), next.getComponent().getClassName());
    }

    @Test
    @Config(qualifiers = "w900dp-h500dp-land")
    public void focusGate_rightFromTheRailReturnsToTheLastFocusedView() {
        MainActivity activity = launch().get();
        MainRootLayout root = activity.findViewById(R.id.mainRoot);
        NavigationRailView rail = activity.findViewById(R.id.navRail);
        rail.findViewById(R.id.nav_search).performClick();
        Shadows.shadowOf(Looper.getMainLooper()).idle();
        View field = section(activity, Section.SEARCH).requireView().findViewById(R.id.searchField);
        View railItem = rail.findViewById(R.id.nav_search);

        field.requestFocus();
        railItem.requestFocus();

        assertSame(field, root.focusSearch(railItem, View.FOCUS_RIGHT));
    }

    @Test
    @Config(qualifiers = "w900dp-h500dp-land")
    public void search_isTheRealSection_focusOnTheField() {
        MainActivity activity = launchFirstRun().get();
        NavigationRailView rail = activity.findViewById(R.id.navRail);

        rail.findViewById(R.id.nav_search).performClick();
        Shadows.shadowOf(Looper.getMainLooper()).idle();

        Fragment search = section(activity, Section.SEARCH);
        assertTrue(search instanceof SearchSectionFragment);
        assertTrue(root(search) instanceof SearchFragment);
        View field = search.requireView().findViewById(R.id.searchField);
        assertTrue("Entering Search focuses its field", field.isFocused());
        // No PC chosen: the panel offers one, in the results' place.
        assertEquals(View.VISIBLE,
                search.requireView().findViewById(R.id.hostStatePanel).getVisibility());
        assertEquals(View.GONE, search.requireView().findViewById(R.id.searchResults).getVisibility());
    }

    @Test
    @Config(qualifiers = "w900dp-h500dp-land")
    public void stats_isOfferedOnlyWhileTheHostServesThem() {
        MainActivity activity = launch().get();
        NavigationRailView rail = activity.findViewById(R.id.navRail);
        BottomNavigationView bottom = activity.findViewById(R.id.navBottom);
        // No host yet: nothing serves statistics.
        assertFalse(rail.getMenu().findItem(R.id.nav_stats).isVisible());
        assertFalse(bottom.getMenu().findItem(R.id.nav_stats).isVisible());

        activity.onLibrary(SnapshotFixtures.withStats("host-1", true));

        assertTrue(rail.getMenu().findItem(R.id.nav_stats).isVisible());
        assertTrue(bottom.getMenu().findItem(R.id.nav_stats).isVisible());
        rail.findViewById(R.id.nav_stats).performClick();
        Shadows.shadowOf(Looper.getMainLooper()).idle();
        Fragment stats = section(activity, Section.STATS);
        assertTrue(stats instanceof StatsSectionFragment);
        assertTrue(root(stats) instanceof StatsFragment);
        assertTrue(stats.isVisible());
    }

    @Test
    @Config(qualifiers = "w900dp-h500dp-land")
    public void stats_hiddenWhileSelected_fallsBackToTheLibrary() {
        MainActivity activity = launch().get();
        NavigationRailView rail = activity.findViewById(R.id.navRail);
        BottomNavigationView bottom = activity.findViewById(R.id.navBottom);
        activity.onLibrary(SnapshotFixtures.withStats("host-1", true));
        rail.findViewById(R.id.nav_stats).performClick();
        Shadows.shadowOf(Looper.getMainLooper()).idle();
        assertEquals(R.id.nav_stats, rail.getSelectedItemId());

        // Another host, one without /appstats.
        activity.onLibrary(SnapshotFixtures.withStats("host-2", false));
        Shadows.shadowOf(Looper.getMainLooper()).idle();

        assertFalse(rail.getMenu().findItem(R.id.nav_stats).isVisible());
        assertFalse(bottom.getMenu().findItem(R.id.nav_stats).isVisible());
        assertEquals(R.id.nav_library, rail.getSelectedItemId());
        assertEquals(R.id.nav_library, bottom.getSelectedItemId());
        assertTrue(section(activity, Section.LIBRARY).isVisible());
        assertTrue(section(activity, Section.STATS).isHidden());
        assertFalse(activity.isFinishing());
    }

    @Test
    @Config(qualifiers = "w900dp-h500dp-land")
    public void library_opensOnHome_withNoHostOffersToChooseOne() {
        MainActivity activity = launchFirstRun().get();
        NavigationRailView rail = activity.findViewById(R.id.navRail);
        rail.findViewById(R.id.nav_library).performClick();
        Shadows.shadowOf(Looper.getMainLooper()).idle();

        Fragment library = section(activity, Section.LIBRARY);
        assertTrue(library instanceof LibraryFragment);
        Fragment top = library.getChildFragmentManager().findFragmentById(R.id.sectionStackContainer);
        assertTrue(top instanceof LibraryHomeFragment);
        // The home's own stack has nothing under it: back has no work in the Library.
        assertEquals(0, library.getChildFragmentManager().getBackStackEntryCount());
        View view = top.requireView();
        View primary = view.findViewById(R.id.hostStatePrimary);
        assertNotNull("The home lists the host state panel when no PC is chosen", primary);
        assertEquals(View.VISIBLE, primary.getVisibility());
        assertNotNull(view.findViewById(R.id.libraryHostChip));

        primary.performClick();
        Shadows.shadowOf(Looper.getMainLooper()).idle();

        assertTrue(section(activity, Section.PCS).isVisible());
        assertFalse(activity.isFinishing());
    }

    @Test
    @Config(qualifiers = "w900dp-h500dp-land")
    public void seeAll_isPushedOverTheHome_andBackPopsIt() {
        MainActivity activity = launch().get();
        LibraryFragment library = (LibraryFragment) section(activity, Section.LIBRARY);

        library.showSeeAll();
        Shadows.shadowOf(Looper.getMainLooper()).idle();

        Fragment top = library.getChildFragmentManager().findFragmentById(R.id.sectionStackContainer);
        assertTrue(top instanceof SeeAllFragment);
        // A window with the rail has no arrow: B, or the Library's item again, goes back.
        assertEquals(View.GONE, top.requireView().findViewById(R.id.seeAllBack).getVisibility());

        activity.getOnBackPressedDispatcher().onBackPressed();
        Shadows.shadowOf(Looper.getMainLooper()).idle();

        top = library.getChildFragmentManager().findFragmentById(R.id.sectionStackContainer);
        assertTrue(top instanceof LibraryHomeFragment);
        assertTrue(top.isVisible());
        assertFalse(activity.isFinishing());
    }

    @Test
    @Config(qualifiers = "w400dp-h800dp-port")
    public void seeAllInACompactWindow_hasTheBackArrowAndASearchButton() {
        MainActivity activity = launch().get();
        LibraryFragment library = (LibraryFragment) section(activity, Section.LIBRARY);

        library.showSeeAll();
        Shadows.shadowOf(Looper.getMainLooper()).idle();

        Fragment top = library.getChildFragmentManager().findFragmentById(R.id.sectionStackContainer);
        assertTrue(top instanceof SeeAllFragment);
        // Something to go back to now, so the arrow shows.
        assertEquals(View.VISIBLE, top.requireView().findViewById(R.id.seeAllBack).getVisibility());
        // Search is not in the bottom bar: the toolbar's button leads there.
        BottomNavigationView bottom = activity.findViewById(R.id.navBottom);
        assertFalse(bottom.getMenu().findItem(R.id.nav_search).isVisible());
        top.requireView().findViewById(R.id.seeAllSearch).performClick();
        Shadows.shadowOf(Looper.getMainLooper()).idle();
        assertTrue(section(activity, Section.SEARCH) instanceof SearchSectionFragment);
        assertTrue(section(activity, Section.SEARCH).isVisible());
    }

    @Test
    @Config(qualifiers = "w900dp-h500dp-land")
    public void libraryChosenAgain_popsSeeAll() {
        MainActivity activity = launch().get();
        LibraryFragment library = (LibraryFragment) section(activity, Section.LIBRARY);
        library.showSeeAll();
        Shadows.shadowOf(Looper.getMainLooper()).idle();

        NavigationRailView rail = activity.findViewById(R.id.navRail);
        rail.setSelectedItemId(R.id.nav_library);
        Shadows.shadowOf(Looper.getMainLooper()).idle();

        Fragment top = library.getChildFragmentManager().findFragmentById(R.id.sectionStackContainer);
        assertTrue(top instanceof LibraryHomeFragment);
        assertTrue(top.isVisible());
    }

    @Test
    @Config(qualifiers = "w900dp-h500dp-land")
    public void pcs_isTheRealSection_searchingUntilAHostIsKnown() {
        MainActivity activity = launch().get();
        NavigationRailView rail = activity.findViewById(R.id.navRail);

        rail.findViewById(R.id.nav_pcs).performClick();
        Shadows.shadowOf(Looper.getMainLooper()).idle();

        Fragment pcs = section(activity, Section.PCS);
        assertTrue(pcs instanceof PcsFragment);
        assertEquals(View.VISIBLE, pcs.requireView().findViewById(R.id.pcsEmpty).getVisibility());

        pcs.requireView().findViewById(R.id.pcsAddButton).performClick();
        Intent next = Shadows.shadowOf(activity).getNextStartedActivity();
        assertNotNull("Add PC should open the add-PC screen", next);
        assertEquals(AddComputerManually.class.getName(), next.getComponent().getClassName());
    }

    @Test
    @Config(qualifiers = "w900dp-h500dp-land")
    public void firstRun_opensOnPcs() {
        MainActivity activity = launchFirstRun().get();

        NavigationRailView rail = activity.findViewById(R.id.navRail);
        assertEquals(R.id.nav_pcs, rail.getSelectedItemId());
        assertTrue(section(activity, Section.PCS).isVisible());
        // Not made yet, or at least not on screen behind PCs.
        assertTrue(section(activity, Section.LIBRARY) == null
                || section(activity, Section.LIBRARY).isHidden());
    }

    @Test
    @Config(qualifiers = "w900dp-h500dp-land")
    public void launcher_withALastHost_opensOnItsLibrary() {
        MainActivity activity = launch().get();

        NavigationRailView rail = activity.findViewById(R.id.navRail);
        assertEquals(R.id.nav_library, rail.getSelectedItemId());
        assertTrue(section(activity, Section.LIBRARY).isVisible());
        assertEquals("host-last",
                activity.hostRepository().getSelectedHostUuid().getValue());
    }

    @Test
    @Config(qualifiers = "w900dp-h500dp-land")
    public void hostShortcut_opensThatHostsLibrary() {
        // What ShortcutTrampoline hands over for a host shortcut, even on a first run.
        Intent intent = new Intent(ApplicationProvider.getApplicationContext(), MainActivity.class)
                .setAction(Intent.ACTION_MAIN)
                .putExtra(LibraryIntents.EXTRA_UUID, "host-shortcut")
                .putExtra(LibraryIntents.EXTRA_NAME, "Taverna");
        MainActivity activity = Robolectric.buildActivity(MainActivity.class, intent).setup().get();
        Shadows.shadowOf(Looper.getMainLooper()).idle();

        NavigationRailView rail = activity.findViewById(R.id.navRail);
        assertEquals(R.id.nav_library, rail.getSelectedItemId());
        assertTrue(section(activity, Section.LIBRARY).isVisible());
        assertEquals("host-shortcut",
                activity.hostRepository().getSelectedHostUuid().getValue());
    }

    @Test
    @Config(qualifiers = "w900dp-h500dp-land")
    public void pairingLink_opensOnPcs() {
        Intent intent = new Intent(ApplicationProvider.getApplicationContext(), MainActivity.class)
                .putExtra(LibraryIntents.EXTRA_PAIR_HOSTNAME, "10.0.0.2")
                .putExtra(LibraryIntents.EXTRA_PAIR_PORT, 47989)
                .putExtra(LibraryIntents.EXTRA_PAIR_PIN, "1234")
                .putExtra(LibraryIntents.EXTRA_PAIR_PASSPHRASE, "secret");
        MainActivity activity = Robolectric.buildActivity(MainActivity.class, intent).setup().get();
        Shadows.shadowOf(Looper.getMainLooper()).idle();

        NavigationRailView rail = activity.findViewById(R.id.navRail);
        assertEquals(R.id.nav_pcs, rail.getSelectedItemId());
        assertTrue(section(activity, Section.PCS).isVisible());
    }
}
