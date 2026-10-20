package com.limelight.computers;

import android.graphics.Bitmap;

import com.limelight.nvstream.http.ComputerDetails;

/**
 * One host as the UI shows it: what the computer manager knows about it, plus the two things the
 * PC list adds on top, the game it is running and that game's art.
 *
 * <p>It used to be {@code PcView.ComputerObject}. It moved out so the host list can be built and
 * shared without the activity that first drew it.
 *
 * <h3>Mutability</h3>
 *
 * Instances published by {@code HostRepository} are never mutated after publication: a change is
 * a new entry, so a consumer can compare entries by identity and hold one across threads without
 * locking. The fields are public and non-final only as a leftover of the old PC list, which
 * built and updated its own entries in place; nothing does that any more.
 */
public class HostEntry {
    public ComputerDetails details;

    /** The game this PC is in, named from its app list; null when it is idle. */
    public String runningAppName;

    /** That game's hero image, fetched from the host; null until it arrives, or if it has none. */
    public Bitmap runningBackground;

    public HostEntry(ComputerDetails details) {
        if (details == null) {
            throw new IllegalArgumentException("details must not be null");
        }
        this.details = details;
    }

    /**
     * A complete entry, as the repository publishes it.
     *
     * @param details a copy of the service's details, never the live object it polls into.
     */
    public HostEntry(ComputerDetails details, String runningAppName, Bitmap runningBackground) {
        this(details);
        this.runningAppName = runningAppName;
        this.runningBackground = runningBackground;
    }

    @Override
    public String toString() {
        return details.name;
    }

    public String guessManagementUrl() {
        if (details.activeAddress == null) return null;
        return "https://" + details.activeAddress.address + ":" + (details.guessExternalPort() + 1);
    }
}
