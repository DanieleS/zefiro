package com.limelight.binding.video;

import static org.junit.Assert.assertEquals;

import android.app.Application;
import android.media.MediaFormat;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 29, application = Application.class)
public class Packed444Test {
    private static int[] coded(int width, int height) {
        Packed444 geometry = new Packed444(width, height, 1, 0);
        return new int[] {geometry.paddedWidth, geometry.paddedHeight * 2};
    }

    @Test
    public void codedSizeFollowsTheContract() {
        assertEquals(1920, coded(1920, 1080)[0]);
        assertEquals(2160, coded(1920, 1080)[1]);
        assertEquals(2560, coded(2560, 1440)[0]);
        assertEquals(2880, coded(2560, 1440)[1]);
        assertEquals(1280, coded(1280, 720)[0]);
        assertEquals(1440, coded(1280, 720)[1]);
        assertEquals(128, coded(66, 66)[0]);
        assertEquals(144, coded(66, 66)[1]);
        assertEquals(64, coded(1, 1)[0]);
        assertEquals(16, coded(1, 1)[1]);
        assertEquals(3840, coded(3840, 2160)[0]);
        assertEquals(4320, coded(3840, 2160)[1]);
    }

    @Test
    public void logicalSizeAndColorimetryAreKept() {
        Packed444 geometry = new Packed444(2559, 1439, 2, 1);
        assertEquals(2559, geometry.width);
        assertEquals(1439, geometry.height);
        assertEquals(2, geometry.colorSpace);
        assertEquals(1, geometry.colorRange);
    }

    // The 3.2 layout as a CPU reference: where each source sample lands in the coded picture.
    // plane 0 is Y (Wp x 2Hp), planes 1 and 2 the 4:2:0 chroma (Wp/2 x Hp).
    private static int[] place(Packed444 g, boolean v, int x, int y) {
        if ((x & 1) == 0) return new int[] {v ? 2 : 1, x / 2, y};
        return new int[] {0, (v ? g.paddedWidth / 2 : 0) + x / 2, g.paddedHeight + y};
    }

    @Test
    public void conformanceVectors() {
        Packed444 g = new Packed444(65, 9, 1, 0);
        assertEquals(128, g.paddedWidth);
        assertEquals(16, g.paddedHeight);
        assertArrayEquals3(new int[] {0, 0, 16}, place(g, false, 1, 0));
        assertArrayEquals3(new int[] {0, 65, 18}, place(g, true, 3, 2));
        assertArrayEquals3(new int[] {1, 2, 5}, place(g, false, 4, 5));
        assertArrayEquals3(new int[] {2, 32, 8}, place(g, true, 64, 8));

        Packed444 one = new Packed444(1, 1, 1, 0);
        assertEquals(64, one.paddedWidth);
        // The only source pixel has x == 0 (even), so U and V go to the chroma planes at (0, 0);
        // padded odd columns replicate it into the luma plane's lower half
        assertArrayEquals3(new int[] {1, 0, 0}, place(one, false, 0, 0));
        assertArrayEquals3(new int[] {0, 32, 8}, place(one, true, 1, 0));
    }

    private static void assertArrayEquals3(int[] expected, int[] actual) {
        org.junit.Assert.assertArrayEquals(expected, actual);
    }

    @Test
    public void packedSamplesBypassDecoderColorConversion() {
        MediaFormat format = MediaFormat.createVideoFormat("video/hevc", 2560, 2880);
        format.setInteger(MediaFormat.KEY_COLOR_STANDARD, MediaFormat.COLOR_STANDARD_BT2020);
        format.setInteger(MediaFormat.KEY_COLOR_TRANSFER, MediaFormat.COLOR_TRANSFER_ST2084);
        format.setInteger(MediaFormat.KEY_COLOR_RANGE, MediaFormat.COLOR_RANGE_LIMITED);
        Packed444.sampleDataFormat(format);
        assertEquals(MediaFormat.COLOR_STANDARD_BT709, format.getInteger(MediaFormat.KEY_COLOR_STANDARD));
        assertEquals(MediaFormat.COLOR_TRANSFER_SDR_VIDEO, format.getInteger(MediaFormat.KEY_COLOR_TRANSFER));
        assertEquals(MediaFormat.COLOR_RANGE_FULL, format.getInteger(MediaFormat.KEY_COLOR_RANGE));
    }
}
