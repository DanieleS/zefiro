package com.limelight.main;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.limelight.library.LibraryApp;

/**
 * Where the d-pad is on the main screen, as far as the companion panel cares: on a game, on a
 * host, or on nothing it can describe.
 *
 * <p>Every surface that can hold a game (the hero, a shelf, See all, Search) and every one that
 * stands for a host (the Library's chip, a PC card) reports here, through the one listener the
 * main screen hands out ({@link MainHost#gameFocus()}), rather than each list wiring the panel
 * itself. The old library did the latter, attaching a listener to every tile a RecyclerView laid
 * out, and the panel ended up fed from places nobody remembered.
 *
 * <p>Reports are cheap and may come on every focus move; the listener settles them (see
 * {@link CompanionFeed}). Main thread only.
 */
public interface GameFocusListener {

    /** A game of the selected host took focus. */
    void focusGame(@NonNull LibraryApp app);

    /**
     * A host took focus (a PC card), or the section is about one without a game in focus (the
     * Library's chip, its state panel, no games at all).
     *
     * @param hostUuid that host, or null for the selected one
     */
    void focusHost(@Nullable String hostUuid);

    /** Nothing the panel can describe (Stats): it shows the app's name over the hosts row. */
    void none();
}
