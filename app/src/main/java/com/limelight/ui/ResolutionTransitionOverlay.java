package com.limelight.ui;

import android.app.Activity;
import android.content.res.ColorStateList;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.RenderEffect;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.PixelCopy;
import android.view.SurfaceView;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.DecelerateInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;

import com.limelight.R;

/**
 * What the screen shows while the stream changes size: the last frame, blurred and dimmed, under a
 * status card that says what is going on and the size it is going to. The picture is the stream's
 * own, taken off its surface just before the request; the decoder restarts under it, and the card
 * fades out over the new picture once a frame of it has been shown. The card comes in a moment
 * late, so a quick change does not flash a spinner.
 *
 * <p>Takes the touches while it is up: the stream's coordinates are changing under them. Main
 * thread only.
 */
public final class ResolutionTransitionOverlay {
    // The frame is taken small: it is shown blurred anyway, and a full-size copy of a 4K surface
    // would be a lot of memory for it
    private static final int FRAME_LONG_SIDE = 480;
    private static final long CAPTURE_TIMEOUT_MS = 250;
    private static final long CARD_DELAY_MS = 120;
    private static final long CARD_FADE_IN_MS = 180;
    // Time for the compositor to put the new frame on screen before the card goes
    private static final long REVEAL_DELAY_MS = 64;
    private static final long REVEAL_FADE_MS = 220;

    private final ViewGroup parent;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final FrameLayout root;
    private final ImageView image;
    private final LinearLayout card;
    private final TextView status;
    private boolean disposed;

    public ResolutionTransitionOverlay(Activity activity, int width, int height) {
        parent = activity.findViewById(android.R.id.content);
        float density = activity.getResources().getDisplayMetrics().density;

        root = new FrameLayout(activity);
        // Consume the touches while the coordinate system is changing
        root.setClickable(true);
        root.setFocusable(false);
        root.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        root.setElevation(32 * density);

        image = new ImageView(activity);
        image.setScaleType(ImageView.ScaleType.FIT_CENTER);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            image.setRenderEffect(RenderEffect.createBlurEffect(24f, 24f, Shader.TileMode.CLAMP));
        }
        root.addView(image, new FrameLayout.LayoutParams(-1, -1));
        View dim = new View(activity);
        dim.setBackgroundColor(ContextCompat.getColor(activity, R.color.scrim_soft));
        root.addView(dim, new FrameLayout.LayoutParams(-1, -1));

        card = new LinearLayout(activity);
        card.setOrientation(LinearLayout.HORIZONTAL);
        card.setGravity(Gravity.CENTER_VERTICAL);
        int padH = Math.round(16 * density);
        int padV = Math.round(12 * density);
        card.setPadding(padH, padV, padH, padV);
        GradientDrawable background = new GradientDrawable();
        background.setColor(ContextCompat.getColor(activity, R.color.surface_container_high));
        background.setCornerRadius(activity.getResources().getDimension(R.dimen.shape_corner_medium));
        background.setStroke(Math.max(1, Math.round(0.75f * density)),
                ContextCompat.getColor(activity, R.color.outline_variant));
        card.setBackground(background);
        card.setElevation(6 * density);

        ProgressBar spinner = new ProgressBar(activity);
        spinner.setIndeterminate(true);
        spinner.setIndeterminateTintList(ColorStateList.valueOf(
                ContextCompat.getColor(activity, R.color.signal)));
        spinner.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        LinearLayout.LayoutParams spinnerParams = new LinearLayout.LayoutParams(
                Math.round(22 * density), Math.round(22 * density));
        spinnerParams.setMarginEnd(Math.round(14 * density));
        card.addView(spinner, spinnerParams);

