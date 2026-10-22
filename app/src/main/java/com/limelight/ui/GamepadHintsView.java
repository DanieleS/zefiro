package com.limelight.ui;

import android.content.Context;
import android.util.AttributeSet;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.VisibleForTesting;
import androidx.lifecycle.Observer;

import com.limelight.R;

import java.util.Arrays;
import java.util.Objects;

/**
 * A row of gamepad hints, "A Open October · B Up · LB RB Year": each hint is its buttons' glyphs
 * (the mocks' {@code .gk}) and what they do.
 *
 * <p>The row is shown while a gamepad is connected ({@link GamepadPresence}), on every screen and
 * device alike: the main screen, the companion panel, a phone with a pad plugged in. Without one
 * it is gone; a phone held in the hand has no buttons to name. It follows the pad live while
 * attached, so a pad connected or disconnected with the row on screen shows or hides it at once.
 *
 * <p>Hints that do not fit the width wrap onto another line, each hint kept whole, so a phone's
 * narrow window shows them all rather than cutting the last one off.
 *
 * Main thread only.
 */
public class GamepadHintsView extends ViewGroup {

    /** One hint: its buttons, then what they do. Immutable. */
    public static final class Hint {
        @NonNull
        public final String label;
        @NonNull
        private final String[] keys;

        /**
         * @param label what the buttons do ("Open October")
         * @param keys  the buttons, as their glyphs read ("A", "LB", "RB")
         */
        public Hint(@NonNull String label, @NonNull String... keys) {
            this.label = label;
            this.keys = keys.clone();
        }

        @NonNull
        public String[] keys() {
            return keys.clone();
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) {
                return true;
            }
            if (!(o instanceof Hint)) {
                return false;
            }
            Hint that = (Hint) o;
            return label.equals(that.label) && Arrays.equals(keys, that.keys);
        }

        @Override
        public int hashCode() {
            return Objects.hash(label, Arrays.hashCode(keys));
        }

        @NonNull
        @Override
        public String toString() {
            return Arrays.toString(keys) + " " + label;
        }

