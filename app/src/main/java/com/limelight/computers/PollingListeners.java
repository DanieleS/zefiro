package com.limelight.computers;

import com.limelight.nvstream.http.ComputerDetails;

import java.util.concurrent.CopyOnWriteArrayList;

/**
 * The set of screens listening to {@link ComputerManagerService}'s polls, and the two edges of it
 * that matter: the first listener arriving and the last one leaving.
 *
 * <p>The service used to hold one listener in a field. Starting polling overwrote it and stopping
 * nulled it, so whichever screen started last silently took every update from the others, and any
 * screen stopping stopped them all. That only worked while screens handed over strictly in
 * pause/resume order; a shortcut firing over an open library broke it. A set lets the library,
 * the PC list, the shortcut trampoline and the new host repository overlap safely.
 *
 * <h3>Invariant</h3>
 *
 * Polling runs exactly while the set is non-empty. {@link #add} reports the 0→1 edge and
 * {@link #remove} the 1→0 edge, so the service starts and tears down on those edges alone and
 * never on an individual screen's say-so.
 *
 * <pre>
 *   {} ──add(a)──▶ {a}      true: start polling
 *   {a} ─add(b)──▶ {a,b}    false
 *   {a,b} ─remove(a)─▶ {b}  false
 *   {b} ─remove(b)──▶ {}    true: tear down
 * </pre>
 *
 * <p>The edge methods are synchronized so two screens racing to add (or remove) cannot both see
 * themselves as the edge. Notification is not: it walks a copy-on-write snapshot, so this set on
 * its own tolerates a listener adding or removing itself, or another, from inside a callback.
 *
 * <p>The service around it does not. Several notify sites (the network callback, the initial
 * report) run while holding the service's {@code pollingTuples} lock, and the binder's
 * {@code addPollingListener} / {@code removePollingListener} take {@code pollingStateLock} first
 * and {@code pollingTuples} second. Calling them synchronously from a callback takes the two in
 * the opposite order, and a screen adding on main at the same moment deadlocks with it. So a
 * service listener never changes its registration from {@code notifyComputerUpdated}: it posts
 * that to another thread, as {@code ShortcutTrampoline} does through {@code runOnUiThread}.
 */
public final class PollingListeners implements ComputerManagerListener {
    private final CopyOnWriteArrayList<ComputerManagerListener> listeners = new CopyOnWriteArrayList<>();

    /**
     * Registers a listener. Adding one already present changes nothing: a screen that asks twice
     * (its binder arriving and its resume racing) is still one listener and gets each poll once.
     *
     * @return true when this made the set non-empty, i.e. polling has to start.
     */
    public synchronized boolean add(ComputerManagerListener listener) {
        if (listener == null) {
            throw new IllegalArgumentException("listener must not be null");
        }
        boolean wasEmpty = listeners.isEmpty();
        return listeners.addIfAbsent(listener) && wasEmpty;
    }

    /**
     * Unregisters a listener. Removing one that is not there (or null) is a no-op, so a screen
     * may stop updates it never managed to start.
     *
     * @return true when this emptied the set, i.e. polling has to stop.
     */
    public synchronized boolean remove(ComputerManagerListener listener) {
        return listener != null && listeners.remove(listener) && listeners.isEmpty();
    }

    /**
     * Drops every listener at once, for the system unbinding the service under all of them.
     *
     * @return true when there was anyone to drop.
     */
    public synchronized boolean clear() {
        boolean hadAny = !listeners.isEmpty();
        listeners.clear();
        return hadAny;
    }

    public boolean isEmpty() {
        return listeners.isEmpty();
    }

    public boolean contains(ComputerManagerListener listener) {
        return listeners.contains(listener);
    }

    public int size() {
        return listeners.size();
    }

    /**
     * Hands one update to every listener, on the calling thread. That thread is a polling thread,
     * the network callback's, or a caller holding the service's {@code pollingTuples} lock, and
     * the object is the service's live one: listeners copy what they need and post, nothing more.
     */
    @Override
    public void notifyComputerUpdated(ComputerDetails details) {
        for (ComputerManagerListener listener : listeners) {
            listener.notifyComputerUpdated(details);
        }
    }
}
