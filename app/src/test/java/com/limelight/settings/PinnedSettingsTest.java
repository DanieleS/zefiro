package com.limelight.settings;

import android.content.Context;

import androidx.test.core.app.ApplicationProvider;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** The pinned card's contents: essentials first, the user's pins in order, at most eight. */
@Config(sdk = {33})
@RunWith(RobolectricTestRunner.class)
public class PinnedSettingsTest {

    private PinnedSettings pinned;

    @Before
    public void fresh() {
        Context context = ApplicationProvider.getApplicationContext();
        context.getSharedPreferences(PinnedSettings.FILE, Context.MODE_PRIVATE).edit().clear().commit();
        pinned = new PinnedSettings(context);
    }

    private static Set<String> present(String... extra) {
        Set<String> keys = new HashSet<>(PinnedSettings.ESSENTIALS);
        keys.addAll(Arrays.asList(extra));
        return keys;
    }

    @Test
    public void pins_keepTheirOrderAfterTheEssentials_andSurviveARestart() {
        assertTrue(pinned.pin("frame_pacing"));
        assertTrue(pinned.pin("checkbox_enable_rumble"));
        assertTrue(pinned.pin("list_audio_config"));

        PinnedSettings reopened = new PinnedSettings(
                (Context) ApplicationProvider.getApplicationContext());
        List<String> expected = new ArrayList<>(PinnedSettings.ESSENTIALS);
        expected.addAll(Arrays.asList("frame_pacing", "checkbox_enable_rumble", "list_audio_config"));
        assertEquals(expected, reopened.shown(present("frame_pacing", "checkbox_enable_rumble",
                "list_audio_config")));
    }

    @Test
    public void atMostEightUserPins() {
        for (int i = 0; i < PinnedSettings.MAX_USER_PINS; i++) {
            assertTrue(pinned.pin("key_" + i));
        }

        assertTrue(pinned.isFull());
        assertFalse(pinned.pin("one_too_many"));
        assertEquals(PinnedSettings.MAX_USER_PINS, pinned.userPins().size());

        assertTrue(pinned.unpin("key_3"));
        assertTrue(pinned.pin("one_too_many"));
        assertEquals("one_too_many", pinned.userPins().get(PinnedSettings.MAX_USER_PINS - 1));
    }

    @Test
    public void keysThisDeviceDoesNotHave_areSkipped_butStayPinned() {
        pinned.pin("checkbox_vibrate_fallback");
        pinned.pin("frame_pacing");

        List<String> shown = pinned.shown(present("frame_pacing"));

        assertFalse(shown.contains("checkbox_vibrate_fallback"));
        assertEquals("frame_pacing", shown.get(shown.size() - 1));
        assertTrue(pinned.isPinned("checkbox_vibrate_fallback"));
    }

    @Test
    public void essentials_areNeverUserPins() {
        assertFalse(pinned.pin("list_resolution"));
        assertFalse(pinned.pin("frame_pacing") && pinned.pin("frame_pacing"));
        assertEquals(1, pinned.userPins().size());
        // An essential a device lacks (HDR before Android 7) is skipped like any other.
        Set<String> withoutHdr = present();
        withoutHdr.remove("checkbox_enable_hdr");
        assertFalse(pinned.shown(withoutHdr).contains("checkbox_enable_hdr"));
    }

    @Test
    public void storedString_roundTrips() {
        assertEquals(Arrays.asList("a", "b"), PinnedSettings.parse(" a, b ,,a"));
        assertEquals("a,b", PinnedSettings.join(Arrays.asList("a", "b")));
    }
}
