package com.limelight.nvstream.jni;

import com.limelight.nvstream.NvConnectionListener;
import com.limelight.nvstream.av.audio.AudioRenderer;
import com.limelight.nvstream.av.video.VideoDecoderRenderer;

public class MoonBridge {
    /* See documentation in Limelight.h for information about these functions and constants */

    public static final AudioConfiguration AUDIO_CONFIGURATION_STEREO = new AudioConfiguration(2, 0x3);
    public static final AudioConfiguration AUDIO_CONFIGURATION_51_SURROUND = new AudioConfiguration(6, 0x3F);
    public static final AudioConfiguration AUDIO_CONFIGURATION_71_SURROUND = new AudioConfiguration(8, 0x63F);

    public static final int VIDEO_FORMAT_H264 = 0x0001;
    public static final int VIDEO_FORMAT_H265 = 0x0100;
    public static final int VIDEO_FORMAT_H265_MAIN10 = 0x0200;
    public static final int VIDEO_FORMAT_AV1_MAIN8 = 0x1000;
    public static final int VIDEO_FORMAT_AV1_MAIN10 = 0x2000;
    // PyroWave, the intra-only GPU wavelet codec Vibepollo streams (arm64 and Vulkan 1.3 only,
    // see binding/video/PyroWaveSupport). Only the 8-bit 4:2:0 profile is ever offered.
    public static final int VIDEO_FORMAT_PYROWAVE = 0x010000;

    public static final int VIDEO_FORMAT_MASK_H264 = 0x000F;
    public static final int VIDEO_FORMAT_MASK_H265 = 0x0F00;
    public static final int VIDEO_FORMAT_MASK_AV1 = 0xF000;
    public static final int VIDEO_FORMAT_MASK_PYROWAVE = 0x0F0000;
    public static final int VIDEO_FORMAT_MASK_10BIT = 0x2200;

    public static final int BUFFER_TYPE_PICDATA = 0;
    public static final int BUFFER_TYPE_SPS = 1;
    public static final int BUFFER_TYPE_PPS = 2;
    public static final int BUFFER_TYPE_VPS = 3;

    public static final int FRAME_TYPE_PFRAME = 0;
    public static final int FRAME_TYPE_IDR = 1;

    public static final int COLORSPACE_REC_601 = 0;
    public static final int COLORSPACE_REC_709 = 1;
    public static final int COLORSPACE_REC_2020 = 2;

    public static final int COLOR_RANGE_LIMITED = 0;
    public static final int COLOR_RANGE_FULL = 1;

    // Dynamic video FEC modes, mirroring LI_FEC_* in Limelight.h
    // Client roaming flags for startConnection() (LI_ROAMING_*) and the status getClientRoamingStatus() returns
    public static final int ROAMING_ENABLED = 0x01;
    public static final int ROAMING_ALLOW_METERED = 0x02;
    public static final int ROAMING_IDLE = 0;
    public static final int ROAMING_MOVING = 1;
    public static final int ROAMING_HELD = 2;

    public static final int FEC_DISABLED = 0;
    public static final int FEC_HOST_DEFAULT = 1;
    public static final int FEC_AUTOMATIC = 2;
    public static final int FEC_FIXED = 3;

    public static final int CAPABILITY_DIRECT_SUBMIT = 1;
    public static final int CAPABILITY_REFERENCE_FRAME_INVALIDATION_AVC = 2;
    public static final int CAPABILITY_REFERENCE_FRAME_INVALIDATION_HEVC = 4;
    public static final int CAPABILITY_REFERENCE_FRAME_INVALIDATION_AV1 = 0x40;
    public static final int CAPABILITY_INTRA_REFRESH = 0x80;

    public static final int DR_OK = 0;
    public static final int DR_NEED_IDR = -1;

    public static final int CONN_STATUS_OKAY = 0;
    public static final int CONN_STATUS_POOR = 1;

