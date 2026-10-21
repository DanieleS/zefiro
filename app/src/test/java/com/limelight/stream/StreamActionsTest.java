package com.limelight.stream;

import com.limelight.R;
import com.limelight.binding.input.KeyboardTranslator;
import com.limelight.stream.StreamAction.Group;
import com.limelight.stream.StreamAction.Id;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * The controls' tree against the old in-game menu: every entry it had is still reachable (bar
 * the floating-button toggle and Cancel), each behind the same condition, and the tree has the
 * shape the surfaces draw.
 */
public class StreamActionsTest {

    /** Everything the stream can offer, on. */
    private static StreamCapabilities.Builder everything() {
        return StreamCapabilities.builder()
                .captureEnabled(true)
                .liveBitrate(false, 40000, 50000)
                .locale(Locale.US)
                .allowChangeMouseMode(true)
                .mouseModes(Arrays.asList(new StreamCapabilities.MouseMode(0, "Multi-touch"),
                        new StreamCapabilities.MouseMode(2, "Trackpad")), 2)
                .onExternalDisplay(false)
                .companion(true, false)
                .companionAppSupported(true)
                .canRotate(true)
                .serverCmds(Arrays.asList("restart", "sleep"))
                .deviceOptions(Collections.singletonList(
                        new StreamCapabilities.DeviceOption("Enable Controller Mouse Emulation", true)))
                .defaultSpecialKeys(true)
                .customKeys(StreamCapabilities.parseCustomKeys(
                        "{\"data\":[{\"name\":\"Copy\",\"keys\":[\"VK_LCONTROL\",\"0x43\"]}]}"));
    }

    /** Nothing optional. */
    private static StreamCapabilities.Builder nothing() {
        return StreamCapabilities.builder()
                .captureEnabled(false)
                .allowChangeMouseMode(false)
                .onExternalDisplay(true)
                .companion(false, false)
                .companionAppSupported(false)
                .canRotate(false)
                .defaultSpecialKeys(false);
    }

    private static Set<Id> ids(StreamAction tree) {
        Set<Id> out = EnumSet.noneOf(Id.class);
        collect(tree, out);
        return out;
    }

    private static void collect(StreamAction action, Set<Id> out) {
        out.add(action.id);
        if (action.children != null) {
            for (StreamAction child : action.children) {
                collect(child, out);
            }
        }
    }

    private static List<Id> idsOf(List<StreamAction> page, Group group) {
        List<Id> out = new ArrayList<>();
        for (StreamAction action : page) {
            if (action.group == group) {
                out.add(action.id);
            }
        }
        return out;
    }

    private static StreamAction page(StreamAction tree, Id id) {
        StreamAction page = tree.find(id);
        assertNotNull(id + " missing", page);
        assertTrue(id + " is not a page", page.isPage());
        return page;
    }

    @Test
    public void everyFormerEntryIsReachable() {
        Set<Id> ids = ids(StreamActions.build(everything().build()));

        // The old Quick Menu and Advanced Menu, entry by entry, under their new ids.
        Id[] former = {
                Id.DISCONNECT, Id.QUIT,
                Id.CLIPBOARD_UPLOAD, Id.CLIPBOARD_FETCH,
                Id.SERVER_COMMANDS, Id.SERVER_COMMAND,
                Id.KEYBOARD, Id.PAN_ZOOM, Id.ROTATE, Id.COMPANION_SCREEN,
                Id.SCREENSHOT, Id.SAVE_REPLAY, Id.RECORD,
                Id.BITRATE, Id.BITRATE_ADAPTIVE, Id.BITRATE_CHOICE,
                Id.MOUSE_MODE, Id.MOUSE_MODE_CHOICE, Id.LOCAL_CURSOR, Id.OVERLAY, Id.COMPANION_APP,
                Id.SPECIAL_KEYS_BAR, Id.GAMEPAD, Id.FULL_KEYBOARD, Id.TASK_MANAGER,
                Id.SPECIAL_KEYS, Id.SPECIAL_KEY,
                Id.TOUCH_SENSITIVITY, Id.DEVICE_OPTION,
        };
        for (Id id : former) {
            assertTrue(id + " missing", ids.contains(id));
        }
    }

