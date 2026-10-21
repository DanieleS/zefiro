package com.limelight.main;

import android.os.Looper;
import android.view.KeyEvent;
import android.view.View;

import androidx.fragment.app.Fragment;
import androidx.lifecycle.MutableLiveData;
import androidx.test.platform.app.InstrumentationRegistry;

import com.limelight.MainScreenTestEnvironment;
import com.limelight.R;
import com.limelight.TestLogSuppressor;
import com.limelight.companion.CompanionDisplayManager;
import com.limelight.companion.CompanionPage;
import com.limelight.computers.HostLibrary;
import com.limelight.stats.GameAchievementsPage;
import com.limelight.stats.GamePageArgs;
import com.limelight.stats.GamePageFragment;

import org.junit.After;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;

import java.lang.reflect.Field;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

/**
 * A game's page with the companion panel showing: its lower half goes to the panel as a page of
 * its own, R2 takes the d-pad down there and L2 brings it back, and the panel going away brings
 * the half back to the main screen with the d-pad on it.
 */
@Config(sdk = {33}, qualifiers = "w900dp-h500dp-land",
        shadows = {com.limelight.shadows.ShadowMoonBridge.class,
                com.limelight.shadows.ShadowGameManager.class})
@RunWith(RobolectricTestRunner.class)
public class GamePagePanelTest {

    @BeforeClass
    public static void suppressInvalidIdLogs() {
        TestLogSuppressor.install();
    }

    @Before
    public void prepareEnvironment() {
        MainScreenTestEnvironment.prepare();
    }

    @After
    public void panelOff() throws Exception {
        setPanelShowing(false);
    }

    private static void idle() {
        Shadows.shadowOf(Looper.getMainLooper()).idle();
    }

    // The panel is a Presentation on a second display, which a test has none of: the value its
    // attach and detach would set is set directly.
    @SuppressWarnings("unchecked")
    private static void setPanelShowing(boolean showing) throws Exception {
        Field field = CompanionDisplayManager.class.getDeclaredField("panelShowing");
        field.setAccessible(true);
        ((MutableLiveData<Boolean>) field.get(null)).setValue(showing);
    }

    // What the feed would draw on the panel now.
    private static CompanionPage panelPage(MainActivity activity) throws Exception {
        Field feedField = MainActivity.class.getDeclaredField("companionFeed");
        feedField.setAccessible(true);
        Object feed = feedField.get(activity);
        Field pageField = CompanionFeed.class.getDeclaredField("page");
        pageField.setAccessible(true);
        return (CompanionPage) pageField.get(feed);
    }

    private static MainActivity start() {
        // A pad drives the d-pad: out of touch mode, as a key press would leave it.
        InstrumentationRegistry.getInstrumentation().setInTouchMode(false);
        MainScreenTestEnvironment.rememberLastHost("host-last");
        MainActivity activity = Robolectric.buildActivity(MainActivity.class).setup().get();
        idle();
        return activity;
    }

    private static GamePageFragment openPage(MainActivity activity) {
        HostLibrary.Snapshot snapshot = activity.hostRepository().getLibrary().getValue();
        String host = snapshot != null ? snapshot.hostUuid : "host-last";
        activity.openGamePage(new GamePageArgs(host, "APP-UUID", 0, "Sea of Stars", null));
        idle();
        StackedSectionFragment library = (StackedSectionFragment) activity
                .getSupportFragmentManager().findFragmentByTag(Section.LIBRARY.tag());
        Fragment top = library.getChildFragmentManager()
                .findFragmentById(R.id.sectionStackContainer);
        assertTrue(top instanceof GamePageFragment);
        return (GamePageFragment) top;
    }

    private static void press(MainActivity activity, int code) {
        activity.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN, code));
        activity.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_UP, code));
        idle();
    }

    @Test
    public void withThePanel_theLowerHalfIsAPageThere_andR2L2MoveTheDpad() throws Exception {
        setPanelShowing(true);
        MainActivity activity = start();
        GamePageFragment page = openPage(activity);
        View root = page.requireView();

        assertEquals(View.GONE, root.findViewById(R.id.gamePageAchievements).getVisibility());
        // The host has not answered (it cannot, here): the panel shows the one status row.
        CompanionPage shown = panelPage(activity);
        assertTrue(shown instanceof GameAchievementsPage);
        assertEquals(CompanionPage.NO_STOP, shown.focusedStop());

        View focusedBefore = focus(activity);
        assertNotNull(focusedBefore);
        press(activity, KeyEvent.KEYCODE_BUTTON_R2);
        assertEquals(GameAchievementsPage.STOP_STATUS, panelPage(activity).focusedStop());
        View sink = focus(activity);
        assertNotNull("the main window keeps key focus", sink);
        assertTrue(sink != focusedBefore);

        press(activity, KeyEvent.KEYCODE_BUTTON_L2);
        assertEquals(CompanionPage.NO_STOP, panelPage(activity).focusedStop());
        // Not in the library here, so no Resume or Play: back to where the d-pad came from.
        assertSame(focusedBefore, focus(activity));
    }

    @Test
    public void thePanelGoing_takesThePageOff_andLeavesTheDpadOnTheMainScreen() throws Exception {
        setPanelShowing(true);
        MainActivity activity = start();
        GamePageFragment page = openPage(activity);
        press(activity, KeyEvent.KEYCODE_BUTTON_R2);
        assertEquals(GameAchievementsPage.STOP_STATUS, panelPage(activity).focusedStop());

        setPanelShowing(false);
        idle();

        assertNull(panelPage(activity));
        View focused = focus(activity);
        assertNotNull(focused);
        assertTrue("the d-pad is back on the page", isInside(page.requireView(), focused));
        // On the page's first stop (the status row, without buttons), not on the sink.
        assertEquals(R.id.gamePageStatus, focused.getId());
    }

    @Test
    public void leavingThePage_takesItsPageOffThePanel() throws Exception {
        setPanelShowing(true);
        MainActivity activity = start();
        openPage(activity);
        assertNotNull(panelPage(activity));

        activity.getOnBackPressedDispatcher().onBackPressed();
        idle();

        assertNull(panelPage(activity));
    }

    // The window's focused view. Not getCurrentFocus(): a Robolectric window is never given
    // window focus, and that answers null without it.
    private static View focus(MainActivity activity) {
        return activity.getWindow().getDecorView().findFocus();
    }

    private static boolean isInside(View ancestor, View view) {
        for (Object p = view; p instanceof View; p = ((View) p).getParent()) {
            if (p == ancestor) {
                return true;
            }
        }
        return false;
    }
}
