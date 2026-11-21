package com.limelight.binding.video;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class FrameWarpFramesTest {
    @Test
    public void aTimeFindsTheFrameQueuedWithIt() {
        FrameWarpFrames frames = new FrameWarpFrames();
        frames.note(1_000_001, 41);
        frames.note(1_016_667, 42);
        assertEquals(41, frames.frameFor(1_000_001));
        assertEquals(42, frames.frameFor(1_016_667));
        assertEquals(FrameWarpFrames.UNKNOWN, frames.frameFor(1_000_002));
    }

    @Test
    public void anOverwrittenSlotIsUnknownNotWrong() {
        FrameWarpFrames frames = new FrameWarpFrames();
        frames.note(5, 1);
        frames.note(5 + 256, 2);
        assertEquals(2, frames.frameFor(5 + 256));
        assertEquals(FrameWarpFrames.UNKNOWN, frames.frameFor(5));
    }

    @Test
    public void frameNumbersAreUnsignedAndClearForgetsThem() {
        FrameWarpFrames frames = new FrameWarpFrames();
        frames.note(9, -1);
        assertEquals(0xFFFFFFFFL, frames.frameFor(9));
        frames.clear();
        assertEquals(FrameWarpFrames.UNKNOWN, frames.frameFor(9));
    }
}
