package com.limelight.main;

import android.content.Context;
import android.util.AttributeSet;
import android.view.FocusFinder;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.widget.LinearLayout;

import androidx.annotation.Nullable;

import com.limelight.R;

/**
 * The main screen's root, and the focus gate between its navigation and the section on screen.
 *
 * <p>Left to itself, Android's focus search picks the geometrically nearest view in the whole
 * window. Between a rail and a content area that is almost never what a d-pad user wants: RIGHT
 * from the third rail item lands on whatever content happens to sit level with it, not on the
 * tile the user left; UP from the top of a shelf can escape sideways into the rail. Every
 * directional search passes through here on its way to the window, and this decides instead.
 *
 * <h3>Zones</h3>
 *
 * <pre>
 *   ┌──────┬────────────────────────────┐
 *   │ rail │                            │   RIGHT from the rail ──▶ the section's restore target
 *   │ ──── │          section           │   LEFT  in the section ──▶ the section's own search;
 *   │avatar│                            │                            the checked rail item only
 *   └──────┴────────────────────────────┘                            when that finds nothing
 *
 *   ┌───────────────────────────────────┐
 *   │              section              │   DOWN  in the section ──▶ the section's own search;
 *   ├───────────────────────────────────┤                            the checked bar item only
 *   │            bottom bar             │                            when that finds nothing
 *   └───────────────────────────────────┘   UP    from the bar ──▶ the section's restore target
 * </pre>
 *
 * The rail column (its items and the avatar) is one zone: UP and DOWN move within it and stop at
 * its ends. Inside the section, the search is limited to the section, so focus never leaves it by
 * accident in a direction that has no exit. Views outside every zone are searched as usual, and
 * so are Tab and Shift+Tab everywhere.
 *
 * Main thread only, like all focus handling.
 */
public class MainRootLayout extends LinearLayout {

    /** What the gate needs to know about the section on screen. */
    public interface Gate {
        /** The current section's {@link SectionFocus#restoreFocusTarget()}, or null. */
        @Nullable
        View sectionRestoreTarget();

        /** The visible navigation widget's item for the checked section, or null. */
        @Nullable
        View checkedNavItem();
    }

    private ViewGroup railColumn;
    private ViewGroup sectionContainer;
    private ViewGroup bottomBar;
    @Nullable
    private Gate gate;

    public MainRootLayout(Context context) {
        super(context);
    }

    public MainRootLayout(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    @Override
    protected void onFinishInflate() {
        super.onFinishInflate();
        railColumn = findViewById(R.id.navRailColumn);
        sectionContainer = findViewById(R.id.sectionContainer);
        bottomBar = findViewById(R.id.navBottom);
    }

    public void setGate(@Nullable Gate gate) {
        this.gate = gate;
    }

    @Override
    public View focusSearch(View focused, int direction) {
        if (gate == null || focused == null || railColumn == null || sectionContainer == null
                || bottomBar == null) {
            return super.focusSearch(focused, direction);
        }
        // Tab and Shift+Tab from a hardware keyboard walk the whole window in order; the zones
        // are for the d-pad's directions only, and holding Tab inside one would trap it there.
        if (direction == FOCUS_FORWARD || direction == FOCUS_BACKWARD) {
            return super.focusSearch(focused, direction);
        }

        if (isWithin(focused, railColumn)) {
            return fromRail(focused, direction);
        }
        if (isWithin(focused, sectionContainer)) {
            return fromSection(focused, direction);
        }
        if (isWithin(focused, bottomBar)) {
            return fromBottomBar(focused, direction);
        }
        return super.focusSearch(focused, direction);
    }

    private View fromRail(View focused, int direction) {
        if (direction == FOCUS_RIGHT) {
            View target = sectionEntry(FOCUS_RIGHT);
            return target != null ? target : focused;
        }
        if (direction == FOCUS_UP || direction == FOCUS_DOWN) {
            View next = FocusFinder.getInstance().findNextFocus(railColumn, focused, direction);
            return next != null ? next : focused;
        }
        // LEFT (the only direction left): the rail is the screen's edge. Returning the focused
        // view keeps focus where it is without letting the key go on to anything else.
        return focused;
    }

    private View fromSection(View focused, int direction) {
        View inSection = FocusFinder.getInstance().findNextFocus(sectionContainer, focused, direction);
        if (inSection != null) {
            return inSection;
        }
        boolean compact = bottomBar.getVisibility() == VISIBLE;
        if ((direction == FOCUS_LEFT && !compact) || (direction == FOCUS_DOWN && compact)) {
            View item = gate.checkedNavItem();
            if (item != null && item.isShown() && item.isFocusable()) {
                return item;
            }
        }
        return focused;
    }

    private View fromBottomBar(View focused, int direction) {
        if (direction == FOCUS_UP) {
            View target = sectionEntry(FOCUS_UP);
            return target != null ? target : focused;
        }
        if (direction == FOCUS_LEFT || direction == FOCUS_RIGHT) {
            View next = FocusFinder.getInstance().findNextFocus(bottomBar, focused, direction);
            return next != null ? next : focused;
        }
        // DOWN (the only direction left): the bar is the screen's bottom edge.
        return focused;
    }

    /**
     * Where focus enters the section: its own restore target when it has a usable one, otherwise
     * the first focusable view a search from that side of the section finds.
     */
    @Nullable
    private View sectionEntry(int direction) {
        View target = gate.sectionRestoreTarget();
        if (target != null && target.isShown() && target.isFocusable()
                && isWithin(target, sectionContainer)) {
            return target;
        }
        return FocusFinder.getInstance().findNextFocus(sectionContainer, null, direction);
    }

    private static boolean isWithin(View view, ViewGroup ancestor) {
        if (view == ancestor) {
            return true;
        }
        ViewParent p = view.getParent();
        while (p instanceof View) {
            if (p == ancestor) {
                return true;
            }
            p = p.getParent();
        }
        return false;
    }
}
