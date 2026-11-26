#include <Limelight.h>

#include <jni.h>
#include <android/log.h>

#include <arpa/inet.h>
#include <stdlib.h>
#include <string.h>

#include "minisdl.h"
#include "controller_type.h"
#include "controller_list.h"

#ifdef HAVE_PYROWAVE
#include <android/native_window_jni.h>
#include "pyrowave_renderer.h"
#endif

JNIEXPORT void JNICALL
Java_com_limelight_nvstream_jni_MoonBridge_sendMouseMove(JNIEnv *env, jclass clazz, jshort deltaX, jshort deltaY) {
    LiSendMouseMoveEvent(deltaX, deltaY);
}

JNIEXPORT void JNICALL
Java_com_limelight_nvstream_jni_MoonBridge_sendExecServerCmd(JNIEnv *env, jclass clazz,
                                                             jint cmdId) {
    LiSendExecServerCmd(cmdId);
}

JNIEXPORT jint JNICALL
Java_com_limelight_nvstream_jni_MoonBridge_sendCaptureAction(JNIEnv *env, jclass clazz,
                                                             jint action) {
    return LiSendCaptureAction((uint8_t) action);
}

JNIEXPORT jint JNICALL
Java_com_limelight_nvstream_jni_MoonBridge_requestResolutionChange(JNIEnv *env, jclass clazz,
                                                                   jint width, jint height) {
    return LiRequestResolutionChange(width, height);
}

JNIEXPORT void JNICALL
Java_com_limelight_nvstream_jni_MoonBridge_sendEmptyPayload(JNIEnv *env, jclass clazz) {
    LiSendEmptyPayload();
}

JNIEXPORT void JNICALL
Java_com_limelight_nvstream_jni_MoonBridge_sendMousePosition(JNIEnv *env, jclass clazz,
                                                             jshort x, jshort y, jshort referenceWidth, jshort referenceHeight) {
    LiSendMousePositionEvent(x, y, referenceWidth, referenceHeight);
}

JNIEXPORT void JNICALL
Java_com_limelight_nvstream_jni_MoonBridge_sendMouseMoveAsMousePosition(JNIEnv *env, jclass clazz,
        jshort deltaX, jshort deltaY, jshort referenceWidth, jshort referenceHeight) {
    LiSendMouseMoveAsMousePositionEvent(deltaX, deltaY, referenceWidth, referenceHeight);
}

JNIEXPORT void JNICALL
Java_com_limelight_nvstream_jni_MoonBridge_sendMouseButton(JNIEnv *env, jclass clazz, jbyte buttonEvent, jbyte mouseButton) {
    LiSendMouseButtonEvent(buttonEvent, mouseButton);
}

JNIEXPORT void JNICALL
Java_com_limelight_nvstream_jni_MoonBridge_sendMultiControllerInput(JNIEnv *env, jclass clazz, jshort controllerNumber,
                                                           jshort activeGamepadMask, jint buttonFlags,
                                                           jbyte leftTrigger, jbyte rightTrigger,
                                                           jshort leftStickX, jshort leftStickY,
                                                           jshort rightStickX, jshort rightStickY) {
    LiSendMultiControllerEvent(controllerNumber, activeGamepadMask, buttonFlags,
        leftTrigger, rightTrigger, leftStickX, leftStickY, rightStickX, rightStickY);
}

JNIEXPORT jint JNICALL
Java_com_limelight_nvstream_jni_MoonBridge_sendTouchEvent(JNIEnv *env, jclass clazz,
                                                          jbyte eventType, jint pointerId,
                                                          jfloat x, jfloat y, jfloat pressureOrDistance,
                                                          jfloat contactAreaMajor, jfloat contactAreaMinor,
                                                          jshort rotation) {
    return LiSendTouchEvent(eventType, pointerId, x, y, pressureOrDistance,
                            contactAreaMajor, contactAreaMinor, rotation);
}