    @Test
    public void mainPageIsTilesThenListThenDanger() {
        List<StreamAction> main = StreamActions.build(everything().build()).children;
        assertNotNull(main);

        assertEquals(Arrays.asList(Id.KEYBOARD, Id.GAMEPAD, Id.MOUSE, Id.OVERLAY),
                idsOf(main, Group.TILE));
        assertEquals(Arrays.asList(Id.SCREENSHOT, Id.BITRATE, Id.SPECIAL_KEYS, Id.MORE),
                idsOf(main, Group.LIST));
        assertEquals(Arrays.asList(Id.DISCONNECT, Id.QUIT), idsOf(main, Group.DANGER));

        // In drawing order: no group comes back after the next one has started.
        Group last = Group.TILE;
        for (StreamAction action : main) {
            assertTrue(action + " out of order", action.group.ordinal() >= last.ordinal());
            last = action.group;
        }
    }

    @Test
    public void mouseModeIsAPageOfTheAllowedModesWithTheOneInUseChecked() {
        StreamAction modes = page(StreamActions.build(everything().build()), Id.MOUSE_MODE);
        List<StreamAction> rows = modes.children;
        assertEquals(Arrays.asList(Id.MOUSE_MODE_CHOICE, Id.MOUSE_MODE_CHOICE, Id.LOCAL_CURSOR),
                idsOf(rows, Group.LIST));
        assertEquals("Multi-touch", rows.get(0).labelText);
        assertEquals(0, rows.get(0).arg);
        assertEquals(Boolean.FALSE, rows.get(0).checked);
        assertEquals(2, rows.get(1).arg);
        assertEquals(Boolean.TRUE, rows.get(1).checked);
        assertEquals(R.string.toggle_local_mouse_cursor, rows.get(2).labelRes);

        // Nothing applied yet: the toggle alone, nothing checked.
        StreamAction none = page(StreamActions.build(everything()
                .mouseModes(Collections.singletonList(new StreamCapabilities.MouseMode(1, "Mouse")), -1)
                .build()), Id.MOUSE_MODE);
        assertEquals(Boolean.FALSE, none.children.get(0).checked);
    }

    @Test
    public void everyEntryHasALabelAndThereIsNoCancel() {
        assertLabelled(StreamActions.build(everything().build()));
    }

    private static void assertLabelled(StreamAction action) {
        assertTrue(action + " has no label", action.labelRes != 0 || action.labelText != null);
        if (action.children != null) {
            for (StreamAction child : action.children) {
                assertLabelled(child);
            }
        }
    }

    @Test
    public void tilesCarryIconsAndToggleState() {
        StreamAction on = StreamActions.build(everything().hudVisible(true)
                .virtualControllerShown(true).build());
        assertEquals(Boolean.TRUE, on.find(Id.OVERLAY).checked);
        assertEquals(Boolean.TRUE, on.find(Id.GAMEPAD).checked);

        StreamAction off = StreamActions.build(everything().build());
        assertEquals(Boolean.FALSE, off.find(Id.OVERLAY).checked);
        assertEquals(Boolean.FALSE, off.find(Id.GAMEPAD).checked);
        // Not toggles.
        assertNull(off.find(Id.KEYBOARD).checked);
        assertNull(off.find(Id.DISCONNECT).checked);

        for (StreamAction tile : off.children) {
            if (tile.group == Group.TILE) {
                assertTrue(tile + " has no icon", tile.iconRes != 0);
            }
        }
    }

    @Test
    public void optionalEntriesFollowTheirConditions() {
        Set<Id> ids = ids(StreamActions.build(nothing().build()));

        assertFalse("capture off", ids.contains(Id.SCREENSHOT));
        assertFalse("capture off", ids.contains(Id.SAVE_REPLAY));
        assertFalse("capture off", ids.contains(Id.RECORD));
        assertFalse("no live bitrate", ids.contains(Id.BITRATE));
        assertFalse("external display", ids.contains(Id.GAMEPAD));
        assertFalse("no mouse mode and no pad", ids.contains(Id.MOUSE));
        assertFalse("not over the game", ids.contains(Id.ROTATE));
        assertFalse("no companion", ids.contains(Id.COMPANION_SCREEN));
        assertFalse("no companion app", ids.contains(Id.COMPANION_APP));
        assertFalse("defaults cleared, none imported", ids.contains(Id.SPECIAL_KEY));

        // Always there.
        for (Id id : new Id[]{Id.KEYBOARD, Id.OVERLAY, Id.SPECIAL_KEYS, Id.MORE,
                Id.CLIPBOARD_UPLOAD, Id.CLIPBOARD_FETCH, Id.SERVER_COMMANDS, Id.PAN_ZOOM,
                Id.SPECIAL_KEYS_BAR, Id.FULL_KEYBOARD, Id.TASK_MANAGER, Id.TOUCH_SENSITIVITY,
                Id.DISCONNECT, Id.QUIT}) {
            assertTrue(id + " missing", ids.contains(id));
        }
    }

