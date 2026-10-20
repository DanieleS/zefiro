package com.limelight.computers;

import com.limelight.grid.AppSortOrder;
import com.limelight.grid.assets.NoArtDetector;
import com.limelight.library.LibraryApp;
import com.limelight.library.LibraryModel;
import com.limelight.nvstream.http.AppMetadata;
import com.limelight.nvstream.http.NvApp;

import java.util.Collections;
import java.util.List;

/**
 * Library snapshots for tests outside this package, whose constructor the library keeps to
 * itself: a screen is only ever handed one, never makes one.
 */
public final class SnapshotFixtures {

    private SnapshotFixtures() {
    }

    /** A ready library of these games, running {@code running} (null for nothing). */
    public static HostLibrary.Snapshot ready(String hostUuid, List<LibraryApp> apps, NvApp running) {
        return new HostLibrary.Snapshot(hostUuid, HostLibrary.State.READY,
                Collections.unmodifiableList(apps), running, LibraryModel.Specials.NONE,
                Collections.<String, AppMetadata>emptyMap(), false, AppSortOrder.HOST,
                Collections.<NoArtDetector.ArtFingerprint>emptySet(), 0, 0);
    }

    /** A ready library with no games, whose host serves play statistics or not. */
    public static HostLibrary.Snapshot withStats(String hostUuid, boolean statsAvailable) {
        return new HostLibrary.Snapshot(hostUuid, HostLibrary.State.READY,
                Collections.<LibraryApp>emptyList(), null, LibraryModel.Specials.NONE,
                Collections.<String, AppMetadata>emptyMap(), statsAvailable, AppSortOrder.HOST,
                Collections.<NoArtDetector.ArtFingerprint>emptySet(), 0, 0);
    }
}