    public static final int ML_ERROR_GRACEFUL_TERMINATION = 0;
    public static final int ML_ERROR_NO_VIDEO_TRAFFIC = -100;
    public static final int ML_ERROR_NO_VIDEO_FRAME = -101;
    public static final int ML_ERROR_UNEXPECTED_EARLY_TERMINATION = -102;
    public static final int ML_ERROR_PROTECTED_CONTENT = -103;
    public static final int ML_ERROR_FRAME_CONVERSION = -104;
    // This device's own code, never sent by a PC: the video decoder failed and recovering it did not help
    public static final int CLIENT_ERROR_DECODER = -107;

    public static final int ML_PORT_INDEX_TCP_47984 = 0;
    public static final int ML_PORT_INDEX_TCP_47989 = 1;
    public static final int ML_PORT_INDEX_TCP_48010 = 2;
    public static final int ML_PORT_INDEX_UDP_47998 = 8;
    public static final int ML_PORT_INDEX_UDP_47999 = 9;
    public static final int ML_PORT_INDEX_UDP_48000 = 10;
    public static final int ML_PORT_INDEX_UDP_48010 = 11;

    public static final int ML_PORT_FLAG_ALL = 0xFFFFFFFF;
    public static final int ML_PORT_FLAG_TCP_47984 = 0x0001;
    public static final int ML_PORT_FLAG_TCP_47989 = 0x0002;
    public static final int ML_PORT_FLAG_TCP_48010 = 0x0004;
    public static final int ML_PORT_FLAG_UDP_47998 = 0x0100;
    public static final int ML_PORT_FLAG_UDP_47999 = 0x0200;
    public static final int ML_PORT_FLAG_UDP_48000 = 0x0400;
    public static final int ML_PORT_FLAG_UDP_48010 = 0x0800;

    public static final int ML_TEST_RESULT_INCONCLUSIVE = 0xFFFFFFFF;

    public static final byte SS_KBE_FLAG_NON_NORMALIZED = 0x01;

    public static final int LI_ERR_UNSUPPORTED = -5501;

    // Host feature flags (see getHostFeatureFlags()). Capture actions are a Vibepollo/Scirocco
    // protocol extension: the host carries them out with the capture tool configured on it.
    public static final int LI_FF_CAPTURE_ACTIONS   = 0x20000;
    public static final int LI_FF_CAPTURE_AVAILABLE = 0x40000;
    // The host takes a client's request to change the stream resolution without ending the stream
    // (requestResolutionChange()). A Foundation Sunshine extension.
    public static final int LI_FF_CLIENT_RESOLUTION_CHANGE = 0x400;

    // Actions for sendCaptureAction()
    public static final int LI_CAPTURE_SCREENSHOT       = 0x01;
    public static final int LI_CAPTURE_SAVE_REPLAY      = 0x02;
    public static final int LI_CAPTURE_TOGGLE_RECORDING = 0x03;

    public static final byte LI_TOUCH_EVENT_HOVER       = 0x00;
    public static final byte LI_TOUCH_EVENT_DOWN        = 0x01;
    public static final byte LI_TOUCH_EVENT_UP          = 0x02;
    public static final byte LI_TOUCH_EVENT_MOVE        = 0x03;
    public static final byte LI_TOUCH_EVENT_CANCEL      = 0x04;
    public static final byte LI_TOUCH_EVENT_BUTTON_ONLY = 0x05;
    public static final byte LI_TOUCH_EVENT_HOVER_LEAVE = 0x06;
    public static final byte LI_TOUCH_EVENT_CANCEL_ALL  = 0x07;

    public static final byte LI_TOOL_TYPE_UNKNOWN = 0x00;
    public static final byte LI_TOOL_TYPE_PEN = 0x01;
    public static final byte LI_TOOL_TYPE_ERASER = 0x02;

    public static final byte LI_PEN_BUTTON_PRIMARY = 0x01;
    public static final byte LI_PEN_BUTTON_SECONDARY = 0x02;
    public static final byte LI_PEN_BUTTON_TERTIARY = 0x04;