    @Test
    public void mousePageHoldsTheModePickerAndThePadsOwnOptions() {
        List<StreamAction> mouse = page(StreamActions.build(everything().build()), Id.MOUSE).children;
        assertEquals(2, mouse.size());
        assertEquals(Id.MOUSE_MODE, mouse.get(0).id);
        assertEquals(Id.DEVICE_OPTION, mouse.get(1).id);
        assertEquals("Enable Controller Mouse Emulation", mouse.get(1).labelText);
        assertEquals(0, mouse.get(1).arg);
        assertTrue(mouse.get(1).withGameFocus);

        // Mouse mode locked, but opened from a pad: the page is just the pad's options.
        StreamAction padOnly = StreamActions.build(everything().allowChangeMouseMode(false).build());
        assertEquals(Collections.singletonList(Id.DEVICE_OPTION),
                idsOf(page(padOnly, Id.MOUSE).children, Group.LIST));

        // Opened by touch: no pad options.
        StreamAction touch = StreamActions.build(everything()
                .deviceOptions(Collections.emptyList()).build());
        assertEquals(Collections.singletonList(Id.MOUSE_MODE),
                idsOf(page(touch, Id.MOUSE).children, Group.LIST));
    }

    @Test
    public void bitratePageOffersAdaptationThenTheLadder() {
        StreamAction tree = StreamActions.build(everything().liveBitrate(true, 37500, 50000).build());
        StreamAction bitrate = page(tree, Id.BITRATE);
        assertEquals(R.string.game_menu_bitrate_adaptive, bitrate.labelRes);
        assertEquals("37.5", bitrate.labelArg);

        List<StreamAction> rows = bitrate.children;
        assertEquals(Id.BITRATE_ADAPTIVE, rows.get(0).id);
        assertEquals(R.string.game_menu_bitrate_adaptive_on, rows.get(0).labelRes);

        boolean sawChosen = false;
        boolean sawCurrent = false;
        for (StreamAction row : rows.subList(1, rows.size())) {
            assertEquals(Id.BITRATE_CHOICE, row.id);
            if (row.arg == 50000) {
                sawChosen = true;
            }
            if (row.arg == 37500) {
                sawCurrent = true;
                assertEquals(R.string.game_menu_bitrate_choice_current, row.labelRes);
            } else {
                assertEquals(R.string.game_menu_bitrate_choice, row.labelRes);
            }
        }
        assertTrue(sawChosen);
        assertTrue(sawCurrent);

        StreamAction manual = StreamActions.build(everything().build());
        assertEquals(R.string.game_menu_bitrate, manual.find(Id.BITRATE).labelRes);
        assertEquals(R.string.game_menu_bitrate_adaptive_off, manual.find(Id.BITRATE_ADAPTIVE).labelRes);
    }

