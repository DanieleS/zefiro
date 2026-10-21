package com.limelight.stats;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.os.SystemClock;
import android.util.AttributeSet;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;

import androidx.annotation.ColorInt;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;

import com.limelight.R;

/**
 * Time played per day (a week, a month) or per month (a year), as the Stats mock draws it: one
 * series, so one ink. The picked bar is the signal; the others a sunk step of it, a day without
 * play barely there, and what has not happened yet (days after today, months after this one)
 * dimmer still, two pixels tall and out of reach of the d-pad and of touch.
 *
 * <p>The picked bar is today's until another is picked, by touch or, while the view has focus,
 * with the d-pad's left and right. It stays lit whether or not the view has focus; the ring
 * around it is drawn only while the view has focus, so the chart keeps showing what was picked
 * while the d-pad is elsewhere (on the strip, or on the companion panel) without claiming focus.
 * A week's and a year's bars carry their value above them ("3:20", "42h"), the picked one's in
 * the signal.
 *
 * <p>Above the bars sits a tooltip ({@link #setTooltip}), pinned to the top so it never covers a
 * neighbouring bar, with a caret down to the picked one: what the bar stands for, and on a
 * year's month how to open it, which A (or a second tap) does ({@link OnBarActivatedListener}).
 * With the d-pad on the chart that is the A glyph and "Open"; otherwise (by touch, or with the
 * pad elsewhere, where A would not do it) "Open" and a chevron. While that month is on its way a
 * spinner takes their place.
 *
 * <p>Bars before the first one recorded ({@link #setPickableFrom}) are drawn as the future is:
 * nothing is known of them, which is not the same as nothing played.
 *
 * <p>In compact form it is the game page's twelve-week sparkline: no labels, no tooltip, the
 * last bar (this week) in focus, and nothing to pick.
 *
 * <p>Drawn by hand rather than with a charting library: there are at most 31 rounded bars and a
 * row of letters, and drawing them here keeps the d-pad selection, the labels on exactly the
 * days that need them, and the app's palette all in one place. Main thread only.
 */
public class StatsBarsView extends View {

    /** Told which bar is picked now, or -1 when none is. */
    public interface OnSelectionChangedListener {
        void onSelectionChanged(int index);
    }

    /** Told when the picked bar is activated: A or the d-pad's centre on it, or a second tap. */
    public interface OnBarActivatedListener {
        void onBarActivated(int index);
    }

    private long[] values = new long[0];
    @Nullable
    private String[] labels;
    @Nullable
    private String[] valueLabels;
    private int defaultIndex = -1;
    private int selected = -1;
    // Bars from here on have not happened yet; values.length when every bar has.
    private int futureFrom;
    // Bars before this one predate the first recorded session; 0 when every bar is recorded.
    private int pickableFrom;
    private boolean compact;
    @Nullable
    private OnSelectionChangedListener listener;
    @Nullable
    private OnBarActivatedListener activatedListener;

    @Nullable
    private CharSequence tooltip;
    private boolean tooltipAction;
    private boolean tooltipLoading;
    @Nullable
    private final CharSequence baseDescription;

    private final Paint barPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint zeroPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint futurePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint onPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint ringPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint labelPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint labelOnPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint labelFuturePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint tipPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint tipStrokePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint tipTextPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint glyphPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint glyphTextPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint spinnerPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint chevronPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();
    private final Path caret = new Path();
    private final Rect textBounds = new Rect();
    private final float density;
    private final String openLabel;

    public StatsBarsView(Context context) {
        this(context, null);
    }