    public static final byte LI_TILT_UNKNOWN = (byte)0xFF;
    public static final short LI_ROT_UNKNOWN = (short)0xFFFF;

    public static final byte LI_CTYPE_UNKNOWN  = 0x00;
    public static final byte LI_CTYPE_XBOX     = 0x01;
    public static final byte LI_CTYPE_PS       = 0x02;
    public static final byte LI_CTYPE_NINTENDO = 0x03;
    public static final byte LI_CTYPE_STEAM    = 0x04;

    public static final short LI_CCAP_ANALOG_TRIGGERS = 0x01;
    public static final short LI_CCAP_RUMBLE          = 0x02;
    public static final short LI_CCAP_TRIGGER_RUMBLE  = 0x04;
    public static final short LI_CCAP_TOUCHPAD        = 0x08;
    public static final short LI_CCAP_ACCEL           = 0x10;
    public static final short LI_CCAP_GYRO            = 0x20;
    public static final short LI_CCAP_BATTERY_STATE   = 0x40;
    public static final short LI_CCAP_RGB_LED         = 0x80;
    public static final short LI_CCAP_DUAL_TOUCHPAD   = 0x100;

    public static final byte LI_MOTION_TYPE_ACCEL = 0x01;
    public static final byte LI_MOTION_TYPE_GYRO  = 0x02;

    public static final byte LI_BATTERY_STATE_UNKNOWN      = 0x00;
    public static final byte LI_BATTERY_STATE_NOT_PRESENT  = 0x01;
    public static final byte LI_BATTERY_STATE_DISCHARGING  = 0x02;
    public static final byte LI_BATTERY_STATE_CHARGING     = 0x03;
    public static final byte LI_BATTERY_STATE_NOT_CHARGING = 0x04; // Connected to power but not charging
    public static final byte LI_BATTERY_STATE_FULL         = 0x05;

    public static final byte LI_BATTERY_PERCENTAGE_UNKNOWN = (byte)0xFF;

    private static AudioRenderer audioRenderer;
    private static VideoDecoderRenderer videoRenderer;
    private static NvConnectionListener connectionListener;

    static {
        System.loadLibrary("moonlight-core");
        init();
    }

    public static int CAPABILITY_SLICES_PER_FRAME(byte slices) {
        return slices << 24;
    }

    public static class AudioConfiguration {
        public final int channelCount;
        public final int channelMask;

        public AudioConfiguration(int channelCount, int channelMask) {
            this.channelCount = channelCount;
            this.channelMask = channelMask;
        }

        // Creates an AudioConfiguration from the integer value returned by moonlight-common-c
        // See CHANNEL_COUNT_FROM_AUDIO_CONFIGURATION() and CHANNEL_MASK_FROM_AUDIO_CONFIGURATION()
        // in Limelight.h
        private AudioConfiguration(int audioConfiguration) {
            // Check the magic byte before decoding to make sure we got something that's actually
            // a MAKE_AUDIO_CONFIGURATION()-based value and not something else like an older version
            // hardcoded AUDIO_CONFIGURATION value from an earlier version of moonlight-common-c.
            if ((audioConfiguration & 0xFF) != 0xCA) {
                throw new IllegalArgumentException("Audio configuration has invalid magic byte!");
            }

            this.channelCount = (audioConfiguration >> 8) & 0xFF;
            this.channelMask = (audioConfiguration >> 16) & 0xFFFF;
        }

        // See SURROUNDAUDIOINFO_FROM_AUDIO_CONFIGURATION() in Limelight.h
        public int getSurroundAudioInfo() {
            return channelMask << 16 | channelCount;
        }

        @Override
        public boolean equals(Object obj) {
            if (obj instanceof AudioConfiguration) {
                AudioConfiguration that = (AudioConfiguration)obj;
                return this.toInt() == that.toInt();
            }

            return false;
        }

