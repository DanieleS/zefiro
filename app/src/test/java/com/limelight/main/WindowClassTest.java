package com.limelight.main;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class WindowClassTest {

    @Test
    public void belowTheBreakpointIsCompact() {
        assertEquals(WindowClass.COMPACT, WindowClass.from(360));
        assertEquals(WindowClass.COMPACT, WindowClass.from(599));
    }

    @Test
    public void fromTheBreakpointIsWide() {
        assertEquals(WindowClass.WIDE, WindowClass.from(600));
        // A phone in landscape and the Thor's top screen.
        assertEquals(WindowClass.WIDE, WindowClass.from(780));
        assertEquals(WindowClass.WIDE, WindowClass.from(1280));
    }

    @Test
    public void anUndefinedWidthIsCompact() {
        // Configuration.SCREEN_WIDTH_DP_UNDEFINED: keep the system bars rather than hide them.
        assertEquals(WindowClass.COMPACT, WindowClass.from(0));
    }
}
