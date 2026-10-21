package com.limelight.computers;

import android.app.Service;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.IBinder;

import com.limelight.nvstream.http.ComputerDetails;

/**
 * {@link CmsGateway} over the real {@link ComputerManagerService} binder.
 */
public class BinderCmsGateway implements CmsGateway {
    private final ComputerManagerService.ComputerManagerBinder binder;

    public BinderCmsGateway(ComputerManagerService.ComputerManagerBinder binder) {
        this.binder = binder;
    }

    /**
     * A connector that binds with the application context. The binding then belongs to whoever
     * holds the connector (the host repository), not to a screen, so it survives the screen being
     * recreated and never leaks one.
     */
    public static Connector connector(Context context) {
        return new BinderConnector(context.getApplicationContext());
    }

    @Override
    public void waitForReady() {
        binder.waitForReady();
    }

    @Override
    public void addPollingListener(ComputerManagerListener listener) {
        binder.addPollingListener(listener);
    }

    @Override
    public void removePollingListener(ComputerManagerListener listener) {
        binder.removePollingListener(listener);
    }

    @Override
    public void waitForPollingStopped() {
        binder.waitForPollingStopped();
    }

    @Override
    public String getUniqueId() {
        return binder.getUniqueId();
    }

    @Override
    public ComputerDetails getComputer(String uuid) {
        return binder.getComputer(uuid);
    }

    @Override
    public void invalidateStateForComputer(String uuid) {
        binder.invalidateStateForComputer(uuid);
    }

    @Override
    public void removeComputer(ComputerDetails computer) {
        binder.removeComputer(computer);
    }

    @Override
    public AppListPoller createAppListPoller(String uuid) {
        // The poller must hold the live object: it reads the host's state from it on every round
        // and writes the fresh list back onto it for the listeners.
        ComputerDetails live = binder.getComputer(uuid);
        if (live == null) {
            return null;
        }
        final ComputerManagerService.ApplistPoller poller = binder.createAppListPoller(live);
        return new AppListPoller() {
            @Override
            public void start() {
                poller.start();
            }

            @Override
            public void stop() {
                poller.stop();
            }

            @Override
            public void pollNow() {
                poller.pollNow();
            }
        };
    }

    private static final class BinderConnector implements Connector {
        private final Context context;
        private ServiceConnection connection;

        BinderConnector(Context context) {
            this.context = context;
        }

        @Override
        public void connect(final Callback callback) {
            if (connection != null) {
                return;
            }
            connection = new ServiceConnection() {
                @Override
                public void onServiceConnected(ComponentName name, IBinder service) {
                    callback.onConnected(new BinderCmsGateway(
                            (ComputerManagerService.ComputerManagerBinder) service));
                }

                @Override
                public void onServiceDisconnected(ComponentName name) {
                    callback.onDisconnected();
                }
            };
            context.bindService(new Intent(context, ComputerManagerService.class), connection,
                    Service.BIND_AUTO_CREATE);
        }

        @Override
        public void disconnect() {
            if (connection == null) {
                return;
            }
            try {
                context.unbindService(connection);
            } catch (IllegalArgumentException e) {
                // Never bound after all; nothing to release.
            }
            connection = null;
        }
    }
}