        // "LB RB Year": the buttons by name, then what they do.
        @NonNull
        String toSpoken() {
            StringBuilder out = new StringBuilder();
            for (String key : keys) {
                out.append(key).append(' ');
            }
            return out.append(label).toString();
        }
    }

    private Hint[] hints = new Hint[0];
    private final Observer<Boolean> padObserver = pad -> updateVisibility();
    private final int hintGap;
    private final int lineGap;

    public GamepadHintsView(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        hintGap = getResources().getDimensionPixelSize(R.dimen.hint_gap);
        lineGap = getResources().getDimensionPixelSize(R.dimen.hint_line_gap);
        setVisibility(GONE);
    }

    /** The hints, in order; none hides the row. An unchanged set is not laid out again. */
    public void setHints(@NonNull Hint... next) {
        if (!Arrays.equals(hints, next)) {
            hints = next.clone();
            rebuild();
        }
        updateVisibility();
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        // Delivers the current value at once, so the row is right from its first frame.
        GamepadPresence.connected().observeForever(padObserver);
    }

    @Override
    protected void onDetachedFromWindow() {
        GamepadPresence.connected().removeObserver(padObserver);
        super.onDetachedFromWindow();
    }

    // One child per hint: its glyphs and its label in a row of their own, so a line break never
    // parts a label from its buttons.
    private void rebuild() {
        removeAllViews();
        LayoutInflater inflater = LayoutInflater.from(getContext());
        int keyGap = getResources().getDimensionPixelSize(R.dimen.key_glyph_gap);
        int labelGap = getResources().getDimensionPixelSize(R.dimen.hint_label_gap);
        StringBuilder spoken = new StringBuilder();
        for (Hint hint : hints) {
            LinearLayout group = new LinearLayout(getContext());
            group.setOrientation(LinearLayout.HORIZONTAL);
            group.setGravity(Gravity.CENTER_VERTICAL);
            for (int k = 0; k < hint.keys.length; k++) {
                TextView key = (TextView) inflater.inflate(R.layout.view_gamepad_hint_key, group,
                        false);
                key.setText(hint.keys[k]);
                LinearLayout.LayoutParams params =
                        (LinearLayout.LayoutParams) key.getLayoutParams();
                params.setMarginStart(k == 0 ? 0 : keyGap);
                group.addView(key, params);
            }
            TextView label = (TextView) inflater.inflate(R.layout.view_gamepad_hint_label, group,
                    false);
            label.setText(hint.label);
            LinearLayout.LayoutParams params = (LinearLayout.LayoutParams) label.getLayoutParams();
            params.setMarginStart(hint.keys.length > 0 ? labelGap : 0);
            // Shrinks (and ellipsizes) only when this one hint is wider than the whole row.
            params.width = 0;
            params.weight = 1;
            group.addView(label, params);
            addView(group, new LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT));
            if (spoken.length() > 0) {
                spoken.append(", ");
            }
            spoken.append(hint.toSpoken());
        }
        // Read as one line: the glyphs on their own are letters without meaning.
        setContentDescription(spoken.toString());
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int widthMode = MeasureSpec.getMode(widthMeasureSpec);
        int padding = getPaddingStart() + getPaddingEnd();
        int available = widthMode == MeasureSpec.UNSPECIFIED ? Integer.MAX_VALUE
                : Math.max(0, MeasureSpec.getSize(widthMeasureSpec) - padding);
        int count = getChildCount();
        int[] widths = new int[count];
        for (int i = 0; i < count; i++) {
            View child = getChildAt(i);
            // Each hint at its natural width, then held to the row if it is wider on its own.
            child.measure(MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED),
                    MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED));
            if (child.getMeasuredWidth() > available) {
                child.measure(MeasureSpec.makeMeasureSpec(available, MeasureSpec.EXACTLY),
                        MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED));
            }
            widths[i] = child.getMeasuredWidth();
        }
        int[] lines = breakLines(widths, hintGap, available);
        int lineCount = count == 0 ? 0 : lines[count - 1] + 1;
        int widest = 0;
        int height = 0;
        for (int line = 0, i = 0; line < lineCount; line++) {
            int lineWidth = 0;
            int lineHeight = 0;
            for (; i < count && lines[i] == line; i++) {
                lineWidth += (lineWidth > 0 ? hintGap : 0) + widths[i];
                lineHeight = Math.max(lineHeight, getChildAt(i).getMeasuredHeight());
            }
            widest = Math.max(widest, lineWidth);
            height += (line > 0 ? lineGap : 0) + lineHeight;
        }
        setMeasuredDimension(
                resolveSize(widest + padding, widthMeasureSpec),
                resolveSize(height + getPaddingTop() + getPaddingBottom(), heightMeasureSpec));
    }

    @Override
    protected void onLayout(boolean changed, int l, int t, int r, int b) {
        int count = getChildCount();
        int available = Math.max(0, r - l - getPaddingLeft() - getPaddingRight());
        int[] widths = new int[count];
        for (int i = 0; i < count; i++) {
            widths[i] = getChildAt(i).getMeasuredWidth();
        }
        int[] lines = breakLines(widths, hintGap, available);
        boolean rtl = getLayoutDirection() == LAYOUT_DIRECTION_RTL;
        int top = getPaddingTop();
        for (int i = 0; i < count; ) {
            int line = lines[i];
            int lineHeight = 0;
            for (int j = i; j < count && lines[j] == line; j++) {
                lineHeight = Math.max(lineHeight, getChildAt(j).getMeasuredHeight());
            }
            int x = 0;
            for (; i < count && lines[i] == line; i++) {
                View child = getChildAt(i);
                int w = child.getMeasuredWidth();
                int h = child.getMeasuredHeight();
                int left = rtl ? r - l - getPaddingRight() - x - w : getPaddingLeft() + x;
                int childTop = top + (lineHeight - h) / 2;
                child.layout(left, childTop, left + w, childTop + h);
                x += w + hintGap;
            }
            top += lineHeight + lineGap;
        }
    }

    /**
     * Which line each hint goes on: as many as fit on a line, in order, then the next line. A
     * hint wider than the row has a line of its own.
     *
     * @param widths    each hint's width
     * @param gap       the space between two hints on a line
     * @param available the row's width
     * @return each hint's line, from 0
     */
    @VisibleForTesting
    static int[] breakLines(@NonNull int[] widths, int gap, int available) {
        int[] lines = new int[widths.length];
        int line = 0;
        long used = 0;
        for (int i = 0; i < widths.length; i++) {
            if (used > 0 && used + gap + widths[i] > available) {
                line++;
                used = 0;
            }
            used += (used > 0 ? gap : 0) + widths[i];
            lines[i] = line;
        }
        return lines;
    }

    private void updateVisibility() {
        setVisibility(hints.length > 0 && GamepadPresence.isConnected() ? VISIBLE : GONE);
    }
}
