package com.limelight.main;

import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.widget.Button;
import android.widget.FrameLayout;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.FragmentActivity;
import androidx.lifecycle.MutableLiveData;

import com.limelight.companion.CompanionPage;
import com.limelight.companion.CompanionPageRenderer;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

/**
 * The virtual focus: the d-pad handed down to a page on the panel and back, with the main window
 * keeping key focus throughout (on the sink while the d-pad is down there).
 */
@Config(sdk = {33}, shadows = {com.limelight.shadows.ShadowMoonBridge.class,
        com.limelight.shadows.ShadowGameManager.class})
@RunWith(RobolectricTestRunner.class)
public class PanelFocusTest {

    /** A filter row over two rows, standing in for a game's achievements. */
    private static final class TestPage extends CompanionPage {
        private static final CompanionPageRenderer RENDERER = new CompanionPageRenderer() {
            @NonNull
            @Override
            public View create(@NonNull LayoutInflater inflater, @NonNull ViewGroup parent) {
                return new View(parent.getContext());
            }

            @Override
            public void bind(@NonNull View view, @NonNull CompanionPage page,
                             @NonNull PageTaps taps) {
            }
        };

        final String title;

        TestPage(String title, int focusedStop) {
            super(focusedStop);
            this.title = title;
        }

        @NonNull
        @Override
        public CompanionPageRenderer renderer() {
            return RENDERER;
        }

        @NonNull
        @Override
        public CompanionPage withFocus(int stop) {
            return new TestPage(title, stop);
        }

        @Override
        protected boolean sameContent(@NonNull CompanionPage other) {
            return title.equals(((TestPage) other).title);
        }

        @Override
        protected int contentHash() {
            return Objects.hashCode(title);
        }
    }

    /** The feed's part: what was published last, for whom. */
    private static final class RecordingPages implements CompanionPages {
        CompanionPage page;
        PageOwner owner;
        boolean tapped;

        @Override
        public boolean pageTappedJustNow() {
            return tapped;
        }

        @Override
        public void publish(@NonNull PageOwner owner, @NonNull CompanionPage page) {
            this.owner = owner;
            this.page = page;
        }

        @Override
        public void clear(@NonNull PageOwner owner) {
            if (owner == this.owner) {
                this.owner = null;
                this.page = null;
            }
        }
    }

    private static final class TestOwner implements PanelFocus.Owner {
        CompanionPage page = new TestPage("Hades", CompanionPage.NO_STOP);
        PageFocus stops = new PageFocus(Arrays.asList(
                new int[]{0, 1, 2}, new int[]{100}, new int[]{101}));
        final List<PageFocus.Nav> exits = new ArrayList<>();
        final List<Integer> activated = new ArrayList<>();
        final List<Boolean> left = new ArrayList<>();
        int backs;
        boolean handlesBack;

        @Nullable
        @Override
        public CompanionPage bottomPage() {
            return page;
        }

        @NonNull
        @Override
        public PageFocus bottomStops() {
            return stops;
        }

        @Override
        public void onExit(@NonNull PageFocus.Nav direction) {
            exits.add(direction);
        }

        @Override
        public void onActivate(int stop) {
            activated.add(stop);
        }

        @Override
        public boolean onBottomBack() {
            backs++;
            return handlesBack;
        }

        @Override
        public void onBottomLeft(boolean restored) {
            left.add(restored);
        }

        @Override
        public void onPageTap(int stopId) {
        }
    }

    private ActivityController<FragmentActivity> controller;
    private FrameLayout root;
    private Button top;
    private RecordingPages pages;
    private MutableLiveData<Boolean> panelShowing;
    private TestOwner owner;
    private PanelFocus focus;

    @Before
    public void setUp() {
        controller = Robolectric.buildActivity(FragmentActivity.class).setup();
        FragmentActivity activity = controller.get();
        root = new FrameLayout(activity);
        top = new Button(activity);
        top.setFocusable(true);
        top.setFocusableInTouchMode(true);
        root.addView(top);
        activity.setContentView(root);

        pages = new RecordingPages();
        panelShowing = new MutableLiveData<>(true);
        owner = new TestOwner();
        focus = new PanelFocus(root, activity, pages, panelShowing, owner);
        focus.publish();
        assertTrue(top.requestFocus());
    }

    private static KeyEvent down(int code) {
        return new KeyEvent(KeyEvent.ACTION_DOWN, code);
    }

    @Test
    public void upTopThePageIsPublishedWithoutAStop() {
        assertSame(owner, pages.owner);
        assertEquals(CompanionPage.NO_STOP, pages.page.focusedStop());
        assertEquals(PanelFocus.Zone.TOP, focus.zone());
        // Keys are not the page's while the d-pad is up top.
        assertFalse(focus.onKey(down(KeyEvent.KEYCODE_DPAD_DOWN)));
    }

    @Test
    public void enterBottomMovesFocusToTheSink() {
        assertTrue(focus.enterBottom(100));

        assertEquals(PanelFocus.Zone.BOTTOM, focus.zone());
        assertFalse(top.isFocused());
        View sink = root.findFocus();
        assertNotNull("the main window keeps key focus", sink);
        assertNotSame(top, sink);
        assertEquals(1, sink.getLayoutParams().width);
        assertNull("the sink draws no ring", sink.getBackground());
        assertEquals(100, pages.page.focusedStop());
    }