        @Override
        public int hashCode() {
            return toInt();
        }

        // Returns the integer value expected by moonlight-common-c
        // See MAKE_AUDIO_CONFIGURATION() in Limelight.h
        public int toInt() {
            return ((channelMask) << 16) | (channelCount << 8) | 0xCA;
        }
    }

    public static int bridgeDrSetup(int videoFormat, int width, int height, int redrawRate) {
        if (videoRenderer != null) {
            return videoRenderer.setup(videoFormat, width, height, redrawRate);
        }
        else {
            return -1;
        }
    }

    public static void bridgeDrStart() {
        if (videoRenderer != null) {
            videoRenderer.start();
        }
    }

    public static void bridgeDrStop() {
        if (videoRenderer != null) {
            videoRenderer.stop();
        }
    }

    public static void bridgeDrCleanup() {
        if (videoRenderer != null) {
            videoRenderer.cleanup();
        }
    }

    //todo 不显示画面
    public static int bridgeDrSubmitDecodeUnit(byte[] decodeUnitData, int decodeUnitLength, int decodeUnitType,
                                               int frameNumber, int frameType, char frameHostProcessingLatency,
                                               long receiveTimeUs, long enqueueTimeUs,
                                               long presentationTimeUs) {
        if (videoRenderer != null) {
            return videoRenderer.submitDecodeUnit(decodeUnitData, decodeUnitLength,
                    decodeUnitType, frameNumber, frameType, frameHostProcessingLatency, receiveTimeUs, enqueueTimeUs,
                    presentationTimeUs);
        }
        else {
            return DR_OK;
        }
    }

    public static int bridgeArInit(int audioConfiguration, int sampleRate, int samplesPerFrame) {
        if (audioRenderer != null) {
            return audioRenderer.setup(new AudioConfiguration(audioConfiguration), sampleRate, samplesPerFrame);
        }
        else {
            return -1;
        }
    }

    public static void bridgeArStart() {
        if (audioRenderer != null) {
            audioRenderer.start();
        }
    }

    public static void bridgeArStop() {
        if (audioRenderer != null) {
            audioRenderer.stop();
        }
    }

    public static void bridgeArCleanup() {
        if (audioRenderer != null) {
            audioRenderer.cleanup();
        }
    }

    //静音 todo
    public static void bridgeArPlaySample(short[] pcmData) {
        if (audioRenderer != null) {
            audioRenderer.playDecodedAudio(pcmData);
        }
    }

    public static void bridgeClStageStarting(int stage) {
        if (connectionListener != null) {
            connectionListener.stageStarting(getStageName(stage));
        }
    }

    public static void bridgeClStageComplete(int stage) {
        if (connectionListener != null) {
            connectionListener.stageComplete(getStageName(stage));
        }
    }

    public static void bridgeClStageFailed(int stage, int errorCode) {
        if (connectionListener != null) {
            connectionListener.stageFailed(getStageName(stage), getPortFlagsFromStage(stage), errorCode);
        }
    }

    public static void bridgeClConnectionStarted() {
        if (connectionListener != null) {
            connectionListener.connectionStarted();
        }
    }

    public static void bridgeClConnectionTerminated(int errorCode) {
        if (connectionListener != null) {
            connectionListener.connectionTerminated(errorCode);
        }
    }

    public static void bridgeClRumble(short controllerNumber, short lowFreqMotor, short highFreqMotor) {
        if (connectionListener != null) {
            connectionListener.rumble(controllerNumber, lowFreqMotor, highFreqMotor);
        }
    }

    public static void bridgeClConnectionStatusUpdate(int connectionStatus) {
        if (connectionListener != null) {
            connectionListener.connectionStatusUpdate(connectionStatus);
        }
    }

    public static void bridgeClSetHdrMode(boolean enabled, byte[] hdrMetadata) {
        if (connectionListener != null) {
            connectionListener.setHdrMode(enabled, hdrMetadata);
        }
    }

