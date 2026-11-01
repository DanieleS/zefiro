package com.limelight.nvstream.av.video;

public abstract class VideoDecoderRenderer {
    // The packed 4:4:4 modes (bit meaning of LI_PACKED444_MODE_*) this renderer verified for a
    // logical width x height stream, or 0. Called before the connection starts, off the main thread.
    public int preparePacked444Modes(int formats, int width, int height, int fps) {
        return 0;
    }

    public abstract int setup(int format, int width, int height, int redrawRate);

    public abstract void start();

    public abstract void stop();

    // This is called once for each frame-start NALU. This means it will be called several times
    // for an IDR frame which contains several parameter sets and the I-frame data.
    public abstract int submitDecodeUnit(byte[] decodeUnitData, int decodeUnitLength, int decodeUnitType,
                                         int frameNumber, int frameType, char frameHostProcessingLatency,
                                         long receiveTimeUs, long enqueueTimeUs,
                                         long presentationTimeUs);
    
    public abstract void cleanup();

    public abstract int getCapabilities();

    public abstract void setHdrMode(boolean enabled, byte[] hdrMetadata);
}