JNIEXPORT jint JNICALL
Java_com_limelight_nvstream_jni_MoonBridge_sendPenEvent(JNIEnv *env, jclass clazz, jbyte eventType,
                                                        jbyte toolType, jbyte penButtons,
                                                        jfloat x, jfloat y, jfloat pressureOrDistance,
                                                        jfloat contactAreaMajor, jfloat contactAreaMinor,
                                                        jshort rotation, jbyte tilt) {
    return LiSendPenEvent(eventType, toolType, penButtons, x, y, pressureOrDistance,
                          contactAreaMajor, contactAreaMinor, rotation, tilt);
}

JNIEXPORT jint JNICALL
Java_com_limelight_nvstream_jni_MoonBridge_sendControllerArrivalEvent(JNIEnv *env, jclass clazz,
                                                                      jbyte controllerNumber,
                                                                      jshort activeGamepadMask,
                                                                      jbyte type,
                                                                      jint supportedButtonFlags,
                                                                      jshort capabilities) {
    return LiSendControllerArrivalEvent(controllerNumber, activeGamepadMask, type, supportedButtonFlags, capabilities);
}

JNIEXPORT jint JNICALL
Java_com_limelight_nvstream_jni_MoonBridge_sendControllerTouchEvent(JNIEnv *env, jclass clazz,
                                                                    jbyte controllerNumber,
                                                                    jbyte eventType,
                                                                    jint pointerId, jfloat x,
                                                                    jfloat y, jfloat pressure) {
    return LiSendControllerTouchEvent(controllerNumber, eventType, pointerId, x, y, pressure);
}

JNIEXPORT jint JNICALL
Java_com_limelight_nvstream_jni_MoonBridge_sendControllerMotionEvent(JNIEnv *env, jclass clazz,
                                                                     jbyte controllerNumber,
                                                                     jbyte motionType, jfloat x,
                                                                     jfloat y, jfloat z) {
    return LiSendControllerMotionEvent(controllerNumber, motionType, x, y, z);
}

JNIEXPORT jint JNICALL
Java_com_limelight_nvstream_jni_MoonBridge_sendControllerBatteryEvent(JNIEnv *env, jclass clazz,
                                                                      jbyte controllerNumber,
                                                                      jbyte batteryState,
                                                                      jbyte batteryPercentage) {
    return LiSendControllerBatteryEvent(controllerNumber, batteryState, batteryPercentage);
}

JNIEXPORT void JNICALL
Java_com_limelight_nvstream_jni_MoonBridge_sendKeyboardInput(JNIEnv *env, jclass clazz, jshort keyCode, jbyte keyAction, jbyte modifiers, jbyte flags) {
    LiSendKeyboardEvent2(keyCode, keyAction, modifiers, flags);
}

JNIEXPORT void JNICALL
Java_com_limelight_nvstream_jni_MoonBridge_sendMouseHighResScroll(JNIEnv *env, jclass clazz, jshort scrollAmount) {
    LiSendHighResScrollEvent(scrollAmount);
}

JNIEXPORT void JNICALL
Java_com_limelight_nvstream_jni_MoonBridge_sendMouseHighResHScroll(JNIEnv *env, jclass clazz, jshort scrollAmount) {
    LiSendHighResHScrollEvent(scrollAmount);
}

JNIEXPORT void JNICALL
Java_com_limelight_nvstream_jni_MoonBridge_sendUtf8Text(JNIEnv *env, jclass clazz, jstring text) {
    const char* utf8Text = (*env)->GetStringUTFChars(env, text, NULL);
    LiSendUtf8TextEvent(utf8Text, strlen(utf8Text));
    (*env)->ReleaseStringUTFChars(env, text, utf8Text);
}

JNIEXPORT void JNICALL
Java_com_limelight_nvstream_jni_MoonBridge_stopConnection(JNIEnv *env, jclass clazz) {
    LiStopConnection();
}

JNIEXPORT void JNICALL
Java_com_limelight_nvstream_jni_MoonBridge_interruptConnection(JNIEnv *env, jclass clazz) {
    LiInterruptConnection();
}

JNIEXPORT jstring JNICALL
Java_com_limelight_nvstream_jni_MoonBridge_getStageName(JNIEnv *env, jclass clazz, jint stage) {
    return (*env)->NewStringUTF(env, LiGetStageName(stage));
}

