package com.limelight.companion;

import android.app.Activity;
import android.app.Application;
import android.content.Context;
import android.content.DialogInterface;
import android.hardware.display.DisplayManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Display;
import android.view.WindowManager;

import com.limelight.LimeLog;
import com.limelight.nvstream.http.Achievement;
import com.limelight.preferences.PreferenceConfiguration;
import com.limelight.stats.HostSession;
import com.limelight.stream.ControlsSurface;

import java.util.List;

/**
 * Keeps a companion surface on the secondary display for as long as the app is in the
 * foreground, across every Activity.
 *
 * A Presentation is a Dialog, so it needs an Activity to own its window. The owner therefore
 * changes as the user moves between screens: the surface is re-hosted on whichever Activity
 * is currently resumed, while {@link CompanionState} carries the content across so nothing
 * visibly restarts. The re-host is make-before-break — the new panel is shown before the old
 * one is dismissed — so navigating between screens does not blink the panel.
 *
 * The panel never takes focus; while its controls sheet is open the stream activity steers the
 * gamepad into it (see {@link CompanionPresentation}). A panel that goes away with the sheet still
 * open — dismissed, re-hosted, or its display gone — reports the sheet closed on its way out, so
 * the stream never keeps routing a pad to a sheet that no longer exists. The panel can be
 * dismissed with a back gesture, and turned on again from the library or the in-stream controls.
 *
 * Everything here is best-effort: with no usable secondary display, or when the platform
 * refuses the window, the manager logs and stays idle so the rest of the app is unaffected.
 */
public class CompanionDisplayManager implements Application.ActivityLifecycleCallbacks {

    private static CompanionDisplayManager instance;

    private final Application application;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final CompanionState state = CompanionState.getInstance();

    private DisplayManager displayManager;
    private CompanionPresentation presentation;
    private Activity owner;
    private int presentationDisplayId = Display.INVALID_DISPLAY;
    private int startedActivities;

    // Who owns the secondary screen lives in CompanionState, alongside what there is to show; this
    // class is the part that acts on it, attaching and tearing down the panel to match.

    // Invoked when the panel's own menu button is pressed; the stream activity registers it to
    // raise the in-game menu here.
    private Runnable menuRequestListener;

    // What the browsing surface's Play, Resume, Quit and host taps do; the main screen registers
    // it while it is started, the way the stream activity registers the menu request below.
    private BrowsingActions browsingActions;

    // Invoked when the panel's menu opens and closes; the stream activity registers these to
    // track whether it should be steering the gamepad into the menu drawn here.
    private Runnable menuOpenedListener;
    private Runnable menuClosedListener;

    // Invoked whenever a panel comes up or goes away; the stream activity registers it to hide its
    // own controls handle while the controls live down here instead.
    private Runnable showingListener;

    // Whether the keyboard for a text field focused on the host belongs on the panel. Kept here
    // rather than on the panel itself so a re-hosted panel brings it back up.
    private boolean textKeyboardRequested;

    // Invoked when the user closes that keyboard from the panel; the stream activity registers it
    // so a later "focus left the field" does not count on a keyboard that is already gone.
    private Runnable textKeyboardDismissedListener;

    private final DisplayManager.DisplayListener displayListener = new DisplayManager.DisplayListener() {
        @Override
        public void onDisplayAdded(int displayId) {
            sync();
        }

        @Override
        public void onDisplayRemoved(int displayId) {
            if (displayId == presentationDisplayId) {
                detach();
            }
            sync();
        }

        @Override
        public void onDisplayChanged(int displayId) {
            // Our panel may have just been powered off, or a candidate may have woken up.
            if (displayId == presentationDisplayId) {
                Display display = displayManager.getDisplay(displayId);
                if (display == null || !display.isValid() || display.getState() != Display.STATE_ON) {
                    detach();
                }
            }
            sync();
        }
    };

    private CompanionDisplayManager(Application application) {
        this.application = application;
    }

