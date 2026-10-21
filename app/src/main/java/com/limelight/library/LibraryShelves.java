package com.limelight.library;

import androidx.annotation.Nullable;

import com.limelight.grid.AppSortOrder;
import com.limelight.nvstream.http.AppMetadata;
import com.limelight.nvstream.http.NvApp;
import com.limelight.utils.AppLaunchHistory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * Which games each shelf of the library home holds.
 *
 * <p>A shelf is a short row, not the library: it shows a few games and leads to "See all" for
 * the rest. Neither shelf shows hidden games; hiding a game is the user taking it off display,
 * and a shelf is display.
 *
 * <p>Pure, so the rules are tested apart from the views that draw them.
 */
public final class LibraryShelves {

    /** How many games a shelf holds at most. */
    public static final int SHELF_SIZE = 12;

    private LibraryShelves() {
    }

    /**
     * "Recently played": the games ever played, most recent first, at most {@link #SHELF_SIZE}.
     *
     * <p>It uses {@link AppSortOrder#RECENT} and {@link AppSortOrder#lastPlayed} rather than a
     * rule of its own, so the shelf and the "Recent" sort always agree on which games were played
     * and in what order. A game nobody has played is left off rather than padded in by name: an
     * empty shelf is omitted, a shelf of unplayed games would be a lie.
     *
     * @param metadata Playnite metadata keyed by upper-cased app UUID; may be empty.
     * @param history  this device's launch record; may be null.
     */
    public static List<LibraryApp> recent(List<LibraryApp> apps, Map<String, AppMetadata> metadata,
                                          AppLaunchHistory history) {
        List<LibraryApp> played = new ArrayList<>();
        for (LibraryApp app : apps) {
            if (!app.hidden && AppSortOrder.lastPlayed(app.app, metadata, history) > 0) {
                played.add(app);
            }
        }
        final Comparator<NvApp> order = AppSortOrder.RECENT.comparator(metadata, history);
        // An explicit lambda rather than Comparator.comparing, which needs API 24.
        Collections.sort(played, (a, b) -> order.compare(a.app, b.app));
        return cap(played, SHELF_SIZE);
    }

    /** {@link #preview(List, Comparator, int)} at {@link #SHELF_SIZE}. */
    public static List<LibraryApp> preview(List<LibraryApp> apps, Comparator<NvApp> order) {
        return preview(apps, order, SHELF_SIZE);
    }

    /**
     * "All games": the first {@code n} visible games in the library's current order, so the shelf
     * is the head of what "See all" lists.
     *
     * @param order the library's order, from {@link AppSortOrder#comparator}; null keeps the
     *              order {@code apps} already has.
     */
    public static List<LibraryApp> preview(List<LibraryApp> apps, final Comparator<NvApp> order, int n) {
        List<LibraryApp> visible = new ArrayList<>();
        for (LibraryApp app : apps) {
            if (!app.hidden) {
                visible.add(app);
            }
        }
        if (order != null) {
            Collections.sort(visible, (a, b) -> order.compare(a.app, b.app));
        }
        return cap(visible, n);
    }

    /** How many games "See all" leads to: every visible one. */
    /**
     * The order See all opens in when nothing asked for one: what was played last, while the
     * user has never picked an order and Recently played has something in it (the same history
     * the shelf reads), so the grid starts with the games the user goes back to. Null otherwise,
     * for the library's own order.
     *
     * @param recent {@link #recent} for the library as it stands
     */
    @Nullable
    public static AppSortOrder seeAllDefault(boolean orderChosen, List<LibraryApp> recent) {
        return !orderChosen && !recent.isEmpty() ? AppSortOrder.RECENT : null;
    }

    public static int visibleCount(List<LibraryApp> apps) {
        int count = 0;
        for (LibraryApp app : apps) {
            if (!app.hidden) {
                count++;
            }
        }
        return count;
    }

    private static List<LibraryApp> cap(List<LibraryApp> list, int n) {
        if (list.size() > n) {
            list = new ArrayList<>(list.subList(0, Math.max(0, n)));
        }
        return Collections.unmodifiableList(list);
    }
}