        LinearLayout labels = new LinearLayout(activity);
        labels.setOrientation(LinearLayout.VERTICAL);
        status = new TextView(activity);
        status.setText(R.string.resolution_switch_adjusting);
        status.setTextColor(ContextCompat.getColor(activity, R.color.content_primary));
        status.setTextSize(13f);
        status.setTypeface(Typeface.create("sans-serif", Typeface.BOLD));
        status.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        labels.addView(status);
        TextView target = new TextView(activity);
        target.setText(activity.getString(R.string.resolution_switch_target, width, height));
        target.setTextColor(ContextCompat.getColor(activity, R.color.content_secondary));
        target.setTextSize(12f);
        target.setFontFeatureSettings("tnum");
        target.setPadding(0, Math.round(4 * density), 0, 0);
        labels.addView(target);
        card.addView(labels);

        FrameLayout.LayoutParams cardParams = new FrameLayout.LayoutParams(-2, -2,
                Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        cardParams.bottomMargin = Math.round(48 * density);
        cardParams.leftMargin = Math.round(16 * density);
        cardParams.rightMargin = Math.round(16 * density);
        root.addView(card, cardParams);

        parent.addView(root, new ViewGroup.LayoutParams(-1, -1));

        card.setAlpha(0f);
        card.setTranslationY(8 * density);
        card.animate().alpha(1f).translationY(0f).setStartDelay(CARD_DELAY_MS)
                .setDuration(CARD_FADE_IN_MS).setInterpolator(new DecelerateInterpolator()).start();
    }

    /**
     * Takes the stream's last frame off its surface and holds it, then runs {@code ready}, once.
     * When there is nothing to take (an old Android, a surface that is not there, no memory, a
     * copy that does not finish) the card shows over the black and {@code ready} runs just the
     * same: the change never waits on its picture for long.
     */
    public void holdFrameOf(@Nullable SurfaceView view, Runnable ready) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N || view == null || view.getWidth() <= 0
                || view.getHeight() <= 0 || view.getHolder().getSurface() == null
                || !view.getHolder().getSurface().isValid()) {
            ready.run();
            return;
        }
        double scale = Math.min(1.0, (double) FRAME_LONG_SIDE / Math.max(view.getWidth(), view.getHeight()));
        final Bitmap frame;
        try {
            frame = Bitmap.createBitmap(Math.max(1, (int) (view.getWidth() * scale)),
                    Math.max(1, (int) (view.getHeight() * scale)), Bitmap.Config.ARGB_8888);
        } catch (OutOfMemoryError e) {
            ready.run();
            return;
        }
        final boolean[] delivered = {false};
        final Runnable timeout = () -> {
            if (!delivered[0]) {
                delivered[0] = true;
                ready.run();
            }
        };
        handler.postDelayed(timeout, CAPTURE_TIMEOUT_MS);
        try {
            PixelCopy.request(view, frame, result -> {
                handler.removeCallbacks(timeout);
                if (delivered[0]) {
                    // The change went on without it
                    return;
                }
                delivered[0] = true;
                if (result == PixelCopy.SUCCESS && !disposed) {
                    image.setImageBitmap(frame);
                    root.setBackgroundColor(Color.BLACK);
                }
                ready.run();
            }, handler);
        } catch (RuntimeException e) {
            handler.removeCallbacks(timeout);
            if (!delivered[0]) {
                delivered[0] = true;
                ready.run();
            }
        }
    }

    /** The change could not be done in place: the stream is being restored by a reconnect. */
    public void reconnecting() {
        status.setText(R.string.resolution_switch_reconnecting);
    }

    /** The new picture is up: fades the overlay out and removes it. */
    public void reveal() {
        handler.postDelayed(() -> {
            if (disposed) {
                return;
            }
            root.animate().alpha(0f).setDuration(REVEAL_FADE_MS)
                    .setInterpolator(new DecelerateInterpolator())
                    .withEndAction(this::dispose).start();
        }, REVEAL_DELAY_MS);
    }

    public void dispose() {
        if (disposed) {
            return;
        }
        disposed = true;
        handler.removeCallbacksAndMessages(null);
        root.animate().cancel();
        card.animate().cancel();
        parent.removeView(root);
        // The bitmap may still be in a display list the render thread holds: drop it, never recycle
        image.setImageDrawable(null);
    }
}
