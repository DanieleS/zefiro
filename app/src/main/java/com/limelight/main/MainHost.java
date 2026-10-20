package com.limelight.main;

import androidx.annotation.NonNull;
import androidx.lifecycle.LiveData;

import com.limelight.computers.HostRepository;
import com.limelight.library.LaunchController;
import com.limelight.pcs.HostActions;

/**
 * What a section fragment may ask of the main screen.
 *
 * <p>The controllers that start activities, show dialogs or hold activity-result launchers are
 * owned by the activity, never by a fragment: a section's views come and go (hidden, restored),
 * and a pairing or an export in flight must not go with them. Sections reach those controllers,
 * and the shared host state, through this interface, which {@link MainActivity} implements; a
 * section finds it as its activity.
 *
 * Main thread only.
 */
public interface MainHost {

    /** The host state every section reads. */
    @NonNull
    HostRepository hostRepository();

    /** Pairing, waking and the other per-host actions. */
    @NonNull
    HostActions hostActions();

    /** Starting, resuming and quitting games, and a game's actions sheet. */
    @NonNull
    LaunchController launchController();

    /**
     * Where a section reports what the d-pad is on, for the companion panel. Sections call it
     * on every focus move; it settles the reports itself.
     */
    @NonNull
    GameFocusListener gameFocus();

    /**
     * Where a section screen puts its lower half on the companion panel. A page may be published
     * at any time: while the panel is off ({@code CompanionDisplayManager.panelShowing()}) it is
     * held, and drawn when the panel comes back. A screen that lays the lower half out inline
     * meanwhile clears it (or has no page to give) if it must not reappear on the panel then.
     */
    @NonNull
    CompanionPages companionPages();

    /**
     * Whether the screen is driven by a d-pad first: a wide window with a companion panel or a
     * gamepad attached (the Thor). Such a screen opens with a game focused, even though it
     * starts in touch mode, so the companion panel has a game to show and the first press
     * moves from somewhere visible.
     */
    boolean isDpadFirst();

    /** Switches to a section, checking its navigation item as a tap on it would. */
    void showSection(@NonNull Section section);

    /**
     * Whether the Library lists hidden games too, dimmed, so they can be shown again. It lives
     * with the main screen rather than in a Library screen, because the PCs section asks for it
     * as well (a host's "View all apps").
     */
    @NonNull
    LiveData<Boolean> showHiddenGames();

    void setShowHiddenGames(boolean show);

    /**
     * Whether back has work in a section may have changed: a screen was pushed or popped, or a
     * mode entered or left. The main screen asks the visible section again ({@link SectionBack}).
     */
    void backWorkChanged();
}