    /** Call once from {@code Application.onCreate}. */
    public static void install(Application application) {
        CompanionDisplayManager manager = new CompanionDisplayManager(application);

        manager.displayManager = (DisplayManager) application.getSystemService(Context.DISPLAY_SERVICE);
        if (manager.displayManager == null) {
            LimeLog.warning("CompanionDisplay: no DisplayManager, companion screen disabled");
            return;
        }

        DisplayTargets.dumpDisplays(application);
        application.registerActivityLifecycleCallbacks(manager);
        manager.displayManager.registerDisplayListener(manager.displayListener, manager.handler);
        manager.state.setOwnerListener(manager::onOwnerChanged);
        instance = manager;
    }

    /**
     * The companion needs a display of its own. External monitor mode already spends both
     * panels: the stream on one, the touchpad controller on the other.
     */
    private static boolean isEnabled(Context context) {
        PreferenceConfiguration prefConfig = PreferenceConfiguration.readPreferences(context);
        return prefConfig.enableCompanionDisplay && !prefConfig.enableFullExDisplay;
    }

    /**
     * Whether the companion feature is switched on at all for the current context. The library
     * bar and the in-stream menu use this to decide whether to offer the toggle.
     */
    public static boolean isConfigured(Context context) {
        return isEnabled(context);
    }

    /** Whether a panel is on screen right now. */
    public static boolean isShowing() {
        return instance != null && instance.presentation != null;
    }

    /**
     * Turn the panel on or off by hand. Off is remembered so a back-gesture dismissal or an
     * explicit hide is not undone by the next screen change.
     */
    public static void setVisible(boolean visible) {
        CompanionState.getInstance().setOwner(
                visible ? CompanionState.Owner.PANEL : CompanionState.Owner.OFF);
    }

    /** Flip the panel between on and off. */
    public static void toggle() {
        CompanionState state = CompanionState.getInstance();
        setVisible(state.getOwner() != CompanionState.Owner.PANEL);
    }

    // --- The browsing surface's controls ---

    /**
     * What the browsing surface's buttons do. Each call names the {@link CompanionState.Browsing}
     * the tap landed on, so a tap that races a focus move still acts on the game the user saw.
     *
     * <p>The panel never takes key focus, so these are reached by touch alone; the main screen
     * offers the same actions to the d-pad itself (A plays, Y opens a game's actions).
     */
    public interface BrowsingActions {
        void play(CompanionState.Browsing browsing);

        void resume(CompanionState.Browsing browsing);

        void quit(CompanionState.Browsing browsing);

        void selectHost(String hostUuid);

        /** The hosts row's "+": add a PC. */
        void addHost();
    }

    /**
     * Register what the browsing surface's buttons do, or null to take them away. The main
     * screen sets it while started and clears it when stopped, so a button drawn for a screen
     * that has gone does nothing rather than act on its behalf.
     */
    public static void setBrowsingActions(BrowsingActions actions) {
        if (instance != null) {
            instance.browsingActions = actions;
        }
    }

    /** Whether a tap on the browsing surface would reach anyone; the panel hides what would not. */
    static boolean hasBrowsingActions() {
        return instance != null && instance.browsingActions != null;
    }

    static void onBrowsingPlay(CompanionState.Browsing browsing) {
        if (instance != null && instance.browsingActions != null && browsing != null) {
            instance.browsingActions.play(browsing);
        }
    }

    static void onBrowsingResume(CompanionState.Browsing browsing) {
        if (instance != null && instance.browsingActions != null && browsing != null) {
            instance.browsingActions.resume(browsing);
        }
    }

    static void onBrowsingQuit(CompanionState.Browsing browsing) {
        if (instance != null && instance.browsingActions != null && browsing != null) {
            instance.browsingActions.quit(browsing);
        }
    }

    static void onBrowsingSelectHost(String hostUuid) {
        if (instance != null && instance.browsingActions != null && hostUuid != null) {
            instance.browsingActions.selectHost(hostUuid);
        }
    }

    static void onBrowsingAddHost() {
        if (instance != null && instance.browsingActions != null) {
            instance.browsingActions.addHost();
        }
    }

