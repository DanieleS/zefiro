package com.limelight.nvstream.http;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.util.TimeZone;

import static org.junit.Assert.assertEquals;

// The host's last_played in each dialect it can carry; the device sits in Rome, two hours east
// of UTC on the day the stamps name.
public class AppMetadataLastPlayedTest {

    // 2026-10-21T12:00:00Z
    private static final long NOON_UTC = 1792584000000L;

    private TimeZone saved;

    @Before
    public void setUp() {
        saved = TimeZone.getDefault();
        TimeZone.setDefault(TimeZone.getTimeZone("Europe/Rome"));
    }

    @After
    public void tearDown() {
        TimeZone.setDefault(saved);
    }

    @Test
    public void readsUtc() {
        assertEquals(NOON_UTC, AppMetadata.parseIso8601Millis("2026-10-21T12:00:00Z"));
        assertEquals(NOON_UTC, AppMetadata.parseIso8601Millis("2026-10-21T12:00:00.1234567Z"));
    }

    @Test
    public void appliesTheOffset() {
        // Playnite's local time, as .NET's "o" writes it: not two hours in the future.
        assertEquals(NOON_UTC, AppMetadata.parseIso8601Millis("2026-10-21T14:00:00.1234567+02:00"));
        assertEquals(NOON_UTC, AppMetadata.parseIso8601Millis("2026-10-21T14:00:00+0200"));
        assertEquals(NOON_UTC, AppMetadata.parseIso8601Millis("2026-10-21T07:00:00-05:00"));
    }

    @Test
    public void readsNoOffsetAsLocal() {
        assertEquals(NOON_UTC, AppMetadata.parseIso8601Millis("2026-10-21T14:00:00"));
    }

    @Test
    public void nothingToParse() {
        assertEquals(0, AppMetadata.parseIso8601Millis(""));
        assertEquals(0, AppMetadata.parseIso8601Millis(null));
    }
}
