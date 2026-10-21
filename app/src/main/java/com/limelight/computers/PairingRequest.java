package com.limelight.computers;

import com.limelight.nvstream.http.ComputerDetails;

/**
 * A pairing that a deep link asked for and that can now go ahead: the host it named has answered
 * a poll at the address the link gave.
 *
 * <p>An {@code art://host:port?pin=&passphrase=} link reaches the app before the host has been
 * polled, so the PIN cannot be used yet. The link's address is parked in the repository
 * ({@link HostRepository#setPendingPairing}) and matched against every poll; the first host found
 * online there becomes one of these, for the screen that runs pairing. Ported from the hook in
 * {@code PcView}'s polling listener.
 *
 * <p>Immutable. {@link #host} is a copy, never the service's live object.
 */
public final class PairingRequest {
    public final ComputerDetails host;
    public final String pin;
    public final String passphrase;

    public PairingRequest(ComputerDetails host, String pin, String passphrase) {
        this.host = host;
        this.pin = pin;
        this.passphrase = passphrase;
    }
}
