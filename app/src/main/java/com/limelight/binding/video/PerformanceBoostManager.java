package com.limelight.binding.video;

import android.content.Context;
import android.os.Build;
import android.os.PerformanceHintManager;
import android.os.PowerManager;
import android.os.Process;

import com.limelight.LimeLog;

/**
 * System performance hints and thermal monitoring for the decoder.
 *
 * - PerformanceHintManager (API 31+): tells the system how long a frame really takes, so the
 *   governor keeps the clocks up instead of down-clocking between frames.
 * - Thermal status listener (API 29+): reports when the device starts throttling.
 *
 * Every call is a no-op where the API is missing or the device refuses it.
 */
class PerformanceBoostManager {
    interface ThermalThrottleListener {
        void onThermalThrottle(int thermalStatus);
    }

    private final Context context;
    private volatile PerformanceHintManager.Session hintSession;
    private PowerManager.OnThermalStatusChangedListener thermalStatusListener;

    PerformanceBoostManager(Context context) {
        this.context = context;
    }

    /** Creates a hint session for the calling thread. Call it from the thread doing the work. */
    void createHintSession(int targetFps) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S || targetFps <= 0) {
            return;
        }

        try {
            PerformanceHintManager manager = (PerformanceHintManager)
                    context.getSystemService(Context.PERFORMANCE_HINT_SERVICE);
            if (manager == null) {
                return;
            }

            long targetDurationNs = 1_000_000_000L / targetFps;
            PerformanceHintManager.Session session =
                    manager.createHintSession(new int[]{Process.myTid()}, targetDurationNs);
            hintSession = session;
            if (session != null) {
                LimeLog.info("PerformanceHint session: target=" + (targetDurationNs / 1_000_000) + "ms");
            }
        } catch (Exception e) {
            LimeLog.warning("PerformanceHint unavailable: " + e.getMessage());
            hintSession = null;
        }
    }

    /** Reports how long the last frame took to process. */
    void reportActualWorkDuration(long actualDurationNs) {
        PerformanceHintManager.Session session = hintSession;
        if (session == null || actualDurationNs <= 0) {
            return;
        }
        try {
            session.reportActualWorkDuration(actualDurationNs);
        } catch (Exception ignored) {
            // Some devices don't fully support this
        }
    }

    /** Calls the listener when the device reaches severe thermal throttling. */
    void startThermalMonitoring(final ThermalThrottleListener listener) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            return;
        }

        try {
            PowerManager pm = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
            if (pm == null) {
                return;
            }

            PowerManager.OnThermalStatusChangedListener statusListener = status -> {
                if (status >= PowerManager.THERMAL_STATUS_SEVERE) {
                    LimeLog.warning("Thermal throttling: status=" + status);
                    if (listener != null) {
                        listener.onThermalThrottle(status);
                    }
                }
            };
            pm.addThermalStatusListener(statusListener);
            thermalStatusListener = statusListener;
            LimeLog.info("Thermal monitoring started");
        } catch (Exception e) {
            LimeLog.warning("Thermal monitoring unavailable: " + e.getMessage());
        }
    }

    void close() {
        PerformanceHintManager.Session session = hintSession;
        hintSession = null;
        if (session != null) {
            try {
                session.close();
            } catch (Exception ignored) {
            }
        }

        PowerManager.OnThermalStatusChangedListener statusListener = thermalStatusListener;
        thermalStatusListener = null;
        if (statusListener != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            try {
                PowerManager pm = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
                if (pm != null) {
                    pm.removeThermalStatusListener(statusListener);
                }
            } catch (Exception ignored) {
            }
        }
    }
}