    public StatsBarsView(Context context, AttributeSet attrs) {
        super(context, attrs);
        density = getResources().getDisplayMetrics().density;
        float scaled = getResources().getDisplayMetrics().scaledDensity;
        baseDescription = getContentDescription();
        openLabel = context.getString(R.string.stats_tooltip_open);

        barPaint.setColor(ContextCompat.getColor(context, R.color.chart_bar));
        zeroPaint.setColor(ContextCompat.getColor(context, R.color.chart_bar_zero));
        futurePaint.setColor(ContextCompat.getColor(context, R.color.chart_bar_future));
        int signal = ContextCompat.getColor(context, R.color.signal);
        onPaint.setColor(signal);
        ringPaint.setColor(signal);
        ringPaint.setStyle(Paint.Style.STROKE);
        ringPaint.setStrokeWidth(2 * density);

        labelPaint.setColor(ContextCompat.getColor(context, R.color.content_muted));
        labelPaint.setTextSize(10 * scaled);
        labelPaint.setTextAlign(Paint.Align.CENTER);
        labelOnPaint.set(labelPaint);
        labelOnPaint.setColor(signal);
        labelOnPaint.setFakeBoldText(true);
        labelFuturePaint.set(labelPaint);
        labelFuturePaint.setColor(ContextCompat.getColor(context, R.color.outline));

        tipPaint.setColor(ContextCompat.getColor(context, R.color.signal_container));
        tipStrokePaint.setColor(signal);
        tipStrokePaint.setStyle(Paint.Style.STROKE);
        tipStrokePaint.setStrokeWidth(density);
        tipTextPaint.setColor(ContextCompat.getColor(context, R.color.content_primary));
        tipTextPaint.setTextSize(11 * scaled);
        tipTextPaint.setTypeface(Typeface.MONOSPACE);
        glyphPaint.setColor(signal);
        glyphTextPaint.setColor(ContextCompat.getColor(context, R.color.on_signal));
        glyphTextPaint.setTextSize(9 * scaled);
        glyphTextPaint.setTypeface(Typeface.DEFAULT_BOLD);
        glyphTextPaint.setTextAlign(Paint.Align.CENTER);
        spinnerPaint.setColor(signal);
        spinnerPaint.setStyle(Paint.Style.STROKE);
        spinnerPaint.setStrokeWidth(1.5f * density);
        spinnerPaint.setStrokeCap(Paint.Cap.ROUND);
        chevronPaint.set(spinnerPaint);
        chevronPaint.setStrokeJoin(Paint.Join.ROUND);
    }

    /**
     * @param labels      one per bar, or null for none (an empty string leaves a bar unlabelled)
     * @param valueLabels one per bar drawn above it, or null for none
     * @param focusIndex  the bar picked until another is (today's, or the one remembered), or -1
     */
    public void setData(long[] values, @Nullable String[] labels, @Nullable String[] valueLabels,
                        int focusIndex) {
        this.values = values != null ? values.clone() : new long[0];
        this.labels = labels;
        this.valueLabels = valueLabels;
        this.defaultIndex = focusIndex;
        this.selected = -1;
        this.futureFrom = this.values.length;
        this.pickableFrom = 0;
        invalidate();
        notifySelection();
    }

    /**
     * Marks the bars from {@code index} on as not happened yet: drawn dim and two pixels tall,
     * and skipped by the d-pad and touch. The number of bars, or more, for none. Call after
     * {@link #setData}, which clears it.
     */
    public void setFutureFrom(int index) {
        futureFrom = Math.max(0, Math.min(values.length, index));
        invalidate();
    }

    /**
     * Marks the bars before {@code index} as unrecorded (before GameActivity's first session):
     * drawn as the future is, and skipped by the d-pad and touch. 0 for none. Call after
     * {@link #setData}, which clears it, with a focus index at or after it.
     */
    public void setPickableFrom(int index) {
        pickableFrom = Math.max(0, Math.min(values.length, index));
        invalidate();
    }

    /** The sparkline form: no labels, no picking. */
    public void setCompact(boolean compact) {
        this.compact = compact;
        setFocusable(!compact);
        invalidate();
    }

    /**
     * The colour of the bars not picked; {@code chart_bar} by default. The game page's weeks
     * use their own, a step towards the signal, as the mock draws them.
     */
    public void setBarColor(@ColorInt int color) {
        barPaint.setColor(color);
        invalidate();
    }

    public void setOnSelectionChangedListener(@Nullable OnSelectionChangedListener listener) {
        this.listener = listener;
    }

