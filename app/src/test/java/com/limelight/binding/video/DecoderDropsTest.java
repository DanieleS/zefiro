package com.limelight.binding.video;

import android.app.Application;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import static org.junit.Assert.assertEquals;

/**
 * Frames the engine dropped over a full decode queue reach Java as a frame-number gap. They are
 * decoder drops, not network loss: they stay out of framesLost, which the loss figure and the
 * adaptive bitrate read.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, application = Application.class)
public class DecoderDropsTest {
    @Test
    public void aGapAcrossADecodeQueueOverflowIsDecoderDropsNotLoss() {
        VideoStats window = new VideoStats();
        MediaCodecDecoderRenderer.recordFrameGap(window, 21, true);
        assertEquals(0, window.framesLost);
        assertEquals(0, window.frameLossEvents);
        assertEquals(21, window.decoderDrops);
        assertEquals(21, window.totalFrames);
        window.totalFramesReceived = 79;
        window.totalFrames += 79;
        VideoStatsFps fps = window.getFps();
        assertEquals(21, fps.decoderDrops);
        assertEquals(0, (float) window.framesLost / window.totalFrames * 100, 0);
    }

    @Test
    public void aGapWithoutAnOverflowIsStillNetworkLoss() {
        VideoStats window = new VideoStats();
        MediaCodecDecoderRenderer.recordFrameGap(window, 2, false);
        window.totalFrames += 98;
        assertEquals(2, window.framesLost);
        assertEquals(1, window.frameLossEvents);
        assertEquals(0, window.decoderDrops);
        assertEquals(2, (float) window.framesLost / window.totalFrames * 100, 0.0001f);
    }

    @Test
    public void eachGapIsClassifiedOnItsOwn() {
        VideoStats window = new VideoStats();
        MediaCodecDecoderRenderer.recordFrameGap(window, 3, false);
        MediaCodecDecoderRenderer.recordFrameGap(window, 7, true);
        assertEquals(3, window.framesLost);
        assertEquals(1, window.frameLossEvents);
        assertEquals(7, window.decoderDrops);
        assertEquals(10, window.totalFrames);
    }

    @Test
    public void decoderDropsSurviveTheWindowRollover() {
        VideoStats window = new VideoStats();
        MediaCodecDecoderRenderer.recordFrameGap(window, 5, true);
        VideoStats last = new VideoStats();
        last.copy(window);
        VideoStats both = new VideoStats();
        both.add(last);
        both.add(window);
        assertEquals(10, both.decoderDrops);
        window.clear();
        assertEquals(0, window.decoderDrops);
    }
}
