package com.limelight.stream;

import android.content.Context;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.util.AttributeSet;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;

import com.limelight.R;
import com.limelight.binding.video.PerfStats;

/**
 * The one line drawn over the stream: a health dot and "{fps} FPS · {ms} ms · {loss}%", after
 * the approved Stream mock's OSD pill.
 *
 * <h3>Why bound from {@link PerfStats} and not from text</h3>
 *
 * The decoder used to format its own overlay text, in two variants, and the overlay showed
 * whatever string arrived. The dot needs the numbers, not a sentence, and so does every other
 * surface that shows them (the companion panel lays them out itself), so the HUD formats its own
 * line from the same snapshot the dot is judged on. The two can then never disagree.
 *
 * <h3>When it shows</h3>
 *
 * Three independent inputs, each owned by a different part of {@code Game}, decide it:
 *
 * <pre>
 *   suppressed (PiP)  ── hidden, whatever else holds
 *   status != null    ── shown: the status text, with a red dot (reconnecting, network lost)
 *   statsShown        ── shown once a first window of numbers has arrived
 * </pre>
 *
 * A status outranks the numbers because it explains why they stopped: a frozen "120 FPS" while
 * the network is gone would be a lie. It shows whether or not the user asked for the stats,
 * since it is the only word the stream gives that it is trying to come back.
 *
 * <p>Main thread only, like any view.
 */
public class StreamHud extends LinearLayout {

    private final GradientDrawable dotShape;
    private final TextView text;

    private boolean statsShown;
    private boolean suppressed;
    private boolean poorConnection;
    @Nullable
    private PerfStats stats;
    @Nullable
    private CharSequence status;

    public StreamHud(Context context) {
        this(context, null);
    }

    public StreamHud(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        setOrientation(HORIZONTAL);
        setGravity(Gravity.CENTER_VERTICAL);

        int padH = dp(10);
        int padV = dp(4);
        setPadding(padH, padV, padH, padV);
        GradientDrawable pill = new GradientDrawable();
        pill.setColor(ContextCompat.getColor(context, R.color.hud_scrim));
        pill.setCornerRadius(dp(10));
        setBackground(pill);

        dotShape = new GradientDrawable();
        dotShape.setShape(GradientDrawable.OVAL);
        View dot = new View(context);
        dot.setBackground(dotShape);
        int dotSize = dp(6);
        LayoutParams dotParams = new LayoutParams(dotSize, dotSize);
        dotParams.setMarginEnd(dp(7));
        addView(dot, dotParams);

        text = new TextView(context);
        text.setTypeface(Typeface.MONOSPACE);
        text.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        text.setTextColor(ContextCompat.getColor(context, R.color.content_primary));
        text.setSingleLine(true);
        text.setIncludeFontPadding(false);
        addView(text, new LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT));

        // Purely a readout: it must never take a touch or focus from the stream under it.
        setClickable(false);
        setFocusable(false);
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);

        render();
    }

    /** Whether the user wants the numbers on screen (the Overlay tile and its setting). */
    public void setStatsShown(boolean shown) {
        statsShown = shown;
        render();
    }

    /** Hidden whatever else holds, for picture-in-picture where the window is the game alone. */
    public void setSuppressed(boolean suppressed) {
        this.suppressed = suppressed;
        render();
    }

    /** The host's last word on the link; it holds the dot at amber or worse until it says okay. */
    public void setPoorConnection(boolean poor) {
        poorConnection = poor;
        render();
    }

    /** A new second of numbers. Kept even while hidden, so turning the HUD on shows them at once. */
    public void bind(@NonNull PerfStats stats) {
        this.stats = stats;
        render();
    }

    /**
     * A message that takes the line over, such as the reconnect countdown, or null to give it
     * back to the numbers.
     */
    public void setStatus(@Nullable CharSequence status) {
        this.status = status;
        render();
    }

    private void render() {
        StreamHealth.Level level;
        if (status != null) {
            text.setText(status);
            level = StreamHealth.Level.BAD;
        } else if (stats != null) {
            text.setText(getResources().getString(R.string.stream_hud_stats,
                    stats.totalFps, stats.networkLatencyMs,
                    Float.isNaN(stats.networkDropsPercent) ? 0f : stats.networkDropsPercent));
            level = StreamHealth.classify(stats, poorConnection);
        } else {
            text.setText(null);
            level = poorConnection ? StreamHealth.Level.WARN : StreamHealth.Level.GOOD;
        }
        dotShape.setColor(ContextCompat.getColor(getContext(), colorOf(level)));

        boolean visible = !suppressed && (status != null || (statsShown && stats != null));
        setVisibility(visible ? VISIBLE : GONE);
    }

    private static int colorOf(StreamHealth.Level level) {
        switch (level) {
            case BAD:
                return R.color.health_bad;
            case WARN:
                return R.color.health_warn;
            default:
                return R.color.health_good;
        }
    }

    private int dp(int value) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value,
                getResources().getDisplayMetrics()));
    }
}
