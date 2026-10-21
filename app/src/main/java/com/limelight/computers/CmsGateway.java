package com.limelight.computers;

import com.limelight.nvstream.http.ComputerDetails;

/**
 * The part of {@link ComputerManagerService}'s binder that the host repository and the host
 * library use, as an interface.
 *
 * <p>It exists so {@link HostRepository} can be tested without a bound service: a test hands it
 * a fake that records listeners and emits details on demand. {@link BinderCmsGateway} is the
 * real one. Method contracts are the binder's; in particular the polling listener contract
 * (copy and post, never block) applies to every implementation.
 */
public interface CmsGateway {

    /** Delivers a gateway once the service is bound. */
    interface Connector {
        /**
         * Binds, and calls back once there is a gateway. The gateway is not necessarily ready:
         * call {@link CmsGateway#waitForReady()} off the main thread before using it.
         */
        void connect(Callback callback);

        /** Unbinds. Safe to call when {@link #connect} never got as far as a gateway. */
        void disconnect();

        interface Callback {
            /** On the main thread. */
            void onConnected(CmsGateway gateway);

            /** On the main thread; the service went away, which in practice means it crashed. */
            void onDisconnected();
        }
    }

    /** The app list poller for one host: a thread that reports through the polling listeners. */
    interface AppListPoller {
        void start();

        void stop();

        /** Polls now rather than at the end of the current wait. */
        void pollNow();
    }

    /** Blocks until discovery is bound; never call on the main thread. */
    void waitForReady();

    /** See {@link ComputerManagerService.ComputerManagerBinder#addPollingListener}. */
    void addPollingListener(ComputerManagerListener listener);

    /** See {@link ComputerManagerService.ComputerManagerBinder#removePollingListener}. */
    void removePollingListener(ComputerManagerListener listener);

    /** Blocks while any poll is in flight; never call on the main thread. */
    void waitForPollingStopped();

    String getUniqueId();

    /**
     * The service's live object for a host, or null. Polling threads write to it: read it on the
     * spot or copy it, never keep it.
     */
    ComputerDetails getComputer(String uuid);

    /** Marks the host unknown so the next poll reports it afresh. */
    void invalidateStateForComputer(String uuid);

    void removeComputer(ComputerDetails computer);

    /**
     * A poller for the host's app list, or null when the service does not know the host. Its
     * results arrive as {@code rawAppList} on the details every polling listener receives.
     */
    AppListPoller createAppListPoller(String uuid);
}
