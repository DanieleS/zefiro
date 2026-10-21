package com.limelight.computers;

import com.limelight.nvstream.http.ComputerDetails;
import com.limelight.nvstream.http.PairingManager.PairState;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;

/**
 * A host is published once, but only once the launcher really has its shortcut: one skipped for
 * lack of room must be tried again, as the per-poll call used to.
 */
public class ShortcutPublisherTest {

    private static ComputerDetails pairedHost() {
        ComputerDetails details = new ComputerDetails();
        details.uuid = "uuid-a";
        details.name = "Alpha";
        details.state = ComputerDetails.State.ONLINE;
        details.pairState = PairState.PAIRED;
        return details;
    }

    @Test
    public void aHostTheLauncherHadNoRoomForIsTriedAgain() {
        final List<String> attempts = new ArrayList<>();
        final boolean[] room = {false};
        ShortcutPublisher publisher = new ShortcutPublisher(details -> {
            attempts.add(details.uuid);
            return room[0];
        });
        ComputerDetails host = pairedHost();

        publisher.onHostUpdated(host);
        publisher.onHostUpdated(host);
        assertEquals(2, attempts.size());

        // A slot frees up: published this time, and then left alone.
        room[0] = true;
        publisher.onHostUpdated(host);
        publisher.onHostUpdated(host);
        assertEquals(3, attempts.size());
    }

    @Test
    public void anOfflineHostIsNotForgottenForBeingAsleep() {
        final List<String> attempts = new ArrayList<>();
        ShortcutPublisher publisher = new ShortcutPublisher(details -> attempts.add(details.uuid));
        ComputerDetails host = pairedHost();
        publisher.onHostUpdated(host);

        // Offline with a stale pair state says nothing about pairing.
        host.state = ComputerDetails.State.OFFLINE;
        host.pairState = PairState.NOT_PAIRED;
        publisher.onHostUpdated(host);
        host.state = ComputerDetails.State.ONLINE;
        host.pairState = PairState.PAIRED;
        publisher.onHostUpdated(host);

        assertEquals(1, attempts.size());
    }
}
