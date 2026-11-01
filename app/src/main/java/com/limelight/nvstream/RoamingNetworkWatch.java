package com.limelight.nvstream;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.os.Build;

import com.limelight.LimeLog;
import com.limelight.nvstream.jni.MoonBridge;

/**
 * Client roaming (experimental): while a stream that negotiated roaming runs, tells
 * moonlight-common-c about each default-network change and whether the new network is metered,
 * so it can move the stream. Separate from Game's reconnect callback, which stays the safety net.
 * The callbacks run on ConnectivityManager's thread, never the stream's.
 */
public final class RoamingNetworkWatch {
    private final ConnectivityManager connMgr;
    private final RoamingNetworkTracker tracker;
    private final ConnectivityManager.NetworkCallback callback;

    private RoamingNetworkWatch(ConnectivityManager connMgr, Network streamNetwork) {
        this.connMgr = connMgr;
        tracker = new RoamingNetworkTracker(streamNetwork, metered -> {
            LimeLog.info("Roaming: the default network changed (" + (metered ? "metered" : "not metered") + ")");
            MoonBridge.notifyNetworkChanged(metered);
        });
        callback = new ConnectivityManager.NetworkCallback() {
            @Override
            public void onAvailable(Network network) {
                // From Android 8 onCapabilitiesChanged always follows; before that, ask.
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
                    NetworkCapabilities caps = connMgr.getNetworkCapabilities(network);
                    if (caps != null) {
                        tracker.capabilities(network, caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED));
                    }
                }
            }

            @Override
            public void onCapabilitiesChanged(Network network, NetworkCapabilities caps) {
                tracker.capabilities(network, caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED));
            }

            @Override
            public void onLost(Network network) {
                tracker.lost(network);
            }
        };
    }

    /**
     * Starts reporting network changes, or returns null (with a warning) where it cannot: then
     * the stream does not roam onto another network. The network in use now is the one the
     * stream runs on, so it never starts a roam by itself.
     */
    public static RoamingNetworkWatch start(Context context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) {
            LimeLog.warning("Roaming: needs Android 7 to follow network changes; not roaming");
            return null;
        }
        ConnectivityManager connMgr = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
        if (connMgr == null) {
            LimeLog.warning("Roaming: no ConnectivityManager; not roaming");
            return null;
        }
        RoamingNetworkWatch watch = new RoamingNetworkWatch(connMgr, connMgr.getActiveNetwork());
        try {
            connMgr.registerDefaultNetworkCallback(watch.callback);
        } catch (RuntimeException e) {
            LimeLog.warning("Roaming: could not follow network changes, not roaming: " + e);
            return null;
        }
        return watch;
    }

    /** The stream ends: no report reaches common-c after this returns. */
    public void stop() {
        tracker.stop();
        try {
            connMgr.unregisterNetworkCallback(callback);
        } catch (RuntimeException e) {
            // Already gone
        }
    }
}
