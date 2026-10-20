package com.limelight.main;

import androidx.annotation.NonNull;

import com.limelight.computers.HostRepository;
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

    /** Switches to a section, checking its navigation item as a tap on it would. */
    void showSection(@NonNull Section section);
}
