package com.limelight.stream;

import androidx.annotation.NonNull;

/**
 * Somewhere the {@link StreamActions} tree is drawn and driven: the centred sheet, the phone's
 * side panel ({@link StreamControlsPanel}), and the Thor's bottom-screen sheet
 * ({@link CompanionSheetSurface}). The router (GameMenu) picks one each time the controls open and talks to it only
 * through this, so a new surface never touches what the entries do.
 *
 * <p>A surface keeps its own page stack: choosing a page shows it on the same surface, and
 * {@link #back()} returns to the page before, closing only from the first. Main thread only, all
 * of it.
 */
public interface ControlsSurface {

    /**
     * A surface's input, already turned from keys and sticks into intent. Kept apart from the
     * companion pages' {@code PageFocus.Nav}, which has the four directions only: see there.
     */
    enum Nav { UP, DOWN, LEFT, RIGHT, ACTIVATE, BACK, CLOSE }

    /** What the surface hands back to the router. */
    interface Callbacks {
        /**
         * A page is about to be shown. Returns false to keep it shut, when the router answered
         * it some other way (no server commands to list); the surface is then closed.
         */
        boolean onPageOpening(@NonNull StreamAction page);

        /** A leaf was chosen. The surface has already closed, so the action owns the screen. */
        void onLeaf(@NonNull StreamAction leaf);

        /**
         * The surface went down, however it was closed: from its first page, by a leaf (just
         * before {@link #onLeaf}), or by the router. Only when it was showing something.
         */
        default void onClosed() {
        }
    }

    /** Show the tree's root page, replacing whatever the surface showed. */
    void open(@NonNull StreamAction tree);

    /** One page back, or closed from the first page. */
    void back();

    void close();

    boolean isOpen();

    /** Returns whether the surface used the input; it never lets it through while open. */
    boolean onNav(@NonNull Nav nav);
}