    @Test
    public void specialKeysAreTheDefaultsThenTheImportedOnes() {
        List<StreamAction> keys = page(StreamActions.build(everything().build()), Id.SPECIAL_KEYS).children;
        assertEquals(StreamActions.DEFAULT_KEY_COUNT + 1, keys.size());
        assertEquals(14, StreamActions.DEFAULT_KEY_COUNT);

        assertEquals(R.string.game_menu_send_keys_esc, keys.get(0).labelRes);
        assertArrayEquals(new short[]{KeyboardTranslator.VK_ESCAPE}, keys.get(0).keys());
        // "ALT + B" has always sent Win+Alt+B.
        StreamAction altB = keys.get(StreamActions.DEFAULT_KEY_COUNT - 1);
        assertEquals(R.string.game_menu_send_keys_alt_b, altB.labelRes);
        assertArrayEquals(new short[]{KeyboardTranslator.VK_LWIN, KeyboardTranslator.VK_LMENU,
                KeyboardTranslator.VK_B}, altB.keys());

        StreamAction custom = keys.get(keys.size() - 1);
        assertEquals("Copy", custom.labelText);
        assertArrayEquals(new short[]{0xA2, 0x43}, custom.keys());

        for (StreamAction key : keys) {
            // Sent at once, as the old Send special keys menu did.
            assertFalse(key.withGameFocus);
        }

        // Defaults cleared: only the imported ones.
        List<StreamAction> imported = page(StreamActions.build(everything()
                .defaultSpecialKeys(false).build()), Id.SPECIAL_KEYS).children;
        assertEquals(1, imported.size());
        assertEquals("Copy", imported.get(0).labelText);
    }

    @Test
    public void serverCommandsAreAPageEvenWhenEmpty() {
        StreamAction cmds = page(StreamActions.build(everything().build()), Id.SERVER_COMMANDS);
        assertEquals(2, cmds.children.size());
        assertEquals("> sleep", cmds.children.get(1).labelText);
        assertEquals(1, cmds.children.get(1).arg);
        assertTrue(cmds.children.get(1).withGameFocus);

        StreamAction none = page(StreamActions.build(nothing().build()), Id.SERVER_COMMANDS);
        assertTrue(none.children.isEmpty());
    }

    @Test
    public void moreLabelsFollowState() {
        StreamAction zoomed = StreamActions.build(everything().zoomEnabled(true)
                .companion(true, true).build());
        assertEquals(R.string.game_menu_disable_zoom_mode, zoomed.find(Id.PAN_ZOOM).labelRes);
        assertEquals(R.string.game_menu_companion_hide, zoomed.find(Id.COMPANION_SCREEN).labelRes);

        StreamAction plain = StreamActions.build(everything().build());
        assertEquals(R.string.game_menu_enable_zoom_mode, plain.find(Id.PAN_ZOOM).labelRes);
        assertEquals(R.string.game_menu_companion_show, plain.find(Id.COMPANION_SCREEN).labelRes);
    }

    @Test
    public void focusFlagsMatchTheOldMenu() {
        StreamAction tree = StreamActions.build(everything().build());
        // These inject keys or open something over the game.
        for (Id id : new Id[]{Id.KEYBOARD, Id.GAMEPAD, Id.OVERLAY, Id.MOUSE_MODE_CHOICE,
                Id.LOCAL_CURSOR, Id.SCREENSHOT,
                Id.SAVE_REPLAY, Id.RECORD, Id.CLIPBOARD_UPLOAD, Id.CLIPBOARD_FETCH, Id.PAN_ZOOM,
                Id.ROTATE, Id.SPECIAL_KEYS_BAR, Id.FULL_KEYBOARD, Id.TASK_MANAGER,
                Id.TOUCH_SENSITIVITY}) {
            assertTrue(id + " should wait for the game", tree.find(id).withGameFocus);
        }
        for (Id id : new Id[]{Id.DISCONNECT, Id.QUIT, Id.COMPANION_SCREEN, Id.COMPANION_APP,
                Id.BITRATE_ADAPTIVE, Id.BITRATE_CHOICE}) {
            assertFalse(id + " should run at once", tree.find(id).withGameFocus);
        }
        assertArrayEquals(new short[]{KeyboardTranslator.VK_LCONTROL, KeyboardTranslator.VK_LSHIFT,
                KeyboardTranslator.VK_ESCAPE}, tree.find(Id.TASK_MANAGER).keys());
    }

    @Test
    public void customKeysParseAndReportABadOne() {
        StreamCapabilities.CustomKeys empty = StreamCapabilities.parseCustomKeys("");
        assertTrue(empty.keys.isEmpty());
        assertFalse(empty.invalid);

        StreamCapabilities.CustomKeys bad = StreamCapabilities.parseCustomKeys(
                "{\"data\":[{\"name\":\"Esc\",\"keys\":[\"VK_ESCAPE\"]},"
                        + "{\"name\":\"Nope\",\"keys\":[\"KEY_Q\"]}]}");
        assertTrue(bad.invalid);
        // The ones before the bad key stay, as they always did.
        assertEquals(1, bad.keys.size());
        assertEquals("Esc", bad.keys.get(0).name);

        assertTrue(StreamCapabilities.parseCustomKeys("not json").invalid);
    }

