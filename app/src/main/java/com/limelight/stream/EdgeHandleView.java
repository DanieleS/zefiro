package com.limelight.stream;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Rect;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.util.AttributeSet;
import android.util.TypedValue;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;

import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;

import com.limelight.R;

import java.util.Collections;

/**
 * The phone's way into the in-stream controls: a small pill on the right edge of the stream, as
 * on the approved Stream mock. A tap, or a swipe from it towards the middle, opens the side panel.
 *
 * <h3>Only its own rect</h3>
 *
 * The view is the touch zone (24 x 120dp, set by the layout) and the pill is drawn inside it, so
 * the zone is easy to hit while the mark stays small. Every touch that lands outside the zone
 * reaches the stream exactly as before; inside it, the handle takes the whole gesture, which is
 * the price of having one at all. A swipe in from the rest of the edge opens the panel too: the
 * game watches for it ({@link EdgeSwipe}) without taking the touches that are not one.
 *
 * <h3>Edge gestures</h3>
 *
 * With gesture navigation, a swipe in from the side is the system's Back, and it would win. On
 * API 29 and later the zone is excluded from the system's gestures; before that only the tap is
 * dependable. The rest of the edge is left to the system: it honours at most 200dp of exclusion
 * per edge, so with gesture navigation the swipe elsewhere along the edge may still be Back.
 *
 * <p>Main thread only, like any view.
 */
public class EdgeHandleView extends View {

    private static final int PILL_WIDTH_DP = 6;
    private static final int PILL_HEIGHT_DP = 56;
    private static final int PILL_CORNER_DP = 3;
    /** How far towards the middle a drag must travel to count as a swipe. */
    private static final int SWIPE_DP = 16;

    private final GradientDrawable pill = new GradientDrawable();
    private final int touchSlop;
    private final int swipeDistance;
    private final Rect exclusion = new Rect();

    @Nullable
    private Runnable onOpen;
    private float downX;
    private float downY;
    private boolean opened;

    public EdgeHandleView(Context context) {
        this(context, null);
    }

    public EdgeHandleView(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        touchSlop = ViewConfiguration.get(context).getScaledTouchSlop();
        swipeDistance = dp(SWIPE_DP);

        // Rounded on the side that faces the game only; the other side runs off the screen.
        float r = dp(PILL_CORNER_DP);
        pill.setCornerRadii(new float[]{r, r, 0, 0, 0, 0, r, r});
        pill.setColor(ContextCompat.getColor(context, R.color.edge_handle));

        setContentDescription(context.getString(R.string.stream_controls_open));
        // Never in the d-pad's path: the pad opens the controls with Start or Back, and focus
        // landing here would take it from the stream.
        setFocusable(false);
        setClickable(true);
        // It floats above the overlays by elevation alone; a shadow would only smear the edge.
        setOutlineProvider(null);
    }

    /** What a tap or a swipe does. */
    public void setOnOpenListener(@Nullable Runnable onOpen) {
        this.onOpen = onOpen;
    }

    @Override
    protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
        super.onLayout(changed, left, top, right, bottom);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            exclusion.set(0, 0, right - left, bottom - top);
            setSystemGestureExclusionRects(Collections.singletonList(exclusion));
        }
    }

    @Override
    protected void onDraw(Canvas canvas) {
        int w = getWidth();
        int h = getHeight();
        int pw = dp(PILL_WIDTH_DP);
        int ph = Math.min(dp(PILL_HEIGHT_DP), h);
        int top = (h - ph) / 2;
        pill.setBounds(w - pw, top, w, top + ph);
        // The mock's 35% at rest, so it never competes with the game; full under the finger.
        pill.setAlpha(isPressed() ? 255 : 90);
        pill.draw(canvas);
    }

    @SuppressLint("ClickableViewAccessibility")
    @Override
    public boolean onTouchEvent(MotionEvent event) {
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                downX = event.getX();
                downY = event.getY();
                opened = false;
                setPressed(true);
                invalidate();
                return true;
            case MotionEvent.ACTION_MOVE:
                // Opened mid-drag, as soon as the swipe reads as one, so the panel follows the
                // finger's intent rather than waiting for it to lift.
                if (!opened && downX - event.getX() >= swipeDistance) {
                    open();
                }
                return true;
            case MotionEvent.ACTION_UP:
                if (!opened && Math.abs(event.getX() - downX) < touchSlop
                        && Math.abs(event.getY() - downY) < touchSlop) {
                    performClick();
                }
                release();
                return true;
            case MotionEvent.ACTION_CANCEL:
                release();
                return true;
            default:
                return true;
        }
    }

    @Override
    public boolean performClick() {
        super.performClick();
        open();
        return true;
    }

    private void open() {
        opened = true;
        if (onOpen != null) {
            onOpen.run();
        }
    }

    private void release() {
        setPressed(false);
        invalidate();
    }

    private int dp(int value) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value,
                getResources().getDisplayMetrics()));
    }
}
