package com.limelight.pcs;

import androidx.annotation.ColorRes;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.StringRes;

import com.limelight.R;
import com.limelight.computers.HostEntry;
import com.limelight.nvstream.http.ComputerDetails;
import com.limelight.nvstream.http.ComputerDetails.AddressTuple;
import com.limelight.nvstream.http.NvHTTP;
import com.limelight.nvstream.http.PairingManager.PairState;

/**
 * What the PCs section says about one host: its state as a dot and a word, the action it is
 * waiting for, and the facts about how it is reached.
 *
 * <p>{@link HostTileBinder#statusColor} and {@link HostTileBinder#statusLabel} stay the colours
 * of a host in a list (the Library's host chip, the host sheet, Stats), where "can it be played
 * on" is the only question. A PC tile says more: it tells a host that is playing from one that
 * is idle, and one that is not paired from one that is simply busy, so it reads its colours
 * here.
 *
 * <pre>
 *   kind       when                                       dot
 *   PLAYING    online, paired, in a game                  signal
 *   ONLINE     online, paired                             status_online
 *   UNPAIRED   online, not paired                         a hollow ring
 *   CHECKING   not polled yet (state UNKNOWN)             content_faint
 *   OFFLINE    anything else                              content_faint
 * </pre>
 *
 * <p>Only facts the client really has are offered: the address that answered and which of the
 * host's addresses it is. Nothing here guesses a connection type, a host version or a last-seen
 * time, which no poll reports.
 *
 * <p>Pure: no views and no context, only resource ids, so every rule is testable as it is.
 */
public final class HostStatus {

    /** A host's state, as the PCs section draws it. */
    public enum Kind {
        PLAYING(R.color.signal, false, R.string.pcview_status_running),
        ONLINE(R.color.status_online, false, R.string.pcview_status_online),
        UNPAIRED(R.color.content_muted, true, R.string.pcview_status_unpaired),
        CHECKING(R.color.content_faint, false, R.string.pcview_status_checking),
        OFFLINE(R.color.content_faint, false, R.string.pcview_status_offline);

        /** The dot's colour; for a hollow dot, its ring's. */
        @ColorRes
        public final int color;
        /** Whether the dot is a ring rather than a disc: the host answers but is not ours yet. */
        public final boolean hollow;
        /**
         * The state in words. PLAYING's takes the game's name as its argument; see
         * {@link #textRes} for the one to use when the name is not known.
         */
        @StringRes
        public final int label;

        Kind(@ColorRes int color, boolean hollow, @StringRes int label) {
            this.color = color;
            this.hollow = hollow;
            this.label = label;
        }
    }

    /**
     * Which of the host's addresses answered the last poll. Its label is for the facts the
     * bottom screen shows, where it reads as "Connection".
     */
    public enum Connection {
        LOCAL(R.string.pcs_connection_local),
        MANUAL(R.string.pcs_connection_manual),
        IPV6(R.string.pcs_connection_ipv6),
        REMOTE(R.string.pcs_connection_remote),
        /** Offline, or reached at an address the host no longer lists. */
        NONE(0);

        @StringRes
        public final int label;

        Connection(@StringRes int label) {
            this.label = label;
        }
    }

    private HostStatus() {
    }

    @NonNull
    public static Kind kind(@NonNull ComputerDetails details) {
        if (details.state == ComputerDetails.State.ONLINE) {
            if (details.pairState != PairState.PAIRED) {
                return Kind.UNPAIRED;
            }
            return details.runningGameId != 0 ? Kind.PLAYING : Kind.ONLINE;
        }
        return details.state == ComputerDetails.State.UNKNOWN ? Kind.CHECKING : Kind.OFFLINE;
    }

    /**
     * The status line's text: {@link Kind#label}, except for a host playing a game whose name is
     * not known, which says only that it is in a game. Format it with
     * {@link HostEntry#runningAppName}; a string without a placeholder ignores the argument.
     */
    @StringRes
    public static int textRes(@NonNull HostEntry entry) {
        Kind kind = kind(entry.details);
        if (kind == Kind.PLAYING && entry.runningAppName == null) {
            return R.string.pcview_status_running_unnamed;
        }
        return kind.label;
    }

    /**
     * Which address the host answered at. When the active address equals more than one of them
     * (a manual address typed as the host's local one), the first of local, manual, IPv6, remote
     * names it.
     */
    @NonNull
    public static Connection connection(@NonNull ComputerDetails details) {
        AddressTuple active = details.activeAddress;
        if (details.state != ComputerDetails.State.ONLINE || active == null) {
            return Connection.NONE;
        }
        if (active.equals(details.localAddress)) {
            return Connection.LOCAL;
        }
        if (active.equals(details.manualAddress)) {
            return Connection.MANUAL;
        }
        if (active.equals(details.ipv6Address)) {
            return Connection.IPV6;
        }
        if (active.equals(details.remoteAddress)) {
            return Connection.REMOTE;
        }
        return Connection.NONE;
    }

    /**
     * The address to show for a host: the one that answered, else the last one known, trying
     * local, manual, IPv6 and remote in that order; null for a host with none.
     */
    @Nullable
    public static AddressTuple address(@NonNull ComputerDetails details) {
        if (details.activeAddress != null) {
            return details.activeAddress;
        }
        if (details.localAddress != null) {
            return details.localAddress;
        }
        if (details.manualAddress != null) {
            return details.manualAddress;
        }
        if (details.ipv6Address != null) {
            return details.ipv6Address;
        }
        return details.remoteAddress;
    }

    /** An address as a person reads it: the port only when it is not the default one. */
    @NonNull
    public static String addressText(@NonNull AddressTuple address) {
        return address.port == NvHTTP.DEFAULT_HTTP_PORT ? address.address : address.toString();
    }
}