    public static void bridgeClResolutionChanged(int width, int height) {
        if (connectionListener != null) {
            connectionListener.resolutionChanged(width, height);
        }
    }

    public static void bridgeClRumbleTriggers(short controllerNumber, short leftTrigger, short rightTrigger) {
        if (connectionListener != null) {
            connectionListener.rumbleTriggers(controllerNumber, leftTrigger, rightTrigger);
        }
    }

    public static void bridgeClSetMotionEventState(short controllerNumber, byte eventType, short sampleRateHz) {
        if (connectionListener != null) {
            connectionListener.setMotionEventState(controllerNumber, eventType, sampleRateHz);
        }
    }

    public static void bridgeClSetControllerLED(short controllerNumber, byte r, byte g, byte b) {
        if (connectionListener != null) {
            connectionListener.setControllerLED(controllerNumber, r, g, b);
        }
    }

    public static void bridgeClSetTextFocus(byte focusType) {
        if (connectionListener != null) {
            connectionListener.setTextFocus(focusType);
        }
    }

    public static void setupBridge(VideoDecoderRenderer videoRenderer, AudioRenderer audioRenderer, NvConnectionListener connectionListener) {
        MoonBridge.videoRenderer = videoRenderer;
        MoonBridge.audioRenderer = audioRenderer;
        MoonBridge.connectionListener = connectionListener;
    }

    public static void cleanupBridge() {
        MoonBridge.videoRenderer = null;
        MoonBridge.audioRenderer = null;
        MoonBridge.connectionListener = null;
    }

    public static native int startConnection(String address, String appVersion, String gfeVersion,
                                              String rtspSessionUrl, int serverCodecModeSupport,
                                              int width, int height, int fps,
                                              int bitrate, int pyroWaveFallbackBitrate,
                                              int packetSize, int streamingRemotely,
                                              int audioConfiguration, int supportedVideoFormats,
                                              int clientRefreshRateX100,
                                              byte[] riAesKey, byte[] riAesIv,
                                              int videoCapabilities,
                                              int colorSpace, int colorRange,
                                              int controlPeerTimeoutMs,
                                              int clientRoaming,
                                              boolean enableTextFocusHints,
                                              int videoFecMode, int videoFecPercentage,
                                              int packed444Modes,
                                              int frameWarp, int frameWarpController);

    public static native void stopConnection();

    public static native void interruptConnection();

    public static native void sendExecServerCmd(int cmdId);

    // Asks the host to carry out a capture action (LI_CAPTURE_*). Returns LI_ERR_UNSUPPORTED
    // unless the host advertised LI_FF_CAPTURE_ACTIONS, 0 on success and -1 if it wasn't sent.
    public static native int sendCaptureAction(int action);

    // Asks the host to change the stream resolution without ending it. 0 means sent, not applied:
    // only a frame of the new size (see resolutionChanged()) shows it was. -1 invalid size, -2
    // unsupported (the host did not advertise LI_FF_CLIENT_RESOLUTION_CHANGE), -3 disconnected,
    // -4 not sent.
    public static native int requestResolutionChange(int width, int height);

    public static native void sendEmptyPayload();

    public static native void sendMouseMove(short deltaX, short deltaY);

    public static native void sendMousePosition(short x, short y, short referenceWidth, short referenceHeight);

    public static native void sendMouseMoveAsMousePosition(short deltaX, short deltaY, short referenceWidth, short referenceHeight);

    public static native void sendMouseButton(byte buttonEvent, byte mouseButton);

    public static native void sendMultiControllerInput(short controllerNumber,
                                    short activeGamepadMask, int buttonFlags,
                                    byte leftTrigger, byte rightTrigger,
                                    short leftStickX, short leftStickY,
                                    short rightStickX, short rightStickY);

    public static native int sendTouchEvent(byte eventType, int pointerId, float x, float y, float pressure,
                                            float contactAreaMajor, float contactAreaMinor, short rotation);