JNIEXPORT jstring JNICALL
Java_com_limelight_nvstream_jni_MoonBridge_findExternalAddressIP4(JNIEnv *env, jclass clazz, jstring stunHostName, jint stunPort) {
    int err;
    struct in_addr wanAddr;
    const char* stunHostNameStr = (*env)->GetStringUTFChars(env, stunHostName, NULL);

    err = LiFindExternalAddressIP4(stunHostNameStr, stunPort, &wanAddr.s_addr);
    (*env)->ReleaseStringUTFChars(env, stunHostName, stunHostNameStr);

    if (err == 0) {
        char addrStr[INET_ADDRSTRLEN];

        inet_ntop(AF_INET, &wanAddr, addrStr, sizeof(addrStr));

        __android_log_print(ANDROID_LOG_INFO, "moonlight-common-c", "Resolved WAN address to %s", addrStr);

        return (*env)->NewStringUTF(env, addrStr);
    }
    else {
        __android_log_print(ANDROID_LOG_ERROR, "moonlight-common-c", "STUN failed to get WAN address: %d", err);
        return NULL;
    }
}

JNIEXPORT jint JNICALL
Java_com_limelight_nvstream_jni_MoonBridge_getPendingAudioDuration(JNIEnv *env, jclass clazz) {
    return LiGetPendingAudioDuration();
}

JNIEXPORT jint JNICALL
Java_com_limelight_nvstream_jni_MoonBridge_getPendingVideoFrames(JNIEnv *env, jclass clazz) {
    return LiGetPendingVideoFrames();
}

JNIEXPORT jlong JNICALL
Java_com_limelight_nvstream_jni_MoonBridge_getDecoderDrops(JNIEnv *env, jclass clazz) {
    return (jlong)LiGetDecoderDrops();
}

JNIEXPORT jint JNICALL
Java_com_limelight_nvstream_jni_MoonBridge_testClientConnectivity(JNIEnv *env, jclass clazz, jstring testServerHostName, jint referencePort, jint testFlags) {
    int ret;
    const char* testServerHostNameStr = (*env)->GetStringUTFChars(env, testServerHostName, NULL);

    ret = LiTestClientConnectivity(testServerHostNameStr, (unsigned short)referencePort, testFlags);

    (*env)->ReleaseStringUTFChars(env, testServerHostName, testServerHostNameStr);

    return ret;
}

JNIEXPORT jint JNICALL
Java_com_limelight_nvstream_jni_MoonBridge_getPortFlagsFromStage(JNIEnv *env, jclass clazz, jint stage) {
    return LiGetPortFlagsFromStage(stage);
}

JNIEXPORT jint JNICALL
Java_com_limelight_nvstream_jni_MoonBridge_getPortFlagsFromTerminationErrorCode(JNIEnv *env, jclass clazz, jint errorCode) {
    return LiGetPortFlagsFromTerminationErrorCode(errorCode);
}

JNIEXPORT jstring JNICALL
Java_com_limelight_nvstream_jni_MoonBridge_stringifyPortFlags(JNIEnv *env, jclass clazz, jint portFlags, jstring separator) {
    const char* separatorStr = (*env)->GetStringUTFChars(env, separator, NULL);
    char outputBuffer[512];

    LiStringifyPortFlags(portFlags, separatorStr, outputBuffer, sizeof(outputBuffer));

    (*env)->ReleaseStringUTFChars(env, separator, separatorStr);
    return (*env)->NewStringUTF(env, outputBuffer);
}

JNIEXPORT jlong JNICALL
Java_com_limelight_nvstream_jni_MoonBridge_getEstimatedRttInfo(JNIEnv *env, jclass clazz) {
    uint32_t rtt, variance;

    if (!LiGetEstimatedRttInfo(&rtt, &variance)) {
        return -1;
    }

    return ((uint64_t)rtt << 32U) | variance;
}

JNIEXPORT jint JNICALL
Java_com_limelight_nvstream_jni_MoonBridge_getControlStreamSilenceMs(JNIEnv *env, jclass clazz) {
    return LiGetControlStreamSilenceMs();
}

