package com.limelight.main;

import android.os.Build;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.DefaultLifecycleObserver;
import androidx.lifecycle.LifecycleOwner;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.Observer;

import com.limelight.companion.CompanionDisplayManager;
import com.limelight.companion.CompanionPage;

/**
 * The d-pad on the companion panel, for a screen whose lower half is drawn there: the virtual
 * focus.
 *
 * <p>The panel's window never takes key focus (see {@code CompanionPresentation}): the pad would
 * land on the panel's display and, on its way back, on whatever sits behind it. So the main
 * window keeps the keys, and while the d-pad is "down there" this steers a focus index through
 * the page's stops ({@link PageFocus}) and publishes it in the page, which the panel draws as the
 * ring. The screen's own focused view hands its focus to a <em>sink</em>, a 1×1 view with no
 * background, so the top screen shows no ring meanwhile.
 *
 * <h3>Invariant</h3>
 *
 * Exactly one screen draws the focus ring, and the main window never gives up key focus.
 *
 * <pre>
 *   TOP ──enterBottom(stop)──▶ BOTTOM     focus: top view ─▶ sink; page: NO_STOP ─▶ stop
 *    ▲                           │        d-pad moves the stop; A activates; B leaves
 *    └────leaveBottom(restore)───┘        restore: the remembered top view gets focus back
 *
 *   BOTTOM is left without restoring when: touch mode is entered (unless a tap on the panel
 *   just now brought it), the section is hidden, the view is destroyed, the panel goes away,
 *   or the sink loses focus some other way
 * </pre>
 *
 * <p>The owner keeps its own page current and calls {@link #publish()} whenever it changes (new
 * data, a filter); this adds the focused stop and hands it to {@link CompanionPages}. Taps on
 * the panel go straight to the owner ({@link CompanionPages.PageOwner}) and do not move the zone.
 *
 * <p>One per section screen that has a page, made with its view. Main thread only.
 */
public final class PanelFocus {

    /** Where the d-pad is: the main screen, or the page on the panel. */
    public enum Zone { TOP, BOTTOM }

    /** The screen whose lower half the page is. */
    public interface Owner extends CompanionPages.PageOwner {
        /** The page as it stands, focus aside; null when there is none to show. */
        @Nullable
        CompanionPage bottomPage();

        /** Its stops, row by row. */
        @NonNull
        PageFocus bottomStops();

        /**
         * The d-pad went off the page's first or last row. Leaving the bottom is the owner's
         * call.
         */
        void onExit(@NonNull PageFocus.Nav direction);

        /** A or the d-pad's centre on a stop. */
        void onActivate(int stop);

        /**
         * B (or back) while the d-pad is on the page.
         *
         * @return whether the owner handled it; by default it does not, and the d-pad goes back
         * up to the view it came from
         */
        default boolean onBottomBack() {
            return false;
        }

        /**
         * The d-pad left the page. Told after the fact, so the owner can place focus itself when
         * it was not restored (the panel went away and the lower half is now inline).
         *
         * @param restored whether the view the d-pad came from has focus again
         */
        default void onBottomLeft(boolean restored) {
        }
    }

    private final ViewGroup root;
    private final CompanionPages pages;
    private final LiveData<Boolean> panelShowing;
    private final Owner owner;
    private final View sink;

    private Zone zone = Zone.TOP;
    private int stop = CompanionPage.NO_STOP;
    // The top view the d-pad was on when it went down; given focus back on the way up.
    @Nullable
    private View remembered;
    // Set while leaving, so the sink's own loss of focus is not taken for an outside move.
    private boolean leaving;
    private boolean destroyed;
    @Nullable
    private ViewTreeObserver touchModeObserver;

    private final ViewTreeObserver.OnTouchModeChangeListener touchModeListener =
            this::onTouchModeChanged;

    private final Observer<Boolean> panelObserver = showing -> {
        if (!Boolean.TRUE.equals(showing)) {
            leaveBottom(false);
        }
    };

    /**
     * For a section screen: its pages go through {@link MainHost#companionPages()}, and the panel
     * is {@link CompanionDisplayManager#panelShowing()}. Call from {@code onViewCreated}.
     *
     * @param root the screen's root, which the sink is added to: a container a 1×1 child does
     *             not disturb (a FrameLayout), never a ScrollView, which takes one child only
     */
    @NonNull
    public static PanelFocus attach(@NonNull Fragment screen, @NonNull ViewGroup root,
                                    @NonNull Owner owner) {
        return new PanelFocus(root, screen.getViewLifecycleOwner(),
                ((MainHost) screen.requireActivity()).companionPages(),
                CompanionDisplayManager.panelShowing(), owner);
    }

