package com.limelight.companion;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

/** The stats grid is full for every count the panel shows, and never wider than four. */
public class StatsGridTest {

    @Test
    public void nineIsThreeByThree() {
        assertEquals(3, StatsGrid.columns(9));
    }

    @Test
    public void eightIsFourByTwo() {
        // Host or Device missing: the capture's empty ninth cell
        assertEquals(4, StatsGrid.columns(8));
    }

    @Test
    public void sevenAndSixFillWhatTheyCan() {
        assertEquals(3, StatsGrid.columns(6));
        assertEquals(4, StatsGrid.columns(7));
    }

    @Test
    public void aFewTilesShareOneRow() {
        assertEquals(1, StatsGrid.columns(0));
        assertEquals(2, StatsGrid.columns(2));
        assertEquals(3, StatsGrid.columns(3));
        assertEquals(4, StatsGrid.columns(4));
    }
}
