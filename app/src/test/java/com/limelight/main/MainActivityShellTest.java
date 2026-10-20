package com.limelight.main;

import android.app.Application;
import android.app.GameManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.Looper;
import android.view.View;

import androidx.fragment.app.Fragment;
import androidx.test.core.app.ApplicationProvider;

import com.google.android.material.bottomnavigation.BottomNavigationView;
import com.google.android.material.navigationrail.NavigationRailView;
import com.limelight.ProfilesActivity;
import com.limelight.R;
import com.limelight.TestLogSuppressor;
import com.limelight.computers.ComputerManagerService;
import com.limelight.library.LibraryIntents;
import com.limelight.pcs.PcsFragment;
import com.limelight.preferences.AddComputerManually;
import com.limelight.preferences.StreamSettings;

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
import static org.mockito.Mockito.mock;

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
        Application app = ApplicationProvider.getApplicationContext();

        // A saved renderer for this build, so the probe has nothing to do.
        SharedPreferences glPrefs = app.getSharedPreferences("GlPreferences", 0);
        glPrefs.edit()
                .putString("Renderer", "TestRenderer")
                .putString("Fingerprint", Build.FINGERPRINT)
                .commit();

        Shadows.shadowOf(app).setSystemService(Context.GAME_SERVICE, mock(GameManager.class));

        // The repository binds the computer manager; a stub binder stands in for the service.
        ComponentName cms = new ComponentName(app, ComputerManagerService.class);
        Shadows.shadowOf(app).setComponentNameAndServiceForBindService(cms,
                mock(ComputerManagerService.ComputerManagerBinder.class));
    }

    private ActivityController<MainActivity> launch() {
        ActivityController<MainActivity> controller =
                Robolectric.buildActivity(MainActivity.class).setup();
        Shadows.shadowOf(Looper.getMainLooper()).idle();
        return controller;
    }

    private static Fragment section(MainActivity activity, Section section) {
        return activity.getSupportFragmentManager().findFragmentByTag(section.tag());
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
        View second = section(activity, Section.LIBRARY).requireView()
                .findViewById(R.id.placeholderSecond);
        View railItem = activity.findViewById(R.id.navRail).findViewById(R.id.nav_library);

        second.requestFocus();
        railItem.requestFocus();

        assertSame(second, root.focusSearch(railItem, View.FOCUS_RIGHT));
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