    public static native int sendPenEvent(byte eventType, byte toolType, byte penButtons, float x, float y,
                                          float pressure, float contactAreaMajor, float contactAreaMinor,
                                          short rotation, byte tilt);

    public static native int sendControllerArrivalEvent(byte controllerNumber, short activeGamepadMask, byte type, int supportedButtonFlags, short capabilities);

    public static native int sendControllerTouchEvent(byte controllerNumber, byte eventType, int pointerId, float x, float y, float pressure);

    public static native int sendControllerMotionEvent(byte controllerNumber, byte motionType, float x, float y, float z);

    public static native int sendControllerBatteryEvent(byte controllerNumber, byte batteryState, byte batteryPercentage);

    public static native void sendKeyboardInput(short keyMap, byte keyDirection, byte modifier, byte flags);

    public static native void sendMouseHighResScroll(short scrollAmount);

    public static native void sendMouseHighResHScroll(short scrollAmount);

    public static native void sendUtf8Text(String text);

    public static native String getStageName(int stage);

    public static native String findExternalAddressIP4(String stunHostName, int stunPort);

    public static native int getPendingAudioDuration();

    /** The Opus concealer for the AAudio callback, a C function pointer (aaudio_output.c). */
    public static native long audioConcealer();

    public static native int getPendingVideoFrames();

    public static native int testClientConnectivity(String testServerHostName, int referencePort, int testFlags);

    public static native int getPortFlagsFromStage(int stage);

    public static native int getPortFlagsFromTerminationErrorCode(int errorCode);

    public static native String stringifyPortFlags(int portFlags, String separator);

    // The RTT is in the top 32 bits, and the RTT variance is in the bottom 32 bits
    public static native long getEstimatedRttInfo();

    // Milliseconds since anything last arrived from the host on the control stream, or -1 when
    // the control stream is not connected
    public static native int getControlStreamSilenceMs();

    // Whether this stream negotiated dynamic FEC (see LiIsDynamicFecNegotiated()). Valid once the
    // connection has started.
    public static native boolean isDynamicFecNegotiated();

    // Whether the host advertised client roaming and it is enabled (see LiIsClientRoamingNegotiated()).
    // Valid once the connection has started.
    public static native boolean isClientRoamingNegotiated();

    // The default network changed; metered is whether the new one is metered. No-op unless roaming
    // was negotiated.
    public static native void notifyNetworkChanged(boolean metered);

    // ROAMING_IDLE, ROAMING_MOVING or ROAMING_HELD
    public static native int getClientRoamingStatus();

    // Successful moves in this connection
    public static native int getClientRoamingCount();

    // 1 when the stream negotiated packed 4:4:4 (chroma packing v1), else 0 (see
    // LiGetNegotiatedChromaPacking()). Valid once the RTSP handshake is done, so from setup().
    public static native int getNegotiatedChromaPacking();

    // The host's extended feature flags (see LiGetHostFeatureFlags()). Valid once the
    // connection has started.
    public static native int getHostFeatureFlags();

    public static native String getLaunchUrlQueryParameters();

    public static native byte guessControllerType(int vendorId, int productId);

    public static native boolean guessControllerHasPaddles(int vendorId, int productId);

    public static native boolean guessControllerHasShareButton(int vendorId, int productId);

    public static native void init();

    // PyroWave renderer (jni/pyrowave-renderer). Decode units reach it straight from native
    // code; Java only hands it the Surface. pyroWaveIsSupported() creates a Vulkan device and a
    // decoder the first time (the result is cached), and is always false outside arm64-v8a.
    public static native boolean pyroWaveIsSupported();
    public static native int pyroWaveStart(Object surface, int videoFormat, int width, int height,
                                           int colorspace, int colorRange);
    public static native void pyroWaveStop();