    // --- The in-game menu, when it is drawn on the companion panel ---

    /** Register what the panel's menu button should do; the stream activity raises the menu. */
    public static void setMenuRequestListener(Runnable listener) {
        if (instance != null) {
            instance.menuRequestListener = listener;
        }
    }

    /**
     * Register what should happen when the panel's menu opens and closes. The stream activity
     * uses these to track, on its own, whether it should be steering the gamepad into this menu —
     * the panel takes no focus, so the pad never leaves the activity, and it needs a reliable
     * signal rather than having to ask the panel each time.
     */
    public static void setMenuOpenedListener(Runnable listener) {
        if (instance != null) {
            instance.menuOpenedListener = listener;
        }
    }

    public static void setMenuClosedListener(Runnable listener) {
        if (instance != null) {
            instance.menuClosedListener = listener;
        }
    }

    /**
     * Register what should happen when a panel comes up or goes away, whatever the reason. Called
     * on the main thread, after {@link #isShowing()} already answers the new way.
     */
    public static void setShowingListener(Runnable listener) {
        if (instance != null) {
            instance.showingListener = listener;
        }
    }

    /** The panel's menu button was pressed. */
    public static void requestOpenMenu() {
        if (instance != null && instance.menuRequestListener != null) {
            instance.menuRequestListener.run();
        }
    }

    /** The panel's menu just closed; tell the owner it no longer holds the gamepad. */
    static void onMenuClosed() {
        // Runs on the main thread, the same as the listener; keep it synchronous so the gamepad
        // is handed back to the game on this very frame rather than one behind.
        if (instance != null && instance.menuClosedListener != null) {
            instance.menuClosedListener.run();
        }
    }

    /** Draw the in-game menu on the panel, if one is up. Returns whether it was shown there. */
    public static boolean showMenu(String title, List<CompanionMenuItem> items) {
        if (instance == null || instance.presentation == null) {
            return false;
        }
        instance.presentation.showMenu(title, items);
        if (instance.menuOpenedListener != null) {
            instance.menuOpenedListener.run();
        }
        return true;
    }

    public static void hideMenu() {
        if (instance != null && instance.presentation != null) {
            instance.presentation.hideMenu();
        }
    }

    /**
     * Raise the "achievement unlocked" toast on the panel too, when one is up: with the companion
     * showing, it is where the eye goes for anything that isn't the game itself.
     */
    public static void showAchievementToast(List<Achievement> fresh, HostSession session) {
        if (instance != null && instance.presentation != null) {
            instance.presentation.showAchievementToast(fresh, session);
        }
    }

    // --- The keyboard for a text field focused on the host ---

    /**
     * Raise the panel's keyboard.
     *
     * @return whether this call raised it: false when it was already up, or there is no panel.
     */
    public static boolean showTextKeyboard() {
        if (instance == null || instance.presentation == null) {
            return false;
        }
        boolean wasVisible = instance.presentation.isTextKeyboardVisible();
        instance.textKeyboardRequested = true;
        instance.presentation.setTextKeyboardVisible(true);
        return !wasVisible && instance.presentation.isTextKeyboardVisible();
    }

    public static void hideTextKeyboard() {
        if (instance == null) {
            return;
        }
        instance.textKeyboardRequested = false;
        if (instance.presentation != null) {
            instance.presentation.setTextKeyboardVisible(false);
        }
    }

    public static void setTextKeyboardDismissedListener(Runnable listener) {
        if (instance != null) {
            instance.textKeyboardDismissedListener = listener;
        }
    }

    /** The user closed the keyboard from the panel. */
    static void onTextKeyboardDismissed() {
        if (instance == null) {
            return;
        }
        instance.textKeyboardRequested = false;
        if (instance.textKeyboardDismissedListener != null) {
            instance.textKeyboardDismissedListener.run();
        }
    }

    public static boolean isMenuOpen() {
        return instance != null && instance.presentation != null && instance.presentation.isMenuOpen();
    }

