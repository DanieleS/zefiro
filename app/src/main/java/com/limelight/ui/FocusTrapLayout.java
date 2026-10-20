package com.limelight.ui;

import android.content.Context;
import android.util.AttributeSet;
import android.view.FocusFinder;
import android.view.View;
import android.widget.LinearLayout;

import androidx.annotation.Nullable;

/**
 * A vertical column that never lets focus out: a focus search that would leave it lands back on
 * the view that has focus.
 *
 * <p>Made for a panel drawn inside a window it shares with something that also takes focus (the
 * stream's own view, under the side panel). Without the trap, a d-pad press past the panel's
 * last row would hand focus to that view, and the next press would go to the game instead of
 * the panel the user is looking at. Main thread only, like any view.
 */
public class FocusTrapLayout extends LinearLayout {

    public FocusTrapLayout(Context context) {
        this(context, null);
    }

    public FocusTrapLayout(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        setOrientation(VERTICAL);
    }

    @Override
    public View focusSearch(View focused, int direction) {
        View next = FocusFinder.getInstance().findNextFocus(this, focused, direction);
        return next != null ? next : focused;
    }
}
