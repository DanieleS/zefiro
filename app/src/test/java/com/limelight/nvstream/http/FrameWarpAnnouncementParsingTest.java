package com.limelight.nvstream.http;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import static org.junit.Assert.assertEquals;

// /serverinfo's FrameWarpVersion: anything but a decimal integer in 1..65535 means no Frame Warp.
// Robolectric for Android's XmlPullParser.
@Config(sdk = {33})
@RunWith(RobolectricTestRunner.class)
public class FrameWarpAnnouncementParsingTest {

    private static int version(String extra) throws Exception {
        return NvHTTP.parseFrameWarpVersion("<?xml version=\"1.0\" encoding=\"utf-8\"?>\n<root status_code=\"200\">"
                + "<hostname>ATLAS</hostname><uniqueid>abc</uniqueid>" + extra + "</root>");
    }

    @Test
    public void theVersionIsRead() throws Exception {
        assertEquals(1, version("<FrameWarpVersion>1</FrameWarpVersion>"));
        assertEquals(65535, version("<FrameWarpVersion>65535</FrameWarpVersion>"));
        assertEquals(2, version("<FrameWarpVersion> 2 </FrameWarpVersion>"));
    }

    @Test
    public void aHostWithoutItOffersNothing() throws Exception {
        assertEquals(0, version(""));
        assertEquals(0, version("<FrameWarpVersion>0</FrameWarpVersion>"));
        assertEquals(0, version("<FrameWarpVersion>65536</FrameWarpVersion>"));
        assertEquals(0, version("<FrameWarpVersion>-1</FrameWarpVersion>"));
        assertEquals(0, version("<FrameWarpVersion>1.5</FrameWarpVersion>"));
        assertEquals(0, version("<FrameWarpVersion>0x1</FrameWarpVersion>"));
        assertEquals(0, version("<FrameWarpVersion></FrameWarpVersion>"));
    }
}
