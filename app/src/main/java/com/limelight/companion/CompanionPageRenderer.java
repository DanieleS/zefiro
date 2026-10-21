package com.limelight.companion;

import android.graphics.Rect;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;

/**
 * Draws one kind of {@link CompanionPage} on the companion panel.
 *
 * <p>A renderer is a stateless singleton: whatever it needs between binds lives in the view it
 * created (tags, adapters), and whatever the page shows lives in the page. The panel creates the
 * view once per kind and binds every new value into it, so a focus move is a rebind, not a new
 * layout.
 *
 * <h3>Contract</h3>
 *
 * <ul>
 *   <li>{@link #bind} is called for every new value. It marks the focused stop
 *       {@code activated}, and every other stop not, so the ring is drawn by the same
 *       {@code state_activated} background as everywhere else on the panel.</li>
 *   <li>It keeps the focused stop in view without taking focus: the panel's window is never
 *       focusable, so {@code requestFocus()} is never called on it. A list scrolls with
 *       {@code RecyclerView.scrollToPosition}; anything else asks with {@link #keepInView}.</li>
 *   <li>Taps go through {@link PageTaps}, naming the stop, never straight to a screen.</li>
 * </ul>
 *
 * Main thread only.
 */
public interface CompanionPageRenderer {

    /** Where a renderer sends a tap on one of its stops. */
    interface PageTaps {
        void tap(int stopId);
    }

    /** Makes the page's view, with the panel's own inflater so the panel's density applies. */
    @NonNull
    View create(@NonNull LayoutInflater inflater, @NonNull ViewGroup parent);

    /** Draws {@code page} into a view this renderer created. */
    void bind(@NonNull View view, @NonNull CompanionPage page, @NonNull PageTaps taps);

    /**
     * Asks the stop's scrolling ancestors to bring it on screen, as focus would, without
     * focusing it. Posted, so a stop laid out by this very bind has its size first.
     */
    static void keepInView(@NonNull final View stop) {
        stop.post(() -> {
            if (stop.isAttachedToWindow()) {
                stop.requestRectangleOnScreen(new Rect(0, 0, stop.getWidth(), stop.getHeight()),
                        false);
            }
        });
    }
}
