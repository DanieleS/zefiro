package com.limelight.binding.video;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.limelight.nvstream.jni.MoonBridge;

import org.junit.Test;

public class Sdr10PolicyTest {
    private static Sdr10Policy.Refusal refusal(boolean enabled, boolean hdr, boolean hevc, boolean av1,
                                               boolean packed) {
        return Sdr10Policy.refusal(enabled, hdr, hevc, av1, packed);
    }

    @Test
    public void offeredWhenEnabledWithAMain10Decoder() {
        assertEquals(Sdr10Policy.Refusal.NONE, refusal(true, false, true, false, false));
        assertEquals(Sdr10Policy.Refusal.NONE, refusal(true, false, false, true, false));
    }

    @Test
    public void refusalsInOrder() {
        assertEquals(Sdr10Policy.Refusal.SETTING_OFF, refusal(false, true, true, true, true));
        assertEquals(Sdr10Policy.Refusal.HDR_STREAM, refusal(true, true, true, true, true));
        assertEquals(Sdr10Policy.Refusal.PACKED_444, refusal(true, false, true, true, true));
        assertEquals(Sdr10Policy.Refusal.NO_MAIN10, refusal(true, false, false, false, false));
    }

    @Test
    public void onlyRealSurprisesAreSaid() {
        assertFalse(Sdr10Policy.worthSaying(Sdr10Policy.Refusal.NONE));
        assertFalse(Sdr10Policy.worthSaying(Sdr10Policy.Refusal.SETTING_OFF));
        assertFalse(Sdr10Policy.worthSaying(Sdr10Policy.Refusal.HDR_STREAM));
        assertTrue(Sdr10Policy.worthSaying(Sdr10Policy.Refusal.NO_MAIN10));
        assertTrue(Sdr10Policy.worthSaying(Sdr10Policy.Refusal.PACKED_444));
    }

    @Test
    public void formatsAreTheDecodersMain10Bits() {
        assertEquals(MoonBridge.VIDEO_FORMAT_H265_MAIN10 | MoonBridge.VIDEO_FORMAT_AV1_MAIN10,
                Sdr10Policy.formats(Sdr10Policy.Refusal.NONE, true, true));
        assertEquals(MoonBridge.VIDEO_FORMAT_AV1_MAIN10, Sdr10Policy.formats(Sdr10Policy.Refusal.NONE, false, true));
        assertEquals(0, Sdr10Policy.formats(Sdr10Policy.Refusal.PACKED_444, true, true));
    }
}
