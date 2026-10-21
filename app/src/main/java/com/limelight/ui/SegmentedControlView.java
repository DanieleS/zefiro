package com.limelight.ui;

import android.content.Context;
import android.util.AttributeSet;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.limelight.R;

import java.util.ArrayList;
import java.util.List;

/**
 * A row of mutually exclusive choices (Week / Month / Year, All / Unlocked / Locked), after the
 * mocks' {@code .seg}: a dark track, the chosen segment filled with the signal's container and
 * labelled in the signal.
 *
 * <p>The same view draws on both screens, so it has two modes:
 *
 * <pre>
 *   inline   on the main screen: each segment is focusable, the d-pad moves between them, and a
 *            press or a tap chooses it here and tells the listener
 *   panel    on the companion panel, whose window never takes focus: nothing is focusable, the
 *            ring is the virtual focus ({@link #setVirtualFocus}, drawn as state_activated),
 *            and a tap only tells the listener. The page value says what is chosen, and the
 *            next bind draws it
 * </pre>
 *
 * Every segment is a 48dp touch target, its pill drawn inset. Main thread only.
 */
public class SegmentedControlView extends LinearLayout {

    /** No segment has the virtual focus. */
    public static final int NO_FOCUS = -1;

    /**
     * Told when a segment is chosen: inline, only when the choice changes; on the panel, on
     * every tap.
     */
    public interface OnSegmentChosenListener {
        void onSegmentChosen(int index);
    }

    private final List<TextView> segments = new ArrayList<>();
    private int selected = -1;
    private boolean inline = true;
    private int virtualFocus = NO_FOCUS;
    @Nullable
    private OnSegmentChosenListener listener;

    public SegmentedControlView(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        setOrientation(HORIZONTAL);
        setBaselineAligned(false);
        setBackgroundResource(R.drawable.segment_track_bg);
        int padding = getResources().getDimensionPixelSize(R.dimen.segment_track_padding);
        setPaddingRelative(padding, 0, padding, 0);
        setMinimumHeight(getResources().getDimensionPixelSize(R.dimen.action_size));
    }

    /**
     * The segments' labels, in order ("All 41", "Unlocked 18"). The same number of labels only
     * relabels the segments, so a count changing does not lose focus or the choice.
     */
    public void setSegments(@NonNull String... labels) {
        if (labels.length != segments.size()) {
            removeAllViews();
            segments.clear();
            LayoutInflater inflater = LayoutInflater.from(getContext());
            for (int i = 0; i < labels.length; i++) {
                TextView segment = (TextView) inflater.inflate(R.layout.view_segment, this, false);
                final int index = i;
                segment.setOnClickListener(v -> onSegmentClicked(index));
                segments.add(segment);
                addView(segment);
            }
            if (selected >= labels.length) {
                selected = -1;
            }
            if (virtualFocus >= labels.length) {
                virtualFocus = NO_FOCUS;
            }
            applyMode();
        }
        for (int i = 0; i < labels.length; i++) {
            segments.get(i).setText(labels[i]);
        }
    }

    /** Chooses a segment, drawing it filled; -1 for none. Does not tell the listener. */
    public void setSelected(int index) {
        selected = index >= 0 && index < segments.size() ? index : -1;
        for (int i = 0; i < segments.size(); i++) {
            segments.get(i).setSelected(i == selected);
        }
    }

    /** The chosen segment, or -1 for none. */
    public int getSelectedIndex() {
        return selected;
    }

    public void setOnSegmentChosenListener(@Nullable OnSegmentChosenListener listener) {
        this.listener = listener;
    }

    /** Inline on the main screen (focusable segments), or drawn on the panel (virtual focus). */
    public void setInline(boolean inline) {
        if (this.inline == inline) {
            return;
        }
        this.inline = inline;
        if (inline) {
            virtualFocus = NO_FOCUS;
        }
        applyMode();
    }

    /** On the panel: the segment the d-pad is on, ringed; {@link #NO_FOCUS} for none. */
    public void setVirtualFocus(int index) {
        virtualFocus = !inline && index >= 0 && index < segments.size() ? index : NO_FOCUS;
        for (int i = 0; i < segments.size(); i++) {
            segments.get(i).setActivated(i == virtualFocus);
        }
    }

    public int getSegmentCount() {
        return segments.size();
    }

    /** A segment's view, for a screen placing focus on it; null when there is no such segment. */
    @Nullable
    public View getSegmentView(int index) {
        return index >= 0 && index < segments.size() ? segments.get(index) : null;
    }

    private void applyMode() {
        for (int i = 0; i < segments.size(); i++) {
            TextView segment = segments.get(i);
            segment.setFocusable(inline);
            segment.setSelected(i == selected);
            segment.setActivated(i == virtualFocus);
        }
    }

    private void onSegmentClicked(int index) {
        if (!inline) {
            // The page decides what is chosen; the tap only says which.
            if (listener != null) {
                listener.onSegmentChosen(index);
            }
            return;
        }
        if (index == selected) {
            return;
        }
        setSelected(index);
        if (listener != null) {
            listener.onSegmentChosen(index);
        }
    }
}
