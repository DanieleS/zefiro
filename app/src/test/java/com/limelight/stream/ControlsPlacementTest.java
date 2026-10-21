package com.limelight.stream;

import androidx.annotation.NonNull;

import com.limelight.stream.ControlsPlacement.Surface;
import com.limelight.stream.StreamAction.Id;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

/**
 * The controls follow the companion panel as it comes and goes mid-stream (the Thor's lower
 * screen switched off and on): which surface holds them, and that an open page survives the move
 * from the side panel's layout to the sheet's and back.
 */
public class ControlsPlacementTest {

    @Test
    public void theSheetOnlyWhilstThePanelShowsAndOnlyOverTheGame() {
        assertEquals(Surface.COMPANION, ControlsPlacement.surfaceFor(true, true));
        assertEquals(Surface.MAIN, ControlsPlacement.surfaceFor(true, false));
        // The external display's phone keeps its own sheet, panel or not
        assertEquals(Surface.MAIN, ControlsPlacement.surfaceFor(false, true));
    }

    @Test
    public void openControlsMoveWhenThePanelComesOrGoes() {
        assertEquals(Surface.MAIN, ControlsPlacement.moveTo(Surface.COMPANION, true, false));
        assertEquals(Surface.COMPANION, ControlsPlacement.moveTo(Surface.MAIN, true, true));
    }

    @Test
    public void nothingMovesWhenAlreadyInPlaceOrClosed() {
        assertNull(ControlsPlacement.moveTo(Surface.COMPANION, true, true));
        assertNull(ControlsPlacement.moveTo(Surface.MAIN, true, false));
        assertNull(ControlsPlacement.moveTo(Surface.MAIN, false, true));
        assertNull(ControlsPlacement.moveTo(null, true, true));
    }

    @Test
    public void aPageOpenOnTheSheetReopensOnTheSidePanelAndBack() {
        StreamAction tree = StreamActions.build(caps());
        Recording sheet = new Recording(true);
        Recording side = new Recording(false);

        // Mouse mode, two pages deep on the sheet (More > Mouse > Mouse mode)
        sheet.open(tree);
        sheet.choose(Id.MORE);
        sheet.choose(Id.MOUSE);
        sheet.choose(Id.MOUSE_MODE);
        assertEquals(Id.MOUSE_MODE, sheet.shownPage().id);

        // The lower screen goes off: the side panel opens on the same page, one level shallower
        // since Mouse sits on its main page
        side.openAt(tree, sheet.shownPage().id);
        assertEquals(Id.MOUSE_MODE, side.shownPage().id);
        side.back();
        assertEquals(Id.MOUSE, side.shownPage().id);
        side.back();
        assertEquals(Id.ROOT, side.shownPage().id);

        // And on again: Bitrate, a list row there and a tile here
        side.choose(Id.BITRATE);
        sheet.openAt(tree, side.shownPage().id);
        assertEquals(Id.BITRATE, sheet.shownPage().id);
        sheet.back();
        assertEquals(Id.ROOT, sheet.shownPage().id);
    }

    @Test
    public void aPageTheOtherLayoutLacksReopensOnTheRoot() {
        StreamAction tree = StreamActions.build(caps());
        Recording sheet = new Recording(true);
        Recording side = new Recording(false);
        sheet.open(tree);
        sheet.choose(Id.CLIPBOARD);
        side.openAt(tree, sheet.shownPage().id);
        assertEquals(Id.ROOT, side.shownPage().id);
    }

    @Test
    public void reopeningDoesNotAskThePageAgain() {
        StreamAction tree = StreamActions.build(caps());
        Recording side = new Recording(false);
        side.openAt(tree, Id.SPECIAL_KEYS);
        assertEquals(Id.SPECIAL_KEYS, side.shownPage().id);
        // Nothing asked: the page (and its toast) was let open once already, where it was first
        assertEquals(Collections.<Id>emptyList(), side.asked);
    }

    private static StreamCapabilities caps() {
        return StreamCapabilities.builder()
                .captureEnabled(true)
                .liveBitrate(false, 40000, 50000)
                .locale(Locale.US)
                .allowChangeMouseMode(true)
                .mouseModes(Arrays.asList(new StreamCapabilities.MouseMode(0, "Multi-touch"),
                        new StreamCapabilities.MouseMode(2, "Trackpad")), 2)
                .onExternalDisplay(false)
                .companion(true, true)
                .defaultSpecialKeys(true)
                .build();
    }

    /** A surface that draws nothing and remembers what it was asked. */
    private static final class Recording extends PagedSurface {

        private final boolean compact;
        final List<Id> asked;
        private StreamAction shown;

        Recording(boolean compact) {
            this(compact, new ArrayList<Id>());
        }

        private Recording(boolean compact, final List<Id> asked) {
            super(new Callbacks() {
                @Override
                public boolean onPageOpening(@NonNull StreamAction page) {
                    if (page.id != Id.ROOT) {
                        asked.add(page.id);
                    }
                    return true;
                }

                @Override
                public void onLeaf(@NonNull StreamAction leaf) {
                }
            });
            this.compact = compact;
            this.asked = asked;
        }

        @NonNull
        @Override
        protected StreamAction arrange(@NonNull StreamAction tree) {
            return compact ? StreamActions.compact(tree) : tree;
        }

        @Override
        protected void render(@NonNull StreamAction page) {
            shown = page;
        }

        @Override
        protected void dismissView() {
            shown = null;
        }

        @Override
        public boolean isOpen() {
            return shown != null;
        }

        @Override
        public boolean onNav(@NonNull Nav nav) {
            return isOpen();
        }

        void choose(Id id) {
            for (StreamAction child : shown.children) {
                if (child.id == id) {
                    activate(child);
                    return;
                }
            }
            throw new AssertionError(id + " not on " + shown);
        }
    }
}