    public void setOnBarActivatedListener(@Nullable OnBarActivatedListener listener) {
        this.activatedListener = listener;
    }

    /**
     * The tooltip over the picked bar, or null for none.
     *
     * @param actionA whether A opens the bar (a year's month): adds the glyph and "Open"
     * @param loading whether what A opened is on its way: a spinner in the glyph's place
     */
    public void setTooltip(@Nullable CharSequence text, boolean actionA, boolean loading) {
        tooltip = text;
        tooltipAction = actionA;
        tooltipLoading = loading;
        // Read as part of the chart, since the tooltip is only drawn.
        if (text == null || baseDescription == null) {
            setContentDescription(text != null ? text : baseDescription);
        } else {
            setContentDescription(baseDescription + ", " + text);
        }
        invalidate();
    }

    /** The picked bar: the one picked, or the default (today's); -1 for none. */
    public int getFocusIndex() {
        if (selected >= 0 && selected < values.length) {
            return selected;
        }
        return defaultIndex >= 0 && defaultIndex < values.length ? defaultIndex : -1;
    }

    private void select(int index) {
        int clamped = Math.max(pickableFrom, Math.min(futureFrom - 1, index));
        if (futureFrom <= pickableFrom || clamped == getFocusIndex()) {
            return;
        }
        selected = clamped;
        invalidate();
        notifySelection();
    }

    private void notifySelection() {
        if (listener != null) {
            listener.onSelectionChanged(getFocusIndex());
        }
    }

    private boolean activate() {
        int index = getFocusIndex();
        if (activatedListener == null || index < pickableFrom || index < 0
                || index >= futureFrom) {
            return false;
        }
        activatedListener.onBarActivated(index);
        return true;
    }

    @Override
    protected void onFocusChanged(boolean gainFocus, int direction,
                                  @Nullable Rect previouslyFocusedRect) {
        super.onFocusChanged(gainFocus, direction, previouslyFocusedRect);
        // The ring follows focus; the picked bar's fill does not.
        invalidate();
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (!compact && futureFrom > 0) {
            int current = getFocusIndex();
            switch (keyCode) {
                case KeyEvent.KEYCODE_DPAD_LEFT:
                    // Picking starts from the picked bar, or from the last one that happened.
                    select(current < 0 ? futureFrom - 1 : current - 1);
                    return true;
                case KeyEvent.KEYCODE_DPAD_RIGHT:
                    select(current < 0 ? futureFrom - 1 : current + 1);
                    return true;
                case KeyEvent.KEYCODE_DPAD_CENTER:
                case KeyEvent.KEYCODE_ENTER:
                case KeyEvent.KEYCODE_NUMPAD_ENTER:
                case KeyEvent.KEYCODE_BUTTON_A:
                    if (event.getRepeatCount() == 0 && activate()) {
                        return true;
                    }
                    break;
                default:
                    break;
            }
        }
        return super.onKeyDown(keyCode, event);
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (compact || values.length == 0) {
            return super.onTouchEvent(event);
        }
        if (event.getAction() == MotionEvent.ACTION_UP) {
            float width = getWidth() - getPaddingLeft() - getPaddingRight();
            if (width > 0) {
                int index = (int) ((event.getX() - getPaddingLeft()) / (width / values.length));
                if (index >= pickableFrom && index < futureFrom) {
                    // A second tap on the picked bar does what A does: the touch way into a
                    // year's month, whose tooltip says "Open".
                    if (index == getFocusIndex()) {
                        activate();
                    } else {
                        select(index);
                    }
                }
            }
            performClick();
        }
        return true;
    }

