package com.limelight.grid.assets;

/**
 * Where a game's cover stands, as a tile draws it.
 *
 * <p>The title card is the tile's answer to "what game is this" whenever there is no real art to
 * answer it, so its visibility follows this state and nothing else. In particular a cover that
 * is still loading is not a reason to hide the title: on a slow host that is every tile for
 * seconds, and a grid of blank cards tells the user nothing.
 *
 * <pre>
 *   bind ──▶ LOADING ──disk or host gives a cover──▶ ART      (the game's own cover)
 *             │  │
 *             │  └──host says none, or gives its stand-in──▶ NO_ART (the title card)
 *             └──host cannot be asked──▶ stays LOADING, until the tile is bound again
 * </pre>
 *
 * NO_ART is a statement about the game, so only the host's answer can lead there. A host with
 * no address yet, or one that is offline, has said nothing, and claiming "no cover" for good
 * would keep the title card up after the cover arrives.
 */
public enum ArtState {
    /**
     * Not known yet: the title card, at reduced emphasis, so it reads as provisional. Also
     * where a tile stays while its host cannot be asked.
     */
    LOADING,
    /** The game's own cover; the title card goes away. */
    ART,
    /** There is no cover worth showing: the title card, at full emphasis, for good. */
    NO_ART
}
