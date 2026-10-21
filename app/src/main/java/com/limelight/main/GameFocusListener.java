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
     * A game of the selected host took focus in a list whose size is the whole of something
     * (See all's grid), which the panel counts out: "Focused · 4 of 12". A shelf is only the head
     * of its list, and reports through {@link #focusGame(LibraryApp)} instead.
     *
     * @param position the game's index in the list, from 0; -1 when unknown
     * @param count    how many games the list has; 0 when unknown
     */
    void focusGame(@NonNull LibraryApp app, int position, int count);

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