    @Override
    public boolean performClick() {
        return super.performClick();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        int n = values.length;
        if (n == 0) {
            return;
        }
        float left = getPaddingLeft();
        float top = getPaddingTop();
        float width = getWidth() - left - getPaddingRight();
        float height = getHeight() - top - getPaddingBottom();
        if (width <= 0 || height <= 0) {
            return;
        }

        Paint.FontMetrics metrics = labelPaint.getFontMetrics();
        float textHeight = metrics.descent - metrics.ascent;
        // The tooltip's own row, with its caret, above the tallest bar's value.
        float tipSpace = compact ? 0 : tipHeight() + 8 * density;
        float valueSpace = !compact && valueLabels != null ? textHeight + 4 * density : 0;
        float labelSpace = !compact && labels != null ? textHeight + 6 * density : 0;
        float barArea = height - tipSpace - valueSpace - labelSpace;
        if (barArea <= 0) {
            return;
        }

        // Scaled to what has happened and is recorded: a dim bar is never the tallest.
        long max = 1;
        for (int i = compact ? 0 : pickableFrom; i < Math.min(n, futureFrom); i++) {
            max = Math.max(max, values[i]);
        }

        float cell = width / n;
        float barWidth = cell * (compact ? 0.72f : n > 14 ? 0.7f : 0.56f);
        float radius = Math.min((n > 14 ? 2 : 4) * density, barWidth / 2);
        float minHeight = 2 * density;
        int focus = compact ? n - 1 : getFocusIndex();
        float baseline = top + tipSpace + valueSpace + barArea;
        boolean ring = !compact && isFocused();

        for (int i = 0; i < n; i++) {
            // Not happened yet, or before the first recorded session: drawn alike.
            boolean future = i >= futureFrom || (!compact && i < pickableFrom);
            boolean on = !future && i == focus;
            float cx = left + cell * i + cell / 2;
            float barHeight = future ? minHeight
                    : Math.max(minHeight, values[i] * barArea / (float) max);
            rect.set(cx - barWidth / 2, baseline - barHeight, cx + barWidth / 2, baseline);
            Paint paint;
            if (on) {
                paint = onPaint;
            } else if (future) {
                paint = futurePaint;
            } else if (values[i] == 0 && !compact) {
                paint = zeroPaint;
            } else {
                paint = barPaint;
            }
            canvas.drawRoundRect(rect, radius, radius, paint);
            if (on && ring) {
                // The mock's ring: a dark gap, then the signal, around the picked bar.
                float gap = 3 * density;
                rect.inset(-gap, -gap);
                canvas.drawRoundRect(rect, radius + gap, radius + gap, ringPaint);
            }

            String value = valueSpace > 0 && valueLabels != null && i < valueLabels.length
                    ? valueLabels[i] : null;
            if (value != null && !value.isEmpty() && !future) {
                canvas.drawText(value, cx, baseline - barHeight - 4 * density - metrics.descent,
                        on ? labelOnPaint : labelPaint);
            }
            String label = labelSpace > 0 && labels != null && i < labels.length ? labels[i] : null;
            if (label != null && !label.isEmpty()) {
                canvas.drawText(label, cx, baseline + 6 * density - metrics.ascent,
                        on ? labelOnPaint : future ? labelFuturePaint : labelPaint);
            }
        }

        if (!compact && tooltip != null && focus >= 0) {
            drawTooltip(canvas, left + cell * focus + cell / 2, left, left + width, top);
        }
    }

    private float tipHeight() {
        Paint.FontMetrics m = tipTextPaint.getFontMetrics();
        return (m.descent - m.ascent) + 8 * density;
    }

