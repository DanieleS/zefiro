package com.limelight.settings;

import android.content.res.XmlResourceParser;

import androidx.test.core.app.ApplicationProvider;

import com.limelight.R;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.xmlpull.v1.XmlPullParser;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

/**
 * The catalogue places every setting the XML declares, once, and nothing else: a key added to
 * {@code preferences.xml} (or deleted from it) without the catalogue following fails here.
 */
@Config(sdk = {33})
@RunWith(RobolectricTestRunner.class)
public class SettingsCatalogTest {

    private static final String ANDROID_NS = "http://schemas.android.com/apk/res/android";

    private static List<String> xmlKeys() throws Exception {
        List<String> keys = new ArrayList<>();
        try (XmlResourceParser parser = ApplicationProvider.getApplicationContext()
                .getResources().getXml(R.xml.preferences)) {
            for (int event = parser.getEventType(); event != XmlPullParser.END_DOCUMENT;
                 event = parser.next()) {
                if (event != XmlPullParser.START_TAG) {
                    continue;
                }
                String tag = parser.getName();
                if (tag.endsWith("PreferenceCategory") || tag.endsWith("PreferenceScreen")) {
                    continue;
                }
                String key = parser.getAttributeValue(ANDROID_NS, "key");
                if (key != null) {
                    keys.add(key);
                }
            }
        }
        return keys;
    }

    @Test
    public void catalogueHoldsExactlyTheXmlKeys() throws Exception {
        Set<String> xml = new TreeSet<>(xmlKeys());
        Set<String> catalogue = new TreeSet<>(SettingsCatalog.get().keys());

        Set<String> missing = new TreeSet<>(xml);
        missing.removeAll(catalogue);
        Set<String> extra = new TreeSet<>(catalogue);
        extra.removeAll(xml);
        assertTrue("Keys in preferences.xml with no place in the catalogue: " + missing,
                missing.isEmpty());
        assertTrue("Keys in the catalogue that preferences.xml does not have: " + extra,
                extra.isEmpty());
        // 144 until the stream side panel dropped the floating button, the companion stats
        // switch and the three perf overlay variants.
        // 141 with the host-clock pacing switch and its profile.
        assertEquals(141, catalogue.size());
    }

    @Test
    public void noKeyIsPlacedTwice() {
        List<String> keys = SettingsCatalog.get().keys();
        Set<String> seen = new HashSet<>();
        for (String key : keys) {
            assertTrue("Placed twice: " + key, seen.add(key));
        }
    }

    @Test
    public void everySubgroupHasKeys() {
        for (SettingsCatalog.Section section : SettingsCatalog.get().sections()) {
            assertFalse(section.id + " has no subgroups", section.subgroups.isEmpty());
            for (SettingsCatalog.Subgroup subgroup : section.subgroups) {
                assertFalse("An empty subgroup in " + section.id, subgroup.keys.isEmpty());
            }
        }
    }

    @Test
    public void lookupsAnswerFromTheTable() {
        SettingsCatalog catalog = SettingsCatalog.get();
        assertEquals("video", catalog.sectionOf("list_resolution").id);
        assertEquals(0, catalog.positionInSection("list_resolution"));
        assertEquals(R.string.settings_group_rumble,
                catalog.subgroupOf("checkbox_enable_rumble").title);
        // Moved out of its XML category, the advanced one, into Connection.
        assertEquals("connection", catalog.sectionOf("checkbox_auto_reconnect").id);
        assertEquals(-1, catalog.positionInSection("no_such_key"));
        assertEquals(null, catalog.sectionOf("no_such_key"));
        assertNotEquals(catalog.positionInSection("list_fps"),
                catalog.positionInSection("list_resolution"));
    }
}
