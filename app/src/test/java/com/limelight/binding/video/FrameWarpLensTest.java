package com.limelight.binding.video;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class FrameWarpLensTest {
    @Test
    public void aFlatPictureMovesEveryPixelByTheShift() {
        float[] out = new float[FrameWarpLens.TERMS];
        FrameWarpLens.terms(19.2f, -10.8f, 1920f, 1080f, 0f, out);
        assertArrayEquals(new float[] {0.01f, -0.01f, 0f, 0f, 1f, 1f}, out, 1e-6f);
    }

    @Test
    public void aLongFocalLengthApproachesTheFlatPicture() {
        float[] flat = new float[FrameWarpLens.TERMS];
        float[] lens = new float[FrameWarpLens.TERMS];
        FrameWarpLens.terms(30f, 12f, 1920f, 1080f, 0f, flat);
        FrameWarpLens.terms(30f, 12f, 1920f, 1080f, 1_000_000f, lens);
        assertArrayEquals(flat, lens, 1e-4f);
    }

    @Test
    public void theLensTermsFollowTheContractFormulas() {
        float[] out = new float[FrameWarpLens.TERMS];
        float focal = 1000f;
        FrameWarpLens.terms(100f, 0f, 1920f, 1080f, focal, out);
        double t = Math.tan(0.1);
        assertEquals(focal * t / 1920.0, out[0], 1e-6);
        assertEquals(0.0, out[1], 1e-9);
        assertEquals(t * 1920.0 / focal, out[2], 1e-6);
        assertEquals(0.0, out[3], 1e-9);
        assertEquals(1.0, out[4], 1e-6);
        assertEquals(Math.sqrt(1 + t * t), out[5], 1e-6);
    }
}
