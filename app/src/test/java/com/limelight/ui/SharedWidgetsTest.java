package com.limelight.ui;

import android.content.Context;
import android.util.TypedValue;
import android.view.View;

import androidx.appcompat.view.ContextThemeWrapper;
import androidx.test.core.app.ApplicationProvider;

import com.limelight.R;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * The widgets the Stats, game and Settings screens share, built as a layout would build them
 * (the two-argument constructor) before any screen puts them in one.
 */
@Config(sdk = {33})
@RunWith(RobolectricTestRunner.class)
public class SharedWidgetsTest {

    private Context context;

    @Before
    public void setUp() {
        context = new ContextThemeWrapper(
                ApplicationProvider.getApplicationContext(), R.style.AppTheme);
    }

    private int dp(int value) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value,
                context.getResources().getDisplayMetrics()));
    }

    @Test
    public void segmentsAreBuiltChosenAndAtLeastATouchTargetTall() {
        SegmentedControlView control = new SegmentedControlView(context, null);
        assertTrue(control.getMinimumHeight() >= dp(48));

        control.setSegments("All", "Unlocked", "Locked");
        assertEquals(3, control.getChildCount());
        assertEquals(3, control.getSegmentCount());

        control.setSelected(1);
        assertEquals(1, control.getSelectedIndex());
        for (int i = 0; i < 3; i++) {
            assertEquals(i == 1, control.getChildAt(i).isSelected());
            assertTrue("inline segments take the d-pad", control.getChildAt(i).isFocusable());
        }
    }

    @Test
    public void onThePanelSegmentsAreNotFocusableAndTheRingIsVirtual() {
        SegmentedControlView control = new SegmentedControlView(context, null);
        control.setSegments("Week", "Month", "Year");

        control.setInline(false);
        control.setVirtualFocus(2);
        for (int i = 0; i < 3; i++) {
            View segment = control.getChildAt(i);
            assertFalse(segment.isFocusable());
            assertEquals(i == 2, segment.isActivated());
        }

        // Back inline, the ring is the real focus's again.
        control.setInline(true);
        for (int i = 0; i < 3; i++) {
            assertFalse(control.getChildAt(i).isActivated());
        }
    }

    @Test
    public void hintsShowAwayFromTheMainScreenAndHideWhenEmpty() {
        GamepadHintsView hints = new GamepadHintsView(context, null);
        assertEquals(View.GONE, hints.getVisibility());

        // No MainHost around this context: the panel's case, where hints are shown.
        hints.setHints(new GamepadHintsView.Hint("Up", "B"));
        assertEquals(View.VISIBLE, hints.getVisibility());
        assertEquals("a glyph and its label", 2, hints.getChildCount());

        hints.setHints();
        assertEquals(View.GONE, hints.getVisibility());
    }
}
