package com.limelight.companion;

import android.view.Display;

/**
 * Whether a stream should follow the host's game telemetry at all.
 *
 * <p>Telemetry is only ever drawn on the companion panel. On a device with no second screen,
 * following it would keep a connection to the host open for the whole session, and a
 * {@code TelemetryStream} thread awake, to feed a view nobody can see. So the stream starts only
 * where a panel could show it.
 *
 * <h3>Why hardware plus config, and not "is the panel up"</h3>
 *
 * {@link CompanionDisplayManager#isCompanionAvailable} also asks whether our panel currently owns
 * the screen. That answer changes during a session: the user can hide the panel and show it again,
 * or a companion app can borrow the screen and hand it back. Telemetry is started once, at connect,
 * so gating on ownership would leave a panel that appears mid-stream with nothing to draw. The gate
 * therefore asks only the two questions that hold for the whole session: is the panel turned on in
 * settings, and is there a second display for it.
 *
 * <p>The same goes for the display's power state: a panel the user switched off, or one the OLED
 * burn-in routine has blanked, can come back on mid-stream. The caller therefore passes the
 * display found by {@link DisplayTargets#findCompanionHardwareId}, which ignores whether the
 * screen is on, not {@link DisplayTargets#findCompanionDisplayId}, which needs it on to draw.
 *
 * <p>Pure, so the rule can be tested without a device.
 */
public final class TelemetryGate {

    private TelemetryGate() {
    }

    /**
     * @param configured         whether the companion panel is turned on in settings
     * @param companionDisplayId the display the panel would use, whether or not it is on right now,
     *                           or {@link Display#INVALID_DISPLAY} when the device has none
     */
    public static boolean shouldStart(boolean configured, int companionDisplayId) {
        return configured && companionDisplayId != Display.INVALID_DISPLAY;
    }
}
