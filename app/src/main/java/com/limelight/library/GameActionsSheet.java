package com.limelight.library;

import android.app.Dialog;
import android.content.Context;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.StringRes;

import com.limelight.R;
import com.limelight.ui.MenuSheet;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Everything that can be done to one game, as a {@link MenuSheet}: a long-press or Y on a tile,
 * and a tap while another game is running.
 *
 * <p>It replaces the old library's {@code showAppMenu}, with the same rows under the same
 * conditions, so nothing a user could do to a game is lost in the move:
 *
 * <pre>
 *   host idle             Start on the PC (what a tap does, so the sheet raised by a long-press
 *                         or Y still leads with it) · start in the display the tap does not use
 *   this game running     Resume · Quit
 *   another game running  Quit and start (preferred display) · Quit and start (the other one)
 *   then                  Hide (checked when hidden; not offered for the running game unless
 *                         it is hidden already) · Companion app (where a second screen exists)
 *                         · Stats (where the host keeps them and the game has a UUID)
 *                         · Details · Create shortcut (Android 8+) · Export launcher file
 * </pre>
 *
 * One change: "Create shortcut" used to be offered only once the tile showed a cover, because
 * the cover was read off the tile's image view. The cover now comes from the loader by game
 * ({@code CachedAppAssetLoader.getBitmap}), and a game with none gets the app icon, so the row
 * is always there.
 *
 * The rows are decided by {@link #itemsFor}, a pure function of the game and the host's state,
 * so they can be tested without a screen; the sheet leads them with Start
 * ({@link #sheetItemsFor}), which a game's page has as its own button instead. What a row does
 * is the {@link Runner}'s business, {@link LaunchController} in production. Main thread only.
 */
public final class GameActionsSheet {

    /** One row of the sheet. The order of {@link #itemsFor}'s result is the order shown. */
    public enum Item {
        /** "Start on <PC>": a tap's start, in the preferred display. The sheet's, not a page's. */
        START(R.string.companion_play_on),
        START_PRIMARY_DISPLAY(R.string.applist_menu_start_primarydisplay),
        START_VIRTUAL_DISPLAY(R.string.applist_menu_start_vdisplay),
        RESUME(R.string.applist_menu_resume),
        QUIT(R.string.applist_menu_quit),
        QUIT_AND_START(R.string.applist_menu_quit_and_start),
        QUIT_AND_START_PRIMARY_DISPLAY(R.string.applist_menu_quit_and_start_primarydisplay),
        QUIT_AND_START_VIRTUAL_DISPLAY(R.string.applist_menu_quit_and_start_vdisplay),
        HIDE(R.string.applist_menu_hide_app),
        COMPANION_APP(R.string.applist_menu_companion_app),
        STATS(R.string.applist_menu_stats),
        DETAILS(R.string.applist_menu_details),
        PIN_SHORTCUT(R.string.applist_menu_scut),
        EXPORT_LAUNCHER(R.string.applist_menu_export_launcher);

        @StringRes
        public final int label;

        Item(@StringRes int label) {
            this.label = label;
        }
    }

    /** What the rows depend on beyond the game itself. */
    public static final class State {
        /** What the host is running, or 0. */
        public final int runningAppId;
        /** The "use virtual display" preference: the display a plain start goes to. */
        public final boolean useVirtualDisplay;
        public final boolean companionSupported;
        public final boolean statsAvailable;
        public final boolean canPinShortcuts;

        public State(int runningAppId, boolean useVirtualDisplay, boolean companionSupported,
                     boolean statsAvailable, boolean canPinShortcuts) {
            this.runningAppId = runningAppId;
            this.useVirtualDisplay = useVirtualDisplay;
            this.companionSupported = companionSupported;
            this.statsAvailable = statsAvailable;
            this.canPinShortcuts = canPinShortcuts;
        }
    }

    /** Runs a row. */
    public interface Runner {
        void run(@NonNull Item item, @NonNull LibraryApp app);
    }

    private GameActionsSheet() {
    }

    /** The rows for a game in this state, in order. */
    @NonNull
    public static List<Item> itemsFor(@NonNull LibraryApp app, @NonNull State state) {
        List<Item> items = new ArrayList<>();
        int appId = app.appId();
        if (state.runningAppId == 0) {
            // A tap already starts in the preferred display, so the sheet offers the other one.
            items.add(state.useVirtualDisplay ? Item.START_PRIMARY_DISPLAY : Item.START_VIRTUAL_DISPLAY);
        } else if (state.runningAppId == appId) {
            items.add(Item.RESUME);
            items.add(Item.QUIT);
        } else {
            items.add(Item.QUIT_AND_START);
            items.add(state.useVirtualDisplay
                    ? Item.QUIT_AND_START_PRIMARY_DISPLAY : Item.QUIT_AND_START_VIRTUAL_DISPLAY);
        }

        // Hiding the running game would take the way back to it off the shelves; a game hidden
        // already can always be shown again.
        if (state.runningAppId != appId || app.hidden) {
            items.add(Item.HIDE);
        }
        if (state.companionSupported) {
            items.add(Item.COMPANION_APP);
        }
        String uuid = app.app.getAppUUID();
        if (state.statsAvailable && uuid != null && !uuid.isEmpty()) {
            items.add(Item.STATS);
        }
        items.add(Item.DETAILS);
        if (state.canPinShortcuts) {
            items.add(Item.PIN_SHORTCUT);
        }
        items.add(Item.EXPORT_LAUNCHER);
        return Collections.unmodifiableList(items);
    }

    /**
     * The sheet's rows: {@link #itemsFor}, led by {@link Item#START} while the host runs nothing,
     * so the sheet opened on a game always offers to play it first.
     */
    @NonNull
    public static List<Item> sheetItemsFor(@NonNull LibraryApp app, @NonNull State state) {
        List<Item> rows = itemsFor(app, state);
        if (state.runningAppId != 0) {
            return rows;
        }
        List<Item> items = new ArrayList<>(rows.size() + 1);
        items.add(Item.START);
        items.addAll(rows);
        return Collections.unmodifiableList(items);
    }

    /**
     * Builds the sheet for a game.
     *
     * @param companionLabel the companion row's label, naming the app already chosen; null for
     *                       the plain label
     * @param hostName       the PC Start names; null for a plain "Play"
     */
    @NonNull
    public static MenuSheet build(@NonNull Context context, @NonNull LibraryApp app,
                                  @NonNull State state, @Nullable String companionLabel,
                                  @Nullable String hostName, @NonNull Runner runner) {
        MenuSheet sheet = new MenuSheet(context).setTitle(app.app.getAppName());
        for (final Item item : sheetItemsFor(app, state)) {
            Runnable action = () -> runner.run(item, app);
            if (item == Item.START) {
                sheet.add(hostName != null ? context.getString(item.label, hostName)
                        : context.getString(R.string.companion_play), action);
            } else if (item == Item.HIDE) {
                sheet.addChecked(context.getString(item.label), app.hidden, action);
            } else if (item == Item.COMPANION_APP && companionLabel != null) {
                sheet.add(companionLabel, action);
            } else {
                sheet.add(context.getString(item.label), action);
            }
        }
        return sheet;
    }

    /** Builds and shows the sheet, centred and focused for the d-pad. */
    @NonNull
    public static Dialog show(@NonNull Context context, @NonNull LibraryApp app,
                              @NonNull State state, @Nullable String companionLabel,
                              @Nullable String hostName, @NonNull Runner runner) {
        return build(context, app, state, companionLabel, hostName, runner).showCentered(context);
    }
}
