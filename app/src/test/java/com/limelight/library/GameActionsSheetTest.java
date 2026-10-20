package com.limelight.library;

import com.limelight.library.GameActionsSheet.Item;
import com.limelight.library.GameActionsSheet.State;
import com.limelight.nvstream.http.NvApp;

import org.junit.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * A game's actions sheet offers what the old library's per-game menu offered, state by state, in
 * the same order; the shortcut row no longer waits for a cover on the tile.
 */
public class GameActionsSheetTest {

    private static LibraryApp game(int id, String uuid, boolean hidden) {
        return new LibraryApp(new NvApp("Game " + id, uuid, id, false), false, hidden);
    }

    private static State state(int running, boolean useVirtualDisplay) {
        return new State(running, useVirtualDisplay, false, false, true);
    }

    @Test
    public void idleHost_offersTheDisplayATapDoesNotUse() {
        List<Item> items = GameActionsSheet.itemsFor(game(1, "u1", false), state(0, false));

        assertEquals(Arrays.asList(Item.START_VIRTUAL_DISPLAY, Item.HIDE, Item.DETAILS,
                Item.PIN_SHORTCUT, Item.EXPORT_LAUNCHER), items);
        assertEquals(Item.START_PRIMARY_DISPLAY,
                GameActionsSheet.itemsFor(game(1, "u1", false), state(0, true)).get(0));
    }

    @Test
    public void thisGameRunning_offersResumeAndQuit_andNoHide() {
        List<Item> items = GameActionsSheet.itemsFor(game(1, "u1", false), state(1, false));

        assertEquals(Arrays.asList(Item.RESUME, Item.QUIT, Item.DETAILS,
                Item.PIN_SHORTCUT, Item.EXPORT_LAUNCHER), items);
    }

    @Test
    public void thisGameRunningAndHidden_canStillBeShownAgain() {
        assertTrue(GameActionsSheet.itemsFor(game(1, "u1", true), state(1, false))
                .contains(Item.HIDE));
    }

    @Test
    public void anotherGameRunning_offersQuitAndStartInBothDisplays() {
        List<Item> items = GameActionsSheet.itemsFor(game(1, "u1", false), state(2, false));
        assertEquals(Item.QUIT_AND_START, items.get(0));
        assertEquals(Item.QUIT_AND_START_VIRTUAL_DISPLAY, items.get(1));

        items = GameActionsSheet.itemsFor(game(1, "u1", false), state(2, true));
        assertEquals(Item.QUIT_AND_START_PRIMARY_DISPLAY, items.get(1));
    }

    @Test
    public void stats_needTheHostsStatsAndAGameUuid() {
        State withStats = new State(0, false, false, true, true);
        assertTrue(GameActionsSheet.itemsFor(game(1, "u1", false), withStats).contains(Item.STATS));
        assertFalse(GameActionsSheet.itemsFor(game(1, null, false), withStats).contains(Item.STATS));
        assertFalse(GameActionsSheet.itemsFor(game(1, "u1", false), state(0, false))
                .contains(Item.STATS));
    }

    @Test
    public void companionAndShortcut_followWhatTheDeviceCanDo() {
        State companion = new State(0, false, true, false, false);
        List<Item> items = GameActionsSheet.itemsFor(game(1, "u1", false), companion);

        assertTrue(items.contains(Item.COMPANION_APP));
        assertFalse(items.contains(Item.PIN_SHORTCUT));
    }
}
