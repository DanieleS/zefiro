package com.limelight.computers;

import android.graphics.Bitmap;

import com.limelight.nvstream.http.ComputerDetails;

/**
 * One host as the UI shows it: what the computer manager knows about it, plus what the PC list
 * adds on top: the game it is running, that game's art, and how many games it has.
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

    /**
     * How many games the Library would list for this host, or {@link #UNKNOWN_GAME_COUNT} for a
     * host whose app list has never been seen. See {@link GameCount} for what is counted.
     */
    public final int gameCount;

    /** The {@link #gameCount} of a host whose app list is not known. */
    public static final int UNKNOWN_GAME_COUNT = -1;

    public HostEntry(ComputerDetails details) {
        this(details, null, null, UNKNOWN_GAME_COUNT);
    }

    /** An entry whose game count is not known; for callers that never had an app list. */
    public HostEntry(ComputerDetails details, String runningAppName, Bitmap runningBackground) {
        this(details, runningAppName, runningBackground, UNKNOWN_GAME_COUNT);
    }

    /**
     * A complete entry, as the repository publishes it.
     *
     * @param details   a copy of the service's details, never the live object it polls into.
     * @param gameCount the games the Library would list, or {@link #UNKNOWN_GAME_COUNT}.
     */
    public HostEntry(ComputerDetails details, String runningAppName, Bitmap runningBackground,
                     int gameCount) {
        if (details == null) {
            throw new IllegalArgumentException("details must not be null");
        }
        this.details = details;
        this.runningAppName = runningAppName;
        this.runningBackground = runningBackground;
        this.gameCount = gameCount;
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
