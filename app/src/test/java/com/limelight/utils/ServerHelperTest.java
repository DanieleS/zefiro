package com.limelight.utils;

import com.limelight.nvstream.http.NvApp;

import org.junit.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

/** The cached name a launch without one is titled by: by UUID first, then by id. */
public class ServerHelperTest {

    private static final List<NvApp> APPS = Arrays.asList(
            new NvApp("Remote Monitor", "AAAA-1", 101, false),
            new NvApp("Hades II", "BBBB-2", 202, true),
            new NvApp(null, "CCCC-3", 303, false));

    @Test
    public void theUuidNamesTheGame() {
        assertEquals("Hades II", ServerHelper.appNameIn(APPS, "bbbb-2", 101));
    }

    @Test
    public void theIdNamesItWithoutAUuid() {
        assertEquals("Remote Monitor", ServerHelper.appNameIn(APPS, null, 101));
        assertEquals("Remote Monitor", ServerHelper.appNameIn(APPS, "", 101));
    }

    @Test
    public void anUnknownOrNamelessAppHasNoName() {
        assertNull(ServerHelper.appNameIn(APPS, "ZZZZ", 0));
        assertNull(ServerHelper.appNameIn(APPS, null, 999));
        assertNull(ServerHelper.appNameIn(APPS, "CCCC-3", 303));
    }
}
