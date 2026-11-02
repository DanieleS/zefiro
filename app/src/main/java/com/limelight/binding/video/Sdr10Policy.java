package com.limelight.binding.video;

import com.limelight.nvstream.jni.MoonBridge;

/**
 * The decisions around a 10-bit SDR stream, apart from the Android objects so they can be tested
 * with made-up capabilities. There is no 10-bit SDR flag in the protocol: offering a 10-bit
 * format (without asking for HDR in the launch) is how the PC is asked to encode 10-bit BT.709.
 * It is offered only when HDR is not streamed, the setting is on, and the device decodes plain
 * Main10 in HEVC or AV1. The experimental packed 4:4:4 path has its own 10-bit handling, so it is
 * not combined with this. A host that streams PyroWave takes the 10-bit formats out again
 * (PyroWaveSupport.formatsForHost), which NvConnection says.
 */
public final class Sdr10Policy {
    private Sdr10Policy() {}

    public enum Refusal {
        NONE,
        SETTING_OFF,
        /** HDR is being streamed; it wins and this is not needed. */
        HDR_STREAM,
        /** Neither HEVC nor AV1 has a Main10 decoder on this device (or the codec setting leaves them out). */
        NO_MAIN10,
        /** The experimental packed 4:4:4 path has its own 10-bit handling. */
        PACKED_444
    }

    public static Refusal refusal(boolean enabled, boolean hdrGranted, boolean hevcMain10, boolean av1Main10,
                                  boolean packed444) {
        if (!enabled) return Refusal.SETTING_OFF;
        if (hdrGranted) return Refusal.HDR_STREAM;
        if (packed444) return Refusal.PACKED_444;
        return hevcMain10 || av1Main10 ? Refusal.NONE : Refusal.NO_MAIN10;
    }

    /** Whether the user should be told why an enabled setting is not used for this stream. */
    public static boolean worthSaying(Refusal refusal) {
        return refusal == Refusal.NO_MAIN10 || refusal == Refusal.PACKED_444;
    }

    /** The 10-bit formats to offer in addition to the 8-bit ones: 0 unless the refusal is NONE. */
    public static int formats(Refusal refusal, boolean hevcMain10, boolean av1Main10) {
        if (refusal != Refusal.NONE) return 0;
        return (hevcMain10 ? MoonBridge.VIDEO_FORMAT_H265_MAIN10 : 0) |
                (av1Main10 ? MoonBridge.VIDEO_FORMAT_AV1_MAIN10 : 0);
    }
}