    // Indexes into the array pyroWaveTakeStats() fills
    public static final int PYROWAVE_STATS_RECEIVED = 0;
    public static final int PYROWAVE_STATS_PRESENTED = 1;
    public static final int PYROWAVE_STATS_DROPPED = 2;
    public static final int PYROWAVE_STATS_PARTIAL = 3;
    public static final int PYROWAVE_STATS_LOST = 4;
    public static final int PYROWAVE_STATS_HOST_LATENCY_FRAMES = 5;
    public static final int PYROWAVE_STATS_HOST_LATENCY_TOTAL = 6; // tenths of a ms
    public static final int PYROWAVE_STATS_HOST_LATENCY_MIN = 7;
    public static final int PYROWAVE_STATS_HOST_LATENCY_MAX = 8;
    public static final int PYROWAVE_STATS_GPU_DECODE_MS = 9; // negative before the first sample
    public static final int PYROWAVE_STATS_COUNT = 10;

    // The running PyroWave renderer's stats since the previous call, into out
    // (PYROWAVE_STATS_COUNT long). Returns the GPU's name, or null without a renderer.
    public static native String pyroWaveTakeStats(double[] out);

    // Draws Frame Warp on the running PyroWave renderer from its next picture on. False when the
    // device or the build cannot; the stream then goes without.
    public static native boolean pyroWaveSetFrameWarp();

    // Frame Warp (see Limelight.h): the setting on and the host listing FrameWarpVersion in its
    // serverinfo are what startConnection() asks the library for; the library only sends the request
    // once the host's RTSP DESCRIBE lists its ids too (isFrameWarpNegotiated()).
    private static volatile boolean frameWarpWanted;
    private static volatile boolean frameWarpListed;
    private static volatile boolean frameWarpDeclined;

    /** frameWarpStatus(): no Frame Warp line (the setting is off). */
    public static final int FRAME_WARP_OFF = 0;
    /** frameWarpStatus(): the setting is on, the host does not offer it. */
    public static final int FRAME_WARP_NOT_OFFERED = 1;
    /** frameWarpStatus(): offered; the host has not learned this game's camera yet. */
    public static final int FRAME_WARP_LEARNING = 2;
    /** frameWarpStatus(): locked; frameWarpStatusNumbers' lock bits say to what. */
    public static final int FRAME_WARP_LOCKED = 3;
    /** frameWarpStatus(): asked for, but this stream's renderer does not draw it. */
    public static final int FRAME_WARP_OFF_FOR_STREAM = 4;

    /** frameWarpShift(): nothing to draw, the picture is shown as it is. */
    public static final int FRAME_WARP_INACTIVE = -2;
    /** frameWarpMask(): the mask the caller holds is current. */
    public static final int FRAME_WARP_MASK_UNCHANGED = -1;
    /** frameWarpMask(): there is no mask any more. */
    public static final int FRAME_WARP_MASK_CLEARED = -2;
    /** frameWarpMask(): the cells array is too small; size holds what it needs. */
    public static final int FRAME_WARP_MASK_TOO_SMALL = -3;

    /** The floats frameWarpShift() writes: shift x, y (picture px), focal, picture width, height, fade, tag status. */
    public static final int FRAME_WARP_FLOATS = 7;
    /** The floats frameWarpStatusNumbers() writes (see the native function). */
    public static final int FRAME_WARP_STATUS_FLOATS = 8;
    public static final int FRAME_WARP_STATUS_LAG_MS = 0;
    public static final int FRAME_WARP_STATUS_FIT = 1;
    public static final int FRAME_WARP_STATUS_SHIFTED = 2;
    public static final int FRAME_WARP_STATUS_EXTRAPOLATED = 3;
    public static final int FRAME_WARP_STATUS_UNTAGGED = 4;
    public static final int FRAME_WARP_STATUS_SHIFT_X = 5;
    public static final int FRAME_WARP_STATUS_SHIFT_Y = 6;
    public static final int FRAME_WARP_STATUS_LOCKS = 7;
    public static final int FRAME_WARP_LOCK_MOUSE = 1;
    public static final int FRAME_WARP_LOCK_STICK = 2;