    /** Move the sheet's selection one step in a direction; the other navs are ignored. */
    public static void moveMenuSelection(ControlsSurface.Nav nav) {
        if (isMenuOpen()) {
            instance.presentation.moveSelection(nav);
        }
    }

    public static void activateMenuSelection() {
        if (isMenuOpen()) {
            instance.presentation.activateSelection();
        }
    }

    /**
     * Whether content handed to {@link CompanionState} will actually be shown somewhere: the
     * companion is switched on and a display exists to host it.
     *
     * Callers use this to decide whether to render something themselves instead. It answers for
     * the current moment, not for the whole stream: a companion panel that is powered off later
     * takes its content with it.
     */
    public static boolean isCompanionAvailable(Activity activity) {
        return isEnabled(activity)
                && CompanionState.getInstance().isPanelOwner()
                && DisplayTargets.findCompanionDisplay(activity, getDisplayIdOf(activity)) != null;
    }

    /**
     * Give the secondary screen up to a companion app, or take it back when the app is done.
     *
     * The panel is a {@code TYPE_PRESENTATION} window, which the platform layers above every
     * application window on that display — so an app launched there is invisible until the panel
     * actually goes away. Hiding it is the only way to let the app be seen.
     */
    public static void yieldToApp(boolean yield) {
        CompanionState state = CompanionState.getInstance();
        if (yield) {
            state.setOwner(CompanionState.Owner.COMPANION_APP);
        } else if (state.getOwner() == CompanionState.Owner.COMPANION_APP) {
            // Only take the screen back from an app. If the user hid the panel meanwhile, their
            // choice outranks ours.
            state.setOwner(CompanionState.Owner.PANEL);
        }
    }

    /** The owner region moved: put the panel up or take it down to match. */
    private void onOwnerChanged() {
        if (state.isPanelOwner()) {
            sync();
        } else {
            detach();
        }
    }

    /** Ensure a panel is up for the current owner, if conditions allow and one is not already. */
    private void sync() {
        if (owner == null || startedActivities == 0 || !isEnabled(application)
                || !state.isPanelOwner()) {
            detach();
            return;
        }

        if (presentation != null) {
            return;
        }

        Display display = findDisplay();
        if (display == null) {
            LimeLog.info("CompanionDisplay: no secondary display available");
            return;
        }

        CompanionPresentation shown = createAndShow(display);
        if (shown == null) {
            return;
        }

        presentation = shown;
        presentationDisplayId = display.getDisplayId();
        LimeLog.info("CompanionDisplay: attached to display " + presentationDisplayId
                + " via " + owner.getClass().getSimpleName());
        notifyShowingChanged();
    }

    /**
     * Move the panel onto the current owner without a visible gap: show the replacement first,
     * then dismiss the one it replaces.
     */
    private void rehost() {
        if (owner == null || startedActivities == 0 || !isEnabled(application)
                || !state.isPanelOwner()) {
            detach();
            return;
        }

        Display display = findDisplay();
        if (display == null) {
            detach();
            return;
        }

        CompanionPresentation replacement = createAndShow(display);
        if (replacement == null) {
            // Keep whatever is already up rather than blanking the panel on a failed swap.
            return;
        }

        CompanionPresentation previous = presentation;
        presentation = replacement;
        presentationDisplayId = display.getDisplayId();
        boolean sheetLost = false;
        if (previous != null) {
            // The sheet is not carried over: it was drawn for the page stack of whoever opened
            // it, and the replacement starts without one.
            sheetLost = previous.isMenuOpen();
            // Its listener is cleared so this deliberate dismissal is not read as the user
            // closing the panel, and CompanionState keeps the replacement's listener.
            previous.setOnDismissListener(null);
            previous.dismiss();
        }
        LimeLog.info("CompanionDisplay: re-hosted on " + owner.getClass().getSimpleName());
        if (sheetLost) {
            onMenuClosed();
        }
        if (previous == null) {
            notifyShowingChanged();
        }
    }

    private Display findDisplay() {
        return DisplayTargets.findCompanionDisplay(application, getDisplayIdOf(owner));
    }