    public PanelFocus(@NonNull ViewGroup root, @NonNull LifecycleOwner viewLifecycle,
                      @NonNull CompanionPages pages, @NonNull LiveData<Boolean> panelShowing,
                      @NonNull Owner owner) {
        this.root = root;
        this.pages = pages;
        this.panelShowing = panelShowing;
        this.owner = owner;

        sink = new View(root.getContext());
        sink.setFocusable(false);
        sink.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            sink.setDefaultFocusHighlightEnabled(false);
        }
        sink.setOnFocusChangeListener((v, hasFocus) -> {
            // Focus taken by something else (a touch on a top view, a key this did not see):
            // the d-pad is up there now, whatever the zone says.
            if (!hasFocus && !leaving) {
                leaveBottom(false);
            }
        });
        root.addView(sink, new ViewGroup.LayoutParams(1, 1));

        // Added to the root's observer even before it is attached: a detached view's observer
        // is merged into the window's on attach.
        touchModeObserver = root.getViewTreeObserver();
        touchModeObserver.addOnTouchModeChangeListener(touchModeListener);
        panelShowing.observe(viewLifecycle, panelObserver);
        viewLifecycle.getLifecycle().addObserver(new DefaultLifecycleObserver() {
            @Override
            public void onDestroy(@NonNull LifecycleOwner source) {
                destroy();
            }
        });
    }

    @NonNull
    public Zone zone() {
        return zone;
    }

    /** The stop the d-pad is on, or {@link CompanionPage#NO_STOP} while it is up top. */
    public int focusedStop() {
        return zone == Zone.BOTTOM ? stop : CompanionPage.NO_STOP;
    }

    /**
     * Takes the d-pad down to {@code stop} on the panel, or moves it there when it is down
     * already. Refused when there is no panel or no page: there is nowhere to draw the ring.
     *
     * @param stop where to land; the first enabled stop when it is not one
     * @return whether the d-pad is on the page now
     */
    public boolean enterBottom(int stop) {
        if (destroyed || !Boolean.TRUE.equals(panelShowing.getValue())
                || owner.bottomPage() == null) {
            return false;
        }
        PageFocus stops = owner.bottomStops();
        int target = stops.isEnabledStop(stop) ? stop : stops.first();
        if (target < 0) {
            return false;
        }
        if (zone == Zone.TOP) {
            View focused = root.findFocus();
            remembered = focused != sink ? focused : null;
            sink.setFocusable(true);
            sink.setFocusableInTouchMode(true);
            // A trigger pulled in touch mode (an axis is not a key, so it does not end touch
            // mode on its own): leaving touch mode is right, the pad is in use.
            boolean took = sink.isInTouchMode() ? sink.requestFocusFromTouch()
                    : sink.requestFocus();
            if (!took) {
                sink.setFocusable(false);
                remembered = null;
                return false;
            }
            zone = Zone.BOTTOM;
        }
        this.stop = target;
        publish();
        return true;
    }

    /**
     * Brings the d-pad back up. Nothing when it is up already.
     *
     * @param restore whether to give focus back to the view it came from
     */
    public void leaveBottom(boolean restore) {
        leave(restore, true, true);
    }

    // As leaveBottom; the owner is not told when its view is being destroyed. The page goes back
    // to the panel without its stop only when asked and while the screen is shown: a hidden
    // screen's publish would make it the owner again and take the panel, and its taps, from the
    // screen now on top.
    private void leave(boolean restore, boolean tellOwner, boolean republish) {
        if (zone != Zone.BOTTOM) {
            return;
        }
        zone = Zone.TOP;
        stop = CompanionPage.NO_STOP;
        leaving = true;
        View back = remembered;
        remembered = null;
        boolean restored = false;
        // Focus first, then the sink lets go: the other way round, the window would hand focus to
        // its first focusable view in between.
        if (restore && back != null && back.isAttachedToWindow() && back.isShown()) {
            restored = back.requestFocus();
        }
        sink.setFocusableInTouchMode(false);
        sink.setFocusable(false);
        leaving = false;
        if (!destroyed && republish && root.isShown()) {
            publish();
        }
        if (tellOwner) {
            owner.onBottomLeft(restored);
        }
    }

    // A touch on the main screen takes the d-pad up; the touch mode a tap on the panel brings
    // does not.
    private void onTouchModeChanged(boolean inTouchMode) {
        if (!inTouchMode) {
            return;
        }
        if (zone == Zone.BOTTOM && pages.pageTappedJustNow()) {
            // The tap on the panel, reaching this window as it gets focus back: taps do not move
            // the zone. The sink is focusable in touch mode, so it should still hold focus; if it
            // somehow does not, it takes it back, or the reclaim after the tap finds nothing.
            if (!sink.isFocused()) {
                sink.requestFocusFromTouch();
            }
            return;
        }
        leaveBottom(false);
    }

    /**
     * The section was hidden: the d-pad cannot stay on a page nobody can see the top of, and the
     * page comes off the panel, which belongs to the section shown now. The owner publishes again
     * when its section is shown.
     */
    public void onSectionHidden() {
        leave(false, true, false);
        pages.clear(owner);
    }

    /**
     * Keys while the d-pad is on the page; the owner calls it from its
     * {@link SectionKeys#onSectionKey} before its own bindings.
     *
     * @return whether the key was used: always false up top, and for keys the page does not take
     */
    public boolean onKey(@NonNull KeyEvent event) {
        if (zone != Zone.BOTTOM || event.getAction() != KeyEvent.ACTION_DOWN) {
            return false;
        }
        switch (event.getKeyCode()) {
            case KeyEvent.KEYCODE_DPAD_UP:
                return move(PageFocus.Nav.UP);
            case KeyEvent.KEYCODE_DPAD_DOWN:
                return move(PageFocus.Nav.DOWN);
            case KeyEvent.KEYCODE_DPAD_LEFT:
                return move(PageFocus.Nav.LEFT);
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                return move(PageFocus.Nav.RIGHT);
            case KeyEvent.KEYCODE_BUTTON_A:
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_NUMPAD_ENTER:
                // A held button repeats; one press is one activation.
                if (event.getRepeatCount() == 0) {
                    owner.onActivate(stop);
                }
                return true;
            case KeyEvent.KEYCODE_BUTTON_B:
            case KeyEvent.KEYCODE_BACK:
            case KeyEvent.KEYCODE_ESCAPE:
                if (event.getRepeatCount() == 0 && !owner.onBottomBack()) {
                    leaveBottom(true);
                }
                return true;
            default:
                return false;
        }
    }

    /**
     * Hands the owner's page to the panel with the d-pad's stop in it, or takes it away when the
     * owner has none. The owner calls it whenever its page changes. A stop the new page no
     * longer has moves to its first; a page with nowhere to stand sends the d-pad back up.
     */
    public void publish() {
        if (destroyed) {
            return;
        }
        CompanionPage page = owner.bottomPage();
        if (page == null) {
            leaveBottom(true);
            pages.clear(owner);
            return;
        }
        if (zone == Zone.BOTTOM) {
            PageFocus stops = owner.bottomStops();
            if (!stops.isEnabledStop(stop)) {
                int first = stops.first();
                if (first < 0) {
                    leaveBottom(true);
                    return;
                }
                stop = first;
            }
        }
        pages.publish(owner, page.withFocus(focusedStop()));
    }

    /** Takes the owner's page off the panel, the d-pad with it. */
    public void clear() {
        leaveBottom(false);
        pages.clear(owner);
    }

    private boolean move(@NonNull PageFocus.Nav direction) {
        int next = owner.bottomStops().move(stop, direction);
        if (next == PageFocus.EXIT_UP || next == PageFocus.EXIT_DOWN) {
            owner.onExit(direction);
        } else if (next != PageFocus.STAY && next != stop) {
            stop = next;
            publish();
        }
        return true;
    }

    // The view is going: the page goes with it, since its taps would reach a screen without one.
    private void destroy() {
        if (destroyed) {
            return;
        }
        destroyed = true;
        leave(false, false, false);
        pages.clear(owner);
        panelShowing.removeObserver(panelObserver);
        ViewTreeObserver observer = touchModeObserver;
        touchModeObserver = null;
        if (observer != null && observer.isAlive()) {
            observer.removeOnTouchModeChangeListener(touchModeListener);
        } else if (root.getViewTreeObserver().isAlive()) {
            root.getViewTreeObserver().removeOnTouchModeChangeListener(touchModeListener);
        }
        root.removeView(sink);
    }
}