    @Test
    public void theDpadMovesThroughThePageAndExitsToTheOwner() {
        focus.enterBottom(1);
        assertTrue(focus.onKey(down(KeyEvent.KEYCODE_DPAD_RIGHT)));
        assertEquals(2, pages.page.focusedStop());
        assertTrue(focus.onKey(down(KeyEvent.KEYCODE_DPAD_DOWN)));
        assertEquals(100, pages.page.focusedStop());
        // A row end: consumed, nothing moves.
        assertTrue(focus.onKey(down(KeyEvent.KEYCODE_DPAD_RIGHT)));
        assertEquals(100, pages.page.focusedStop());

        focus.onKey(down(KeyEvent.KEYCODE_DPAD_UP));
        assertTrue(focus.onKey(down(KeyEvent.KEYCODE_DPAD_UP)));
        assertEquals(Arrays.asList(PageFocus.Nav.UP), owner.exits);

        assertTrue(focus.onKey(down(KeyEvent.KEYCODE_BUTTON_A)));
        assertEquals(Arrays.asList(0), owner.activated);
        // Not the page's key.
        assertFalse(focus.onKey(down(KeyEvent.KEYCODE_BUTTON_Y)));
    }

    @Test
    public void leaveBottomRestoresTheRememberedView() {
        focus.enterBottom(100);
        focus.leaveBottom(true);

        assertEquals(PanelFocus.Zone.TOP, focus.zone());
        assertTrue(top.isFocused());
        assertEquals(CompanionPage.NO_STOP, pages.page.focusedStop());
        assertEquals(Arrays.asList(true), owner.left);
    }

    @Test
    public void bCallsTheOwnerAndByDefaultLeavesTheBottom() {
        focus.enterBottom(100);
        assertTrue(focus.onKey(down(KeyEvent.KEYCODE_BUTTON_B)));
        assertEquals(1, owner.backs);
        assertEquals(PanelFocus.Zone.TOP, focus.zone());
        assertTrue(top.isFocused());

        // An owner that uses B itself (a climb from a month) keeps the d-pad down there.
        owner.handlesBack = true;
        focus.enterBottom(100);
        assertTrue(focus.onKey(down(KeyEvent.KEYCODE_BUTTON_B)));
        assertEquals(2, owner.backs);
        assertEquals(PanelFocus.Zone.BOTTOM, focus.zone());
    }

    @Test
    public void touchModeLeavesTheBottom() throws Exception {
        focus.enterBottom(100);

        enterTouchMode(root.getViewTreeObserver());

        assertEquals(PanelFocus.Zone.TOP, focus.zone());
        assertEquals(CompanionPage.NO_STOP, pages.page.focusedStop());
        assertEquals(Arrays.asList(false), owner.left);
    }

    @Test
    public void touchModeBroughtByATapOnThePanelKeepsTheBottom() throws Exception {
        focus.enterBottom(100);
        View sink = root.findFocus();

        // A tap on the page: the main window enters touch mode when it gets focus back.
        pages.tapped = true;
        enterTouchMode(root.getViewTreeObserver());

        assertEquals(PanelFocus.Zone.BOTTOM, focus.zone());
        assertTrue("the sink keeps key focus for the reclaim", sink.isFocused());
        assertEquals(100, pages.page.focusedStop());
        assertTrue(owner.left.isEmpty());

        // A touch on the main screen later still takes the d-pad up.
        pages.tapped = false;
        enterTouchMode(root.getViewTreeObserver());
        assertEquals(PanelFocus.Zone.TOP, focus.zone());
        assertEquals(Arrays.asList(false), owner.left);
    }

    @Test
    public void thePanelGoingAwayLeavesTheBottom() {
        focus.enterBottom(100);
        panelShowing.setValue(false);

        assertEquals(PanelFocus.Zone.TOP, focus.zone());
        assertEquals(Arrays.asList(false), owner.left);
        // And with no panel, the d-pad cannot go down at all.
        assertFalse(focus.enterBottom(100));
    }

    @Test
    public void aStopTheNewPageLacksMovesToItsFirst() {
        focus.enterBottom(101);
        owner.stops = new PageFocus(Arrays.asList(new int[]{0, 1, 2}, new int[]{100}));
        focus.publish();
        assertEquals(0, pages.page.focusedStop());

        // No page any more: the d-pad goes back up, and the panel is cleared.
        owner.page = null;
        focus.publish();
        assertEquals(PanelFocus.Zone.TOP, focus.zone());
        assertTrue(top.isFocused());
        assertNull(pages.page);
    }

    @Test
    public void hidingTheSectionClearsThePage() {
        focus.enterBottom(100);
        focus.onSectionHidden();

        assertEquals(PanelFocus.Zone.TOP, focus.zone());
        assertEquals(Arrays.asList(false), owner.left);
        assertNull("the panel belongs to the section shown now", pages.page);
    }

    @Test
    public void aHiddenScreenLeavingTheBottomLateKeepsThePanelOfTheOneOnTop() {
        focus.enterBottom(100);
        // The section shown now publishes before the hidden one's sink lets go of focus.
        CompanionPages.PageOwner other = stopId -> { };
        CompanionPage theirs = new TestPage("Stats", CompanionPage.NO_STOP);
        pages.publish(other, theirs);
        root.setVisibility(View.GONE);

        focus.leaveBottom(false);
        focus.onSectionHidden();

        assertEquals(PanelFocus.Zone.TOP, focus.zone());
        assertSame(other, pages.owner);
        assertSame(theirs, pages.page);
    }

    @Test
    public void destroyingTheViewClearsThePage() {
        focus.enterBottom(100);
        controller.pause().stop().destroy();

        assertNull(pages.page);
        assertEquals(PanelFocus.Zone.TOP, focus.zone());
    }

    // ViewRootImpl tells the observer when the window enters touch mode; a test has no touches
    // to send through it, so it is told directly.
    private static void enterTouchMode(ViewTreeObserver observer) throws Exception {
        Method dispatch = ViewTreeObserver.class.getDeclaredMethod(
                "dispatchOnTouchModeChanged", boolean.class);
        dispatch.setAccessible(true);
        dispatch.invoke(observer, true);
    }
}