    /** The controller number Frame Warp tracks when no gamepad is known (mouse only), LI_FRAME_WARP_NO_CONTROLLER. */
    public static final int FRAME_WARP_NO_CONTROLLER = 0xFF;

    /**
     * Call before startConnection(): {@code wanted} is the setting, {@code listed} whether the host's serverinfo has
     * FrameWarpVersion. Returns what startConnection() takes as its frameWarp argument.
     */
    public static int requestFrameWarp(boolean wanted, boolean listed) {
        frameWarpWanted = wanted;
        frameWarpListed = wanted && listed;
        frameWarpDeclined = false;
        return frameWarpListed ? 1 : 0;
    }

    /** Whether the running stream asked the library for Frame Warp and the host's DESCRIBE listed it. */
    public static boolean isFrameWarpStream() {
        return frameWarpListed && !frameWarpDeclined && isFrameWarpNegotiated();
    }

    /**
     * A renderer cannot draw Frame Warp for this stream: the host is told to stop its work for it (once the stream
     * runs, if the request has not been sent yet) and the overlay says so.
     */
    public static void declineFrameWarp() {
        if (!frameWarpListed || frameWarpDeclined) return;
        frameWarpDeclined = true;
        stopFrameWarpIfRunning();
    }

    /** The stream just started: a decline made while the renderers were set up, before the request went out, applies now. */
    public static void frameWarpStreamStarted() {
        if (frameWarpDeclined) stopFrameWarpIfRunning();
    }

    private static void stopFrameWarpIfRunning() {
        // 0 when it was not running; the request goes out once the control stream is up, hence the second look
        if (isFrameWarpNegotiated()) setFrameWarpEnabled(false);
    }

    /** The controller whose right stick turns the camera changed (or became known); mouse only for NO_CONTROLLER. */
    public static void trackFrameWarpController(int controller) {
        if (frameWarpListed && !frameWarpDeclined) setFrameWarpController(controller);
    }

    /**
     * The overlay's Frame Warp state, a FRAME_WARP_* code, with the numbers behind it in {@code out}
     * (FRAME_WARP_STATUS_FLOATS) once the host has offered it.
     */
    public static int frameWarpStatusNumbers(float[] out) {
        if (!frameWarpWanted) return FRAME_WARP_OFF;
        if (!frameWarpListed) return FRAME_WARP_NOT_OFFERED;
        if (frameWarpDeclined) return FRAME_WARP_OFF_FOR_STREAM;
        switch (frameWarpStatus(out)) {
            case 1: return FRAME_WARP_LEARNING;
            case 2: return FRAME_WARP_LOCKED;
            default: return FRAME_WARP_NOT_OFFERED;
        }
    }

    public static native boolean isFrameWarpNegotiated();

    public static native int setFrameWarpEnabled(boolean enabled);

    public static native int setFrameWarpController(int controller);

    /**
     * The shift of the picture with frame number {@code frame}: {@code out} (FRAME_WARP_FLOATS) gets it in picture
     * pixels (x right, y down), the lens focal length (0 without), the picture size the shift is in, the fade and the
     * tag status. Returns the generation of the model used, or FRAME_WARP_INACTIVE.
     */
    public static native int frameWarpShift(long frame, float[] out);

    /**
     * The HUD mask when its generation is not {@code known} (-1 for none): {@code size} gets width and height and
     * {@code cells}, when it has room, one byte per cell (0 or 255, rows from the top). Returns the generation or a
     * FRAME_WARP_MASK_* code.
     */
    public static native int frameWarpMask(int known, int[] size, byte[] cells);

    /** 0 when not negotiated, 1 while learning, 2 once locked; the numbers in {@code out} (FRAME_WARP_STATUS_FLOATS). */
    public static native int frameWarpStatus(float[] out);
}
