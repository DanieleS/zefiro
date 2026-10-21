package com.limelight.companion;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.List;
import java.util.Objects;

/**
 * A screen's lower half, drawn on the companion panel in place of the browsing spotlight: a
 * game's achievements, the Stats remote, a setting's choices.
 *
 * <p>A page is a value, as everything in {@link CompanionState} is. It carries all the renderer
 * draws, including which of its stops the d-pad is on ({@link #focusedStop()}), so a focus move
 * on the main screen is a new value and the panel redraws from it alone. Nothing here calls back:
 * taps reach the screen that published the page through
 * {@link CompanionDisplayManager#setPageActions}, never through the page.
 *
 * <p>It may reference immutable models ({@code Achievement}, {@code AppStats}) and a
 * {@code HostSession} for icons. A session is a connection, not a callback: holding one lets the
 * panel fetch a picture, never act for a screen that has gone. So the "values only" invariant of
 * {@link CompanionState} holds.
 *
 * <h3>Stops</h3>
 *
 * A stop is anything on the page the d-pad can be on, named by an int the subclass chooses
 * (a filter segment, a row). Ids are the page's own; {@link #NO_STOP} means the d-pad is up on
 * the main screen and the panel draws no ring.
 *
 * <h3>Equality</h3>
 *
 * Two pages are equal when they would draw the same: same class, same focused stop, same content
 * ({@link #sameContent}). {@link CompanionState#setPage} drops an equal push, so a screen may
 * publish on every bind without redrawing the panel. Lists compare by identity first, then by
 * content ({@link #sameList}): a screen re-publishing the list it already holds costs nothing.
 */
public abstract class CompanionPage {

    /** The d-pad is not on the page. */
    public static final int NO_STOP = -1;

    private final int focusedStop;

    protected CompanionPage(int focusedStop) {
        this.focusedStop = focusedStop < 0 ? NO_STOP : focusedStop;
    }

    /** What draws this kind of page: a stateless singleton, so the panel can tell kinds apart. */
    @NonNull
    public abstract CompanionPageRenderer renderer();

    /** The stop the d-pad is on, or {@link #NO_STOP}. */
    public final int focusedStop() {
        return focusedStop;
    }

    /** This page with the d-pad on {@code stop} ({@link #NO_STOP} for none). */
    @NonNull
    public abstract CompanionPage withFocus(int stop);

    /**
     * Whether {@code other}, a page of exactly this class, draws the same content. The focused
     * stop is compared already.
     */
    protected abstract boolean sameContent(@NonNull CompanionPage other);

    /** A hash of what {@link #sameContent} compares. */
    protected abstract int contentHash();

    /** Lists by identity first, so an unchanged list is not walked; then by content. */
    protected static boolean sameList(@Nullable List<?> a, @Nullable List<?> b) {
        return a == b || (a != null && a.equals(b));
    }

    @Override
    public final boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || o.getClass() != getClass()) {
            return false;
        }
        CompanionPage that = (CompanionPage) o;
        return focusedStop == that.focusedStop && sameContent(that);
    }

    @Override
    public final int hashCode() {
        return Objects.hash(getClass(), focusedStop, contentHash());
    }
}