    // --- The Thor sheet's layout (StreamActions.compact) ---

    @Test
    public void theThorSheetIsEightTilesThenTheTwoEnds() {
        StreamAction sheet = StreamActions.compact(StreamActions.build(everything().build()));
        assertEquals(Arrays.asList(Id.KEYBOARD, Id.SCREENSHOT, Id.SAVE_REPLAY, Id.OVERLAY,
                        Id.BITRATE, Id.CLIPBOARD, Id.SPECIAL_KEYS, Id.MORE),
                idsOf(sheet.children, Group.TILE));
        assertEquals(Collections.emptyList(), idsOf(sheet.children, Group.LIST));
        assertEquals(Arrays.asList(Id.DISCONNECT, Id.QUIT), idsOf(sheet.children, Group.DANGER));
        assertEquals(R.string.stream_action_keys, sheet.find(Id.SPECIAL_KEYS).labelRes);
        // The bitrate's value is the row's; a tile is named by what it is
        assertEquals(R.string.stream_action_bitrate, sheet.find(Id.BITRATE).labelRes);
        assertNull(sheet.find(Id.BITRATE).labelArg);
        for (StreamAction tile : sheet.children) {
            if (tile.group == Group.TILE) {
                assertTrue(tile + " has no icon", tile.iconRes != 0);
            }
        }
    }

    @Test
    public void theThorSheetLosesNothing() {
        StreamAction full = StreamActions.build(everything().build());
        Set<Id> sheet = ids(StreamActions.compact(full));
        Set<Id> missing = ids(full);
        missing.removeAll(sheet);
        assertEquals(EnumSet.noneOf(Id.class), missing);
        // Gamepad and Mouse, which have no tile, lead More
        List<StreamAction> more = page(StreamActions.compact(full), Id.MORE).children;
        assertEquals(Id.GAMEPAD, more.get(0).id);
        assertEquals(Id.MOUSE, more.get(1).id);
        assertFalse(ids(page(StreamActions.compact(full), Id.MORE)).contains(Id.SAVE_REPLAY));
        assertEquals(Arrays.asList(Id.CLIPBOARD_UPLOAD, Id.CLIPBOARD_FETCH),
                idsOf(page(StreamActions.compact(full), Id.CLIPBOARD).children, Group.MORE));
    }

    @Test
    public void theThorSheetLeavesOutWhatTheStreamCannotDo() {
        StreamAction sheet = StreamActions.compact(StreamActions.build(nothing().build()));
        // No capture tool, no live bitrate: the clipboard, the keys and More still have tiles
        assertEquals(Arrays.asList(Id.KEYBOARD, Id.OVERLAY, Id.CLIPBOARD, Id.SPECIAL_KEYS, Id.MORE),
                idsOf(sheet.children, Group.TILE));
    }

    @Test
    public void aPageIsFoundByItsPathInEitherLayout() {
        StreamAction full = StreamActions.build(everything().build());
        List<StreamAction> path = full.pathTo(Id.MOUSE_MODE);
        assertNotNull(path);
        assertEquals(Arrays.asList(Id.ROOT, Id.MOUSE, Id.MOUSE_MODE), idsOf(path));
        List<StreamAction> moved = StreamActions.compact(full).pathTo(Id.MOUSE_MODE);
        assertNotNull(moved);
        assertEquals(Arrays.asList(Id.ROOT, Id.MORE, Id.MOUSE, Id.MOUSE_MODE), idsOf(moved));
        // Only pages: a leaf is not somewhere to reopen on
        assertNull(full.pathTo(Id.KEYBOARD));
        assertNull(full.pathTo(Id.CLIPBOARD));
    }

    private static List<Id> idsOf(List<StreamAction> actions) {
        List<Id> out = new ArrayList<>();
        for (StreamAction action : actions) {
            out.add(action.id);
        }
        return out;
    }
}