JNIEXPORT jboolean JNICALL
Java_com_limelight_nvstream_jni_MoonBridge_isDynamicFecNegotiated(JNIEnv *env, jclass clazz) {
    return LiIsDynamicFecNegotiated() ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL
Java_com_limelight_nvstream_jni_MoonBridge_isClientRoamingNegotiated(JNIEnv *env, jclass clazz) {
    return LiIsClientRoamingNegotiated() ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNICALL
Java_com_limelight_nvstream_jni_MoonBridge_notifyNetworkChanged(JNIEnv *env, jclass clazz, jboolean metered) {
    LiNotifyNetworkChanged(metered != JNI_FALSE);
}

JNIEXPORT jint JNICALL
Java_com_limelight_nvstream_jni_MoonBridge_getClientRoamingStatus(JNIEnv *env, jclass clazz) {
    return LiGetClientRoamingStatus();
}

JNIEXPORT jint JNICALL
Java_com_limelight_nvstream_jni_MoonBridge_getClientRoamingCount(JNIEnv *env, jclass clazz) {
    return (jint) LiGetClientRoamingCount();
}

// Frame Warp (see Limelight.h). The shift reaches Java as the library works it out and the lens
// terms are derived there (FrameWarpLens), where they can be tested.
static volatile float lastFrameWarpShiftX, lastFrameWarpShiftY;

JNIEXPORT jboolean JNICALL
Java_com_limelight_nvstream_jni_MoonBridge_isFrameWarpNegotiated(JNIEnv *env, jclass clazz) {
    return LiIsFrameWarpNegotiated() ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jint JNICALL
Java_com_limelight_nvstream_jni_MoonBridge_setFrameWarpEnabled(JNIEnv *env, jclass clazz, jboolean enabled) {
    return LiSetFrameWarpEnabled(enabled != JNI_FALSE);
}

JNIEXPORT jint JNICALL
Java_com_limelight_nvstream_jni_MoonBridge_setFrameWarpController(JNIEnv *env, jclass clazz, jint controller) {
    return LiSetFrameWarpController((uint8_t) controller);
}

// out (at least 7 floats): shift x and y in picture pixels, focal, picture width and height, fade,
// tag status. Returns the generation of the model used, or -2 when there is nothing to draw.
JNIEXPORT jint JNICALL
Java_com_limelight_nvstream_jni_MoonBridge_frameWarpShift(JNIEnv *env, jclass clazz, jlong frameNumber,
                                                          jfloatArray out) {
    LI_FRAME_WARP_SHIFT shift;

    if (!LiGetFrameWarpShift((uint32_t) frameNumber, LiGetMicroseconds(), &shift)) {
        lastFrameWarpShiftX = lastFrameWarpShiftY = 0.0f;
        return -2;
    }
    lastFrameWarpShiftX = shift.x;
    lastFrameWarpShiftY = shift.y;
    if ((*env)->GetArrayLength(env, out) >= 7) {
        jfloat values[7] = {
            shift.x, shift.y, shift.focal, (jfloat) shift.pictureWidth, (jfloat) shift.pictureHeight,
            shift.fade, (jfloat) shift.tagStatus,
        };
        (*env)->SetFloatArrayRegion(env, out, 0, 7, values);
    }
    return shift.generation;
}

// The HUD mask when its generation is not `known` (-1 for none), one byte per cell (0 or 255, rows
// from the top) in cells; size gets width and height. Returns the generation, -1 when the caller's
// mask is current, -2 when there is none any more, or -3 when cells is too small for size.
JNIEXPORT jint JNICALL
Java_com_limelight_nvstream_jni_MoonBridge_frameWarpMask(JNIEnv *env, jclass clazz, jint known,
                                                         jintArray size, jbyteArray cells) {
    uint16_t generation = 0, width = 0, height = 0;
    uint32_t bytes = LiGetFrameWarpMask(&generation, &width, &height, NULL, 0);

    if (bytes == 0 || width == 0 || height == 0) {
        return known < 0 ? -1 : -2;
    }
    if ((jint) generation == known) {
        return -1;
    }

    jint dimensions[2] = { width, height };
    (*env)->SetIntArrayRegion(env, size, 0, 2, dimensions);
    jsize count = (jsize) width * height;
    if ((*env)->GetArrayLength(env, cells) < count) {
        return -3;
    }

    uint8_t *bits = (uint8_t *) malloc(bytes);
    jbyte *bytesOut = (jbyte *) malloc((size_t) count);
    if (bits == NULL || bytesOut == NULL) {
        free(bits);
        free(bytesOut);
        return -1;
    }
    // The mask may have been replaced between the two calls: the second call's own numbers hold
    uint16_t copiedGeneration = 0, copiedWidth = 0, copiedHeight = 0;
    uint32_t copied = LiGetFrameWarpMask(&copiedGeneration, &copiedWidth, &copiedHeight, bits, bytes);
    jint result = -1;
    if (copied == bytes && copiedWidth == width && copiedHeight == height) {
        for (jsize i = 0; i < count; i++) {
            bytesOut[i] = (bits[i >> 3] >> (i & 7)) & 1 ? (jbyte) 0xFF : 0;
        }
        (*env)->SetByteArrayRegion(env, cells, 0, count, bytesOut);
        result = copiedGeneration;
    }
    free(bits);
    free(bytesOut);
    return result;
}

// out (at least 8 floats): the game's lag in ms, the fit, pictures shifted, extrapolated and drawn
// unshifted for lack of a tag, the last shift's x and y in picture pixels, and a bit set of
// 1 = mouse locked, 2 = stick locked. Returns 0 when not negotiated, 1 while the host has not
// locked a model yet, 2 once it has.
JNIEXPORT jint JNICALL
Java_com_limelight_nvstream_jni_MoonBridge_frameWarpStatus(JNIEnv *env, jclass clazz, jfloatArray out) {
    LI_FRAME_WARP_STATE state;
    uint32_t shifted = 0, extrapolated = 0, untagged = 0;

    if (!LiGetFrameWarpState(&state)) {
        return 0;
    }
    LiGetFrameWarpCounters(&shifted, &extrapolated, &untagged);
    if ((*env)->GetArrayLength(env, out) >= 8) {
        jfloat values[8] = {
            (jfloat) state.lagUs / 1000.0f, state.fit, (jfloat) shifted, (jfloat) extrapolated,
            (jfloat) untagged, lastFrameWarpShiftX, lastFrameWarpShiftY,
            (jfloat) ((state.mouseLocked ? 1 : 0) | (state.stickLocked ? 2 : 0)),
        };
        (*env)->SetFloatArrayRegion(env, out, 0, 8, values);
    }
    return state.active ? 2 : 1;
}

JNIEXPORT jint JNICALL
Java_com_limelight_nvstream_jni_MoonBridge_getNegotiatedChromaPacking(JNIEnv *env, jclass clazz) {
    return LiGetNegotiatedChromaPacking();
}

JNIEXPORT jint JNICALL
Java_com_limelight_nvstream_jni_MoonBridge_getHostFeatureFlags(JNIEnv *env, jclass clazz) {
    return (jint) LiGetHostFeatureFlags();
}

JNIEXPORT jstring JNICALL
Java_com_limelight_nvstream_jni_MoonBridge_getLaunchUrlQueryParameters(JNIEnv *env, jclass clazz) {
    return (*env)->NewStringUTF(env, LiGetLaunchUrlQueryParameters());
}

JNIEXPORT jbyte JNICALL
Java_com_limelight_nvstream_jni_MoonBridge_guessControllerType(JNIEnv *env, jclass clazz, jint vendorId, jint productId) {
    unsigned int unDeviceID = MAKE_CONTROLLER_ID(vendorId, productId);
    for (int i = 0; i < sizeof(arrControllers) / sizeof(arrControllers[0]); i++) {
        if (unDeviceID == arrControllers[i].m_unDeviceID) {
            switch (arrControllers[i].m_eControllerType) {
                case k_eControllerType_XBox360Controller:
                case k_eControllerType_XBoxOneController:
                    return LI_CTYPE_XBOX;

                case k_eControllerType_PS3Controller:
                case k_eControllerType_PS4Controller:
                case k_eControllerType_PS5Controller:
                    return LI_CTYPE_PS;

                case k_eControllerType_WiiController:
                case k_eControllerType_SwitchProController:
                case k_eControllerType_SwitchJoyConLeft:
                case k_eControllerType_SwitchJoyConRight:
                case k_eControllerType_SwitchJoyConPair:
                case k_eControllerType_SwitchInputOnlyController:
                    return LI_CTYPE_NINTENDO;

                default:
                    return LI_CTYPE_UNKNOWN;
            }
        }
    }
    return LI_CTYPE_UNKNOWN;
}

JNIEXPORT jboolean JNICALL
Java_com_limelight_nvstream_jni_MoonBridge_guessControllerHasPaddles(JNIEnv *env, jclass clazz, jint vendorId, jint productId) {
    // Xbox Elite and DualSense Edge controllers have paddles
    return SDL_IsJoystickXboxOneElite(vendorId, productId) || SDL_IsJoystickDualSenseEdge(vendorId, productId);
}

JNIEXPORT jboolean JNICALL
Java_com_limelight_nvstream_jni_MoonBridge_guessControllerHasShareButton(JNIEnv *env, jclass clazz, jint vendorId, jint productId) {
    // Xbox Elite and DualSense Edge controllers have paddles
    return SDL_IsJoystickXboxSeriesX(vendorId, productId);
}

// PyroWave (arm64-v8a only, see ../pyrowave-renderer). These live here rather
// than in the renderer because moonlight-core hides the symbols of the static
// libraries it links, which would hide JNI entry points defined there.
JNIEXPORT jboolean JNICALL
Java_com_limelight_nvstream_jni_MoonBridge_pyroWaveIsSupported(JNIEnv *env, jclass clazz) {
#ifdef HAVE_PYROWAVE
    return PyroWaveRendererIsSupported() ? JNI_TRUE : JNI_FALSE;
#else
    return JNI_FALSE;
#endif
}

JNIEXPORT jint JNICALL
Java_com_limelight_nvstream_jni_MoonBridge_pyroWaveStart(JNIEnv *env, jclass clazz, jobject surface,
                                                         jint videoFormat, jint width, jint height,
                                                         jint colorspace, jint colorRange) {
#ifdef HAVE_PYROWAVE
    ANativeWindow* window = ANativeWindow_fromSurface(env, surface);
    if (window == NULL) {
        return -1;
    }
    return PyroWaveRendererStart(window, videoFormat, width, height, colorspace, colorRange);
#else
    return -1;
#endif
}

JNIEXPORT void JNICALL
Java_com_limelight_nvstream_jni_MoonBridge_pyroWaveStop(JNIEnv *env, jclass clazz) {
#ifdef HAVE_PYROWAVE
    PyroWaveRendererStop();
#endif
}

// Frame Warp on the running PyroWave renderer: true when it will draw the shift. False when the
// device or the build cannot, and the stream then goes without.
JNIEXPORT jboolean JNICALL
Java_com_limelight_nvstream_jni_MoonBridge_pyroWaveSetFrameWarp(JNIEnv *env, jclass clazz) {
#ifdef HAVE_PYROWAVE
    return PyroWaveRendererSetFrameWarp() ? JNI_TRUE : JNI_FALSE;
#else
    return JNI_FALSE;
#endif
}

JNIEXPORT jstring JNICALL
Java_com_limelight_nvstream_jni_MoonBridge_pyroWaveTakeStats(JNIEnv *env, jclass clazz, jdoubleArray out) {
#ifdef HAVE_PYROWAVE
    PYROWAVE_RENDERER_STATS stats;
    if ((*env)->GetArrayLength(env, out) < 10 || PyroWaveRendererTakeStats(&stats) != 0) {
        return NULL;
    }
    // Order documented in MoonBridge.pyroWaveTakeStats()
    jdouble values[10] = {
        (jdouble)stats.received, (jdouble)stats.presented, (jdouble)stats.dropped,
        (jdouble)stats.partial, (jdouble)stats.lost, (jdouble)stats.hostLatencyFrames,
        (jdouble)stats.hostLatencyTotal, (jdouble)stats.hostLatencyMin,
        (jdouble)stats.hostLatencyMax, stats.gpuDecodeMs,
    };
    (*env)->SetDoubleArrayRegion(env, out, 0, 10, values);
    return (*env)->NewStringUTF(env, stats.gpuName);
#else
    return NULL;
#endif
}
