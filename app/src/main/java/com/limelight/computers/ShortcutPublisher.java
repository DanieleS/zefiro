package com.limelight.computers;

import android.content.Context;

import com.limelight.nvstream.http.ComputerDetails;
import com.limelight.nvstream.http.PairingManager.PairState;
import com.limelight.utils.ShortcutHelper;

import java.util.HashMap;
import java.util.Map;

/**
 * Publishes each paired host's launcher shortcut once per session.
 *
 * <p>{@code PcView} republished it on every poll of every paired host, every 1.5 s: a
 * ShortcutManager IPC each time, for a shortcut that had not changed. Here a host is published
 * the first time it is seen paired, and again only if its name changes (the label) or it was seen
 * unpaired in between (re-pairing must re-enable a shortcut the unpairing disabled).
 *
 * <p>A host counts as published only once the launcher actually has its shortcut. The launcher
 * caps dynamic shortcuts, and a host polled while every slot is taken is skipped rather than
 * pushing another out; it stays unrecorded, so a later poll tries again once a slot frees up,
 * as the per-poll call used to.
 *
 * <p>Once recorded, a host is not checked again this session. If its shortcut is later pushed out
 * (another host force-added on pairing or on opening it reaps the lowest-ranked one, through a
 * {@code ShortcutHelper} of its own that this class never hears from), it stays out until the
 * host is renamed, unpaired and paired again, or the app restarts. That is accepted: the per-poll
 * call only got it back when a slot happened to free up, which a full launcher rarely does, and
 * watching for it would mean either the IPC per poll this class exists to save or a process-wide
 * hook into every helper's reap.
 *
 * <h3>Which context</h3>
 *
 * The repository creates this on the application context, which is enough: publishing a dynamic
 * shortcut, and refreshing an existing TV channel, need no screen. The one TV call that needs an
 * Activity (asking the launcher to show a new channel) is only made for a host that was just
 * paired, which is the pairing screen's job, not this class's.
 *
 * <p>Thread-safe. {@link #onHostUpdated} may block on IPC, so it belongs on a background thread.
 */
public class ShortcutPublisher {

    /** Where a host's shortcut goes; the launcher in production, a recorder in tests. */
    public interface Sink {
        /** @return whether the host has a shortcut afterwards; false asks for another try. */
        boolean publish(ComputerDetails details);
    }

    private final Sink sink;

    /** Host UUID to the name it was published under. */
    private final Map<String, String> published = new HashMap<>();

    public ShortcutPublisher(Context context) {
        this(new ShortcutHelper(context.getApplicationContext())::publishAppViewShortcut);
    }

    public ShortcutPublisher(Sink sink) {
        this.sink = sink;
    }

    /**
     * Publishes the host's shortcut if it is paired and not yet published under this name.
     *
     * @param details a snapshot of the host, not the live object a CMS callback hands out.
     */
    public void onHostUpdated(ComputerDetails details) {
        if (details.uuid == null) {
            return;
        }

        if (details.pairState != PairState.PAIRED) {
            // Only an answer from the host says it is unpaired; an offline host keeps its last
            // known pair state and must not be forgotten for being asleep.
            if (details.state == ComputerDetails.State.ONLINE) {
                forget(details.uuid);
            }
            return;
        }

        String name = details.name == null ? "" : details.name;
        synchronized (published) {
            if (name.equals(published.get(details.uuid))) {
                return;
            }
        }

        // Recorded after the fact: a host the launcher had no room for is tried again next poll.
        // The repository calls this from one ordered thread, so two publishes of one host do not
        // race; if another caller ever did, the worst case is one redundant IPC.
        if (sink.publish(details)) {
            synchronized (published) {
                published.put(details.uuid, name);
            }
        }
    }

    /** Forgets a host, so its next paired update publishes again; for a removed or unpaired host. */
    public void forget(String uuid) {
        synchronized (published) {
            published.remove(uuid);
        }
    }
}
