package com.limelight.nvstream;

public interface NvConnectionListener {
    void stageStarting(String stage);
    void stageComplete(String stage);
    boolean stageFailed(String stage, int portFlags, int errorCode);
    
    void connectionStarted();
    void connectionTerminated(int errorCode);
    void connectionStatusUpdate(int connectionStatus);
    
    void displayMessage(String message);
    void displayTransientMessage(String message);

    void rumble(short controllerNumber, short lowFreqMotor, short highFreqMotor);
    void rumbleTriggers(short controllerNumber, short leftTrigger, short rightTrigger);

    void setHdrMode(boolean enabled, byte[] hdrMetadata);

    void setMotionEventState(short controllerNumber, byte motionType, short reportRateHz);

    void setControllerLED(short controllerNumber, byte r, byte g, byte b);

    // The host reported what kind of text field has focus, one of TextFocusKeyboard.FOCUS_*.
    // Only called when the stream was started with text focus hints enabled.
    void setTextFocus(byte focusType);

    // The host reports the size of the video frames from now on, answering a resolution change
    // request. Frames of the old size may still arrive after it. Only called on hosts that
    // advertised MoonBridge.LI_FF_CLIENT_RESOLUTION_CHANGE.
    void resolutionChanged(int width, int height);
}