    private CompanionPresentation createAndShow(Display display) {
        CompanionPresentation newPresentation = new CompanionPresentation(owner, display, state);
        newPresentation.setOnDismissListener(this::onPresentationDismissed);
        try {
            newPresentation.show();
        } catch (WindowManager.InvalidDisplayException e) {
            // The display went away between selecting it and showing the window.
            LimeLog.warning("CompanionDisplay: display " + display.getDisplayId()
                    + " vanished: " + e.getMessage());
            return null;
        }
        if (textKeyboardRequested) {
            // A text field on the host still has focus: the new panel keeps its keyboard
            newPresentation.setTextKeyboardVisible(true);
        }
        return newPresentation;
    }

    /**
     * The panel was dismissed while it was the current one. If its display is still alive, the
     * user closed it with a back gesture, so keep it off until they ask for it again; otherwise
     * the display went away, so let sync() bring the panel back when a display returns.
     */
    private void onPresentationDismissed(DialogInterface dialog) {
        if (presentation != dialog) {
            return;
        }

        int dismissedId = presentationDisplayId;
        boolean sheetLost = presentation.isMenuOpen();
        presentation = null;
        presentationDisplayId = Display.INVALID_DISPLAY;
        // Told only now, with no panel left: the stream asks isMenuOpen() and must hear "no".
        if (sheetLost) {
            onMenuClosed();
        }
        notifyShowingChanged();

        Display display = displayManager.getDisplay(dismissedId);
        boolean displayAlive = display != null && display.isValid()
                && display.getState() == Display.STATE_ON;
        if (displayAlive) {
            LimeLog.info("CompanionDisplay: dismissed by the user; staying off until turned on");
            state.setOwner(CompanionState.Owner.OFF);
        } else {
            handler.post(this::sync);
        }
    }

    private void detach() {
        if (presentation == null) {
            return;
        }

        LimeLog.info("CompanionDisplay: detaching from display " + presentationDisplayId);
        // Clear the dismiss listener first so our own dismissal isn't mistaken for the user
        // closing the panel out from under us.
        boolean sheetLost = presentation.isMenuOpen();
        presentation.setOnDismissListener(null);
        presentation.dismiss();
        presentation = null;
        presentationDisplayId = Display.INVALID_DISPLAY;
        // A sheet that goes down with its panel never calls hideMenu() itself, so the stream
        // would go on steering the pad into nothing — the pad stuck until the stream ended.
        if (sheetLost) {
            onMenuClosed();
        }
        notifyShowingChanged();
    }

    private void notifyShowingChanged() {
        if (showingListener != null) {
            showingListener.run();
        }
    }

    /**
     * The display the Activity itself lives on. With Artemis' external monitor mode that is
     * not the default display, so it is resolved rather than assumed.
     */
    private static int getDisplayIdOf(Activity activity) {
        Display display = activity.getWindowManager().getDefaultDisplay();
        return display != null ? display.getDisplayId() : Display.DEFAULT_DISPLAY;
    }

    // --- Activity lifecycle ---

    @Override
    public void onActivityStarted(Activity activity) {
        startedActivities++;
    }

    @Override
    public void onActivityResumed(Activity activity) {
        if (owner != activity) {
            // Re-host on the Activity that now owns the foreground, without blanking the panel.
            owner = activity;
            rehost();
        } else {
            sync();
        }
    }

    @Override
    public void onActivityStopped(Activity activity) {
        startedActivities--;
        if (startedActivities == 0) {
            // The whole app went to the background; do not squat the secondary display.
            detach();
        }
    }

    @Override
    public void onActivityDestroyed(Activity activity) {
        if (owner == activity) {
            detach();
            owner = null;
        }
    }

    @Override
    public void onActivityCreated(Activity activity, Bundle savedInstanceState) {
    }

    @Override
    public void onActivityPaused(Activity activity) {
    }

    @Override
    public void onActivitySaveInstanceState(Activity activity, Bundle outState) {
    }
}
