package com.limelight.binding.video;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class ResolutionFrameTrackerTest {

    @Test
    public void aFrameOfTheOldSizeNeverConfirms() {
        ResolutionFrameTracker tracker = new ResolutionFrameTracker(2560, 1440);
        Object codec = new Object();
        assertFalse(tracker.presented(codec, 100));
        tracker.decoded(codec, 100, 1920, 1080);
        assertFalse(tracker.presented(codec, 100));
    }

    @Test
    public void onlyAFrameOfTheNewSizeThatWasShownConfirms() {
        ResolutionFrameTracker tracker = new ResolutionFrameTracker(2560, 1440);
        Object codec = new Object();
        tracker.decoded(codec, 100, 1920, 1080);
        tracker.decoded(codec, 200, 2560, 1440);
        // Shown, but it is the old picture's timestamp
        assertFalse(tracker.presented(codec, 100));
        assertTrue(tracker.presented(codec, 200));
        // Confirmed once
        assertFalse(tracker.presented(codec, 200));
    }

    @Test
    public void aFrameDecodedButNotShownDoesNotConfirm() {
        ResolutionFrameTracker tracker = new ResolutionFrameTracker(1280, 720);
        Object codec = new Object();
        tracker.decoded(codec, 7, 1280, 720);
        assertFalse(tracker.presented(codec, 8));
    }

    @Test
    public void aReplacedDecoderCannotConfirmWithTheSameTimestamp() {
        ResolutionFrameTracker tracker = new ResolutionFrameTracker(1280, 720);
        Object oldCodec = new Object();
        Object newCodec = new Object();
        tracker.decoded(oldCodec, 42, 1280, 720);
        assertFalse(tracker.presented(newCodec, 42));
        tracker.decoded(newCodec, 42, 1280, 720);
        assertTrue(tracker.presented(newCodec, 42));
    }

    @Test
    public void droppedFramesCannotGrowTheTrackingWithoutBound() {
        ResolutionFrameTracker tracker = new ResolutionFrameTracker(1920, 1080);
        Object codec = new Object();
        for (long pts = 0; pts < 100; pts++) {
            tracker.decoded(codec, pts, 1920, 1080);
        }
        assertFalse(tracker.presented(codec, 0));
        assertTrue(tracker.presented(codec, 99));
    }
}
