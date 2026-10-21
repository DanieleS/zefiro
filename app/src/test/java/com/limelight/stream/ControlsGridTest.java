package com.limelight.stream;

import com.limelight.stream.ControlsSurface.Nav;
import com.limelight.stream.StreamAction.Group;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertEquals;

/**
 * The Thor sheet's d-pad moves: along the tile and danger rows, down into the list and back up
 * to the tiles, and nowhere past an edge.
 */
public class ControlsGridTest {

    // The root page as StreamActions builds it: four tiles, three list rows, then the two ends.
    //   0 Keyboard  1 Gamepad  2 Mouse  3 Overlay
    //   4 Screenshot
    //   5 Bitrate
    //   6 More
    //   7 Disconnect  8 Quit game
    private static final List<Group> ROOT = Arrays.asList(
            Group.TILE, Group.TILE, Group.TILE, Group.TILE,
            Group.LIST, Group.LIST, Group.LIST,
            Group.DANGER, Group.DANGER);

    private final ControlsGrid root = ControlsGrid.of(ROOT);

    @Test
    public void leftAndRightWalkTheTileRowAndStopAtItsEnds() {
        assertEquals(1, root.neighbour(0, Nav.RIGHT));
        assertEquals(3, root.neighbour(2, Nav.RIGHT));
        assertEquals(3, root.neighbour(3, Nav.RIGHT));
        assertEquals(2, root.neighbour(3, Nav.LEFT));
        assertEquals(0, root.neighbour(0, Nav.LEFT));
    }

    @Test
    public void downFromAnyTileGoesIntoTheList() {
        for (int tile = 0; tile < 4; tile++) {
            assertEquals(4, root.neighbour(tile, Nav.DOWN));
        }
        assertEquals(5, root.neighbour(4, Nav.DOWN));
        assertEquals(6, root.neighbour(5, Nav.DOWN));
    }

    @Test
    public void upFromTheFirstListRowGoesBackToTheTiles() {
        assertEquals(0, root.neighbour(4, Nav.UP));
        assertEquals(4, root.neighbour(5, Nav.UP));
    }

    @Test
    public void theListRowsHaveNothingSideways() {
        assertEquals(5, root.neighbour(5, Nav.LEFT));
        assertEquals(5, root.neighbour(5, Nav.RIGHT));
    }

    @Test
    public void theDangerRowIsUnderTheListAndWalkedSideways() {
        assertEquals(7, root.neighbour(6, Nav.DOWN));
        assertEquals(8, root.neighbour(7, Nav.RIGHT));
        assertEquals(7, root.neighbour(8, Nav.LEFT));
        assertEquals(6, root.neighbour(7, Nav.UP));
        assertEquals(6, root.neighbour(8, Nav.UP));
        // The bottom edge: nowhere further.
        assertEquals(8, root.neighbour(8, Nav.DOWN));
    }

    @Test
    public void nothingWrapsAtTheTop() {
        assertEquals(2, root.neighbour(2, Nav.UP));
    }

    @Test
    public void withoutAListTheTilesAndTheDangerRowKeepTheirPlaceAlongTheRow() {
        ControlsGrid grid = ControlsGrid.of(Arrays.asList(
                Group.TILE, Group.TILE, Group.TILE, Group.TILE, Group.DANGER, Group.DANGER));
        assertEquals(4, grid.neighbour(0, Nav.DOWN));
        assertEquals(5, grid.neighbour(3, Nav.DOWN));
        assertEquals(0, grid.neighbour(4, Nav.UP));
        assertEquals(3, grid.neighbour(5, Nav.UP));
    }

    @Test
    public void aSubPageIsAColumnUnderItsWayBack() {
        // ‹ Bitrate, then its choices: one row each.
        ControlsGrid page = ControlsGrid.of(Arrays.asList(Group.LIST, Group.LIST, Group.LIST));
        assertEquals(0, page.first());
        assertEquals(1, page.neighbour(0, Nav.DOWN));
        assertEquals(2, page.neighbour(1, Nav.DOWN));
        assertEquals(2, page.neighbour(2, Nav.DOWN));
        assertEquals(0, page.neighbour(1, Nav.UP));
        assertEquals(1, page.neighbour(1, Nav.RIGHT));
    }

    @Test
    public void tilesAfterARowStartARowOfTheirOwn() {
        ControlsGrid grid = ControlsGrid.of(Arrays.asList(
                Group.TILE, Group.TILE, Group.LIST, Group.TILE, Group.TILE));
        assertEquals(1, grid.neighbour(0, Nav.RIGHT));
        // The first row ends at its last tile; it does not run on into the second.
        assertEquals(1, grid.neighbour(1, Nav.RIGHT));
        assertEquals(2, grid.neighbour(1, Nav.DOWN));
        assertEquals(3, grid.neighbour(2, Nav.DOWN));
        assertEquals(4, grid.neighbour(3, Nav.RIGHT));
    }

    @Test
    public void nothingSelectedYetLandsOnTheFirstEntry() {
        assertEquals(0, root.neighbour(-1, Nav.DOWN));
        assertEquals(0, root.neighbour(99, Nav.LEFT));
    }

    @Test
    public void onlyTheFourDirectionsMove() {
        assertEquals(5, root.neighbour(5, Nav.ACTIVATE));
        assertEquals(5, root.neighbour(5, Nav.BACK));
        assertEquals(5, root.neighbour(5, Nav.CLOSE));
    }

    @Test
    public void anEmptyPageHasNothingToSelect() {
        ControlsGrid empty = ControlsGrid.of(Collections.<Group>emptyList());
        assertEquals(0, empty.size());
        assertEquals(-1, empty.first());
        assertEquals(-1, empty.neighbour(-1, Nav.DOWN));
    }
}
