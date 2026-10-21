package com.limelight.settings;

/**
 * The Settings section's layout choices, as plain functions of the window and of whether the
 * companion panel is up, so the section can make them again whenever either changes: a phone
 * turned, or the Thor's lower screen switched off and on while Settings is open.
 *
 * <pre>
 *                          panel up (the Thor)        no panel (phone, Thor screen off)
 *   a list row's A / tap   its choices on the panel   its choices in a dialog on top
 *   a row's description    one line; all of it below  one line wide, two compact; all of it
 *                                                     on the focused row
 *   the header             fixed above the list on the Thor; scrolling with it on a phone;
 *                          on a turned phone the field and chips fixed, the card scrolling
 * </pre>
 *
 * Whether the panel is up is {@link com.limelight.companion.CompanionDisplayManager#panelShowing()},
 * observed, never read once. Pure; no Android types.
 */
final class SettingsLayout {

    /** A description shown in full. */
    static final int ALL_LINES = Integer.MAX_VALUE;

    private SettingsLayout() {
    }

    /**
     * Whether the header (search, card, chips) is the list's first item, scrolling away with the
     * rows, rather than fixed above them. A phone in portrait has no room to keep it; the Thor
     * keeps it. Searching, it is always fixed: it is the search bar then.
     */
    static boolean headerInList(boolean wide, boolean searching) {
        return !searching && !wide;
    }

    /**
     * Whether only the pinned card is the list's first item, while the field and the chips stay
     * fixed above it: a phone in landscape, where the whole header would take half the height,
     * but scrolling it all away would take the chips, the way around the list, with it.
     *
     * @param shortWindow a wide window too short to keep it all ({@code WindowClass.isShortWide})
     */
    static boolean cardInList(boolean wide, boolean shortWindow, boolean searching) {
        return !searching && wide && shortWindow;
    }

    /**
     * Whether a list setting's choices are offered on the panel instead of in a dialog on top.
     * With the panel up they are already spelled out there for the focused row, so A or a tap on
     * the row binds the panel to it rather than covering the main screen.
     */
    static boolean choicesOnPanel(boolean panelShowing, SettingsSearch.Kind kind) {
        return panelShowing && kind == SettingsSearch.Kind.LIST;
    }

    /**
     * How many lines of its description a row in the list shows: one in a wide window and two
     * in a compact one, as the mocks' compact rows; the focused row shows all of it when there is
     * no panel to read it on.
     */
    static int summaryLines(boolean wide, boolean focused, boolean panelShowing) {
        if (focused && !panelShowing) {
            return ALL_LINES;
        }
        return wide ? 1 : 2;
    }
}