    /**
     * The tooltip, in the room kept for it above the tallest bar, centred over the picked one as
     * far as the chart allows, with a caret down to it: the bar's caption, then how to open it
     * (the A glyph and "Open" with the d-pad on the chart, else "Open" and a chevron), or a
     * spinner while the month is on its way.
     */
    private void drawTooltip(Canvas canvas, float cx, float minX, float maxX, float top) {
        String text = String.valueOf(tooltip);
        float pad = 8 * density;
        float gap = 6 * density;
        float height = tipHeight();
        float glyphHeight = height - 10 * density;
        float textWidth = tipTextPaint.measureText(text);
        // The A glyph only while A reaches this chart; by touch the tooltip is the way in.
        boolean byPad = isFocused();
        float glyphWidth = 0;
        float openWidth = 0;
        if (tooltipLoading) {
            glyphWidth = glyphHeight;
        } else if (tooltipAction && byPad) {
            glyphTextPaint.getTextBounds("A", 0, 1, textBounds);
            glyphWidth = Math.max(16 * density, textBounds.width() + 6 * density);
            openWidth = gap + tipTextPaint.measureText(openLabel);
        } else if (tooltipAction) {
            openWidth = tipTextPaint.measureText(openLabel) + 3 * density + glyphHeight / 2;
        }
        float actionWidth = glyphWidth + openWidth;
        float width = pad + textWidth + (actionWidth > 0 ? gap + actionWidth : 0) + pad;
        float x0 = Math.max(minX, Math.min(maxX - width, cx - width / 2));
        float y0 = top;
        rect.set(x0, y0, x0 + width, y0 + height);
        float corner = 7 * density;
        canvas.drawRoundRect(rect, corner, corner, tipPaint);
        canvas.drawRoundRect(rect, corner, corner, tipStrokePaint);
        drawCaret(canvas, cx, x0 + corner, x0 + width - corner, y0 + height);

        Paint.FontMetrics m = tipTextPaint.getFontMetrics();
        float baseline = y0 + height / 2 - (m.ascent + m.descent) / 2;
        float x = x0 + pad;
        canvas.drawText(text, x, baseline, tipTextPaint);
        x += textWidth + gap;
        float gy = y0 + (height - glyphHeight) / 2;
        if (tooltipLoading) {
            // A small arc going round, redrawn every frame while the month is on its way.
            float inset = spinnerPaint.getStrokeWidth();
            rect.set(x + inset, gy + inset, x + glyphHeight - inset, gy + glyphHeight - inset);
            float start = (SystemClock.uptimeMillis() % 1000) * 360f / 1000f;
            canvas.drawArc(rect, start, 270, false, spinnerPaint);
            postInvalidateOnAnimation();
        } else if (tooltipAction && byPad) {
            rect.set(x, gy, x + glyphWidth, gy + glyphHeight);
            canvas.drawRoundRect(rect, 4 * density, 4 * density, glyphPaint);
            Paint.FontMetrics g = glyphTextPaint.getFontMetrics();
            canvas.drawText("A", x + glyphWidth / 2,
                    gy + glyphHeight / 2 - (g.ascent + g.descent) / 2, glyphTextPaint);
            canvas.drawText(openLabel, x + glyphWidth + gap, baseline, tipTextPaint);
        } else if (tooltipAction) {
            canvas.drawText(openLabel, x, baseline, tipTextPaint);
            // A chevron the height of half the glyph, in the signal, after the word.
            float h = glyphHeight / 2;
            float cxs = x + tipTextPaint.measureText(openLabel) + 3 * density + h / 4;
            float cy = y0 + height / 2;
            caret.rewind();
            caret.moveTo(cxs, cy - h / 2);
            caret.lineTo(cxs + h / 2, cy);
            caret.lineTo(cxs, cy + h / 2);
            canvas.drawPath(caret, chevronPaint);
        }
    }

    /**
     * The tooltip's caret: a small notch under it pointing down to the picked bar, kept within
     * the tooltip's straight edge.
     */
    private void drawCaret(Canvas canvas, float cx, float minX, float maxX, float bottom) {
        float half = 4 * density;
        float depth = 4 * density;
        if (maxX - minX < 2 * half) {
            return;
        }
        float x = Math.max(minX + half, Math.min(maxX - half, cx));
        float stroke = tipStrokePaint.getStrokeWidth();
        // Filled from just inside the edge, so the outline under the notch is covered.
        caret.rewind();
        caret.moveTo(x - half, bottom - stroke);
        caret.lineTo(x, bottom + depth);
        caret.lineTo(x + half, bottom - stroke);
        caret.close();
        canvas.drawPath(caret, tipPaint);
        canvas.drawLine(x - half, bottom, x, bottom + depth, tipStrokePaint);
        canvas.drawLine(x, bottom + depth, x + half, bottom, tipStrokePaint);
    }
}
