package com.limelight.library;

import com.limelight.nvstream.http.NvApp;

import java.util.Objects;

/**
 * One game as the library shows it: the host's app, plus whether it is running and whether the
 * user hid it.
 *
 * <p>It replaces {@code AppView.AppObject}, whose flags were flipped in place by the screen as
 * polls came in. This one is immutable: a poll that changes a flag produces a new instance, so a
 * list adapter can diff the old list against the new one and rebind only what changed.
 *
 * <p>{@link NvApp} itself is mutable, but nothing that builds a {@code LibraryApp} touches the app
 * after handing it over; every list from the host is parsed into fresh instances.
 *
 * <p>{@link #equals} compares everything a tile shows (id, name, UUID, HDR and both flags), which
 * is what {@code DiffUtil} needs to decide whether to rebind. Identity is the app id; see
 * {@link #sameApp}.
 */
public final class LibraryApp {
    public final NvApp app;
    public final boolean running;
    public final boolean hidden;

    public LibraryApp(NvApp app, boolean running, boolean hidden) {
        if (app == null) {
            throw new IllegalArgumentException("app must not be null");
        }
        this.app = app;
        this.running = running;
        this.hidden = hidden;
    }

    public int appId() {
        return app.getAppId();
    }

    /** Whether the two are the same game, whatever their state: the stable-id test for a list. */
    public boolean sameApp(LibraryApp other) {
        return other != null && other.app.getAppId() == app.getAppId();
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof LibraryApp)) {
            return false;
        }
        LibraryApp that = (LibraryApp) o;
        return running == that.running
                && hidden == that.hidden
                && app.getAppId() == that.app.getAppId()
                && app.isHdrSupported() == that.app.isHdrSupported()
                && Objects.equals(app.getAppName(), that.app.getAppName())
                && Objects.equals(app.getAppUUID(), that.app.getAppUUID());
    }

    @Override
    public int hashCode() {
        return Objects.hash(app.getAppId(), app.getAppName(), app.getAppUUID(),
                app.isHdrSupported(), running, hidden);
    }

    @Override
    public String toString() {
        return app.getAppName() + (running ? " [running]" : "") + (hidden ? " [hidden]" : "");
    }
}
