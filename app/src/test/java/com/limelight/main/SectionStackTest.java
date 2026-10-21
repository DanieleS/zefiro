package com.limelight.main;

import android.os.Bundle;
import android.os.Looper;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import com.google.android.material.navigationrail.NavigationRailView;
import com.limelight.MainScreenTestEnvironment;
import com.limelight.R;
import com.limelight.TestLogSuppressor;
import com.limelight.computers.SnapshotFixtures;
import com.limelight.stats.StatsFragment;
import com.limelight.stats.StatsSectionFragment;

import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Stacked sections: a screen pushed over a section's root, back popping it before leaving the
 * section, and the section's item chosen again popping to the root.
 */
@Config(sdk = {33}, qualifiers = "w900dp-h500dp-land",
        shadows = {com.limelight.shadows.ShadowMoonBridge.class,
                com.limelight.shadows.ShadowGameManager.class})
@RunWith(RobolectricTestRunner.class)
public class SectionStackTest {

    private static final String TAG_PLACEHOLDER = "test:placeholder";

    /** A screen with a view and nothing else, standing in for a game's page. */
    public static class PlaceholderFragment extends Fragment {
        @Nullable
        @Override
        public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                                 @Nullable Bundle savedInstanceState) {
            return new FrameLayout(inflater.getContext());
        }
    }

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

    /** The main screen on a host that serves statistics, switched to Stats. */
    private static MainActivity onStats() {
        MainScreenTestEnvironment.rememberLastHost("host-last");
        MainActivity activity = Robolectric.buildActivity(MainActivity.class).setup().get();
        idle();
        activity.onLibrary(SnapshotFixtures.withStats("host-1", true));
        rail(activity).findViewById(R.id.nav_stats).performClick();
        idle();
        return activity;
    }

    private static NavigationRailView rail(MainActivity activity) {
        return activity.findViewById(R.id.navRail);
    }

    private static StatsSectionFragment stats(MainActivity activity) {
        return (StatsSectionFragment) activity.getSupportFragmentManager()
                .findFragmentByTag(Section.STATS.tag());
    }

    private static Fragment top(StackedSectionFragment section) {
        return section.getChildFragmentManager().findFragmentById(R.id.sectionStackContainer);
    }

    @Test
    public void push_givesBackWork() {
        MainActivity activity = onStats();
        StatsSectionFragment stats = stats(activity);
        assertFalse(stats.hasBackWork());

        stats.push(new PlaceholderFragment(), TAG_PLACEHOLDER);
        idle();

        assertEquals(1, stats.depth());
        assertTrue(stats.hasBackWork());
        assertTrue(top(stats) instanceof PlaceholderFragment);
        assertTrue(activity.getOnBackPressedDispatcher().hasEnabledCallbacks());
    }

    @Test
    public void push_ofATagAlreadyUp_doesNothing() {
        MainActivity activity = onStats();
        StatsSectionFragment stats = stats(activity);

        stats.push(new PlaceholderFragment(), TAG_PLACEHOLDER);
        stats.push(new PlaceholderFragment(), TAG_PLACEHOLDER);
        idle();

        assertEquals(1, stats.depth());
    }

    @Test
    public void back_popsThePushedScreen_thenLeavesForTheLibrary() {
        MainActivity activity = onStats();
        StatsSectionFragment stats = stats(activity);
        stats.push(new PlaceholderFragment(), TAG_PLACEHOLDER);
        idle();

        activity.getOnBackPressedDispatcher().onBackPressed();
        idle();

        // The pushed screen is gone, the root is shown again, and Stats is still the section.
        assertEquals(0, stats.depth());
        assertTrue(top(stats) instanceof StatsFragment);
        assertTrue(top(stats).isVisible());
        assertEquals(R.id.nav_stats, rail(activity).getSelectedItemId());
        assertTrue(stats.isVisible());

        activity.getOnBackPressedDispatcher().onBackPressed();
        idle();

        assertEquals(R.id.nav_library, rail(activity).getSelectedItemId());
        assertTrue(stats.isHidden());
        assertFalse(activity.isFinishing());
    }

    @Test
    public void statsChosenAgain_popsToTheRoot() {
        MainActivity activity = onStats();
        StatsSectionFragment stats = stats(activity);
        stats.push(new PlaceholderFragment(), TAG_PLACEHOLDER);
        idle();

        rail(activity).setSelectedItemId(R.id.nav_stats);
        idle();

        assertEquals(0, stats.depth());
        assertTrue(top(stats) instanceof StatsFragment);
        assertTrue(top(stats).isVisible());
        assertEquals(R.id.nav_stats, rail(activity).getSelectedItemId());
    }

    @Test
    public void library_backIsDisabledAtHome_andEnabledByAPush() {
        MainScreenTestEnvironment.rememberLastHost("host-last");
        MainActivity activity = Robolectric.buildActivity(MainActivity.class).setup().get();
        idle();
        StackedSectionFragment library = (StackedSectionFragment) activity
                .getSupportFragmentManager().findFragmentByTag(Section.LIBRARY.tag());
        assertFalse(activity.getOnBackPressedDispatcher().hasEnabledCallbacks());

        library.push(new PlaceholderFragment(), TAG_PLACEHOLDER);
        idle();
        assertTrue(activity.getOnBackPressedDispatcher().hasEnabledCallbacks());

        activity.getOnBackPressedDispatcher().onBackPressed();
        idle();
        assertFalse(activity.getOnBackPressedDispatcher().hasEnabledCallbacks());
        assertFalse(activity.isFinishing());
    }
}
