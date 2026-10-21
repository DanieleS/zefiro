package com.limelight.companion;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.limelight.LimeLog;

/**
 * The companion panel's page container: draws a {@link CompanionPage} in place of the browsing
 * spotlight. Used by {@link CompanionPresentation} only, which decides when a page is shown.
 *
 * <p>The view is made once per kind of page and rebound for every new value: a renderer of
 * another class (the user went from a game's page to Stats) replaces it, the same renderer
 * (a focus move, a filter) binds into it. It is inflated with the presentation's context, so the
 * panel's density applies, not the main screen's.
 *
 * <p>The panel is best-effort, as {@link CompanionDisplayManager} says: a renderer that throws is
 * logged and the page is not drawn, and {@link #show} says so, so the presentation falls back to
 * the spotlight. That same value is not tried again until another one replaces it, so a broken
 * page logs once rather than on every redraw.
 *
 * Main thread only.
 */
final class CompanionPageHost {

    private final ViewGroup container;
    private final LayoutInflater inflater;

    @Nullable
    private CompanionPageRenderer renderer;
    @Nullable
    private View view;
    // The value last drawn: what a tap acts on, since it is what the user saw.
    @Nullable
    private CompanionPage shown;
    // The value whose renderer threw, not retried while it stands.
    @Nullable
    private CompanionPage failed;

    private final CompanionPageRenderer.PageTaps taps = stopId -> {
        CompanionPage page = shown;
        if (page != null) {
            CompanionDisplayManager.onPageTap(page, stopId);
        }
    };

    CompanionPageHost(@NonNull ViewGroup container, @NonNull LayoutInflater inflater) {
        this.container = container;
        this.inflater = inflater;
    }

    /**
     * Draws {@code page}.
     *
     * @return whether it is on screen; false when its renderer failed, and the container is
     * hidden again
     */
    boolean show(@NonNull CompanionPage page) {
        if (page.equals(failed)) {
            hide();
            return false;
        }
        if (view != null && page.equals(shown)) {
            // The presentation redraws on every state change (a host polled online, the
            // backdrop landing); a bind for those would pull a list the user is scrolling on
            // the panel back to the focused stop, for a value that has not changed.
            container.setVisibility(View.VISIBLE);
            return true;
        }
        try {
            CompanionPageRenderer next = page.renderer();
            if (view == null || renderer == null || next.getClass() != renderer.getClass()) {
                container.removeAllViews();
                view = null;
                renderer = null;
                View created = next.create(inflater, container);
                container.addView(created);
                view = created;
                renderer = next;
            }
            renderer.bind(view, page, taps);
            shown = page;
            failed = null;
            container.setVisibility(View.VISIBLE);
            return true;
        } catch (RuntimeException e) {
            LimeLog.warning("CompanionPage: " + page.getClass().getSimpleName()
                    + " failed to draw: " + e);
            failed = page;
            // A half-bound view is not trusted with the next value either.
            container.removeAllViews();
            view = null;
            renderer = null;
            hide();
            return false;
        }
    }

    /** Takes the page off the panel. The view is kept for the next page of its kind. */
    void hide() {
        shown = null;
        container.setVisibility(View.GONE);
    }
}
