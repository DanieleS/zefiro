package com.limelight.stream;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.limelight.stream.StreamCapabilities.ResolutionChoice;

import org.junit.Test;

import java.util.List;

public class StreamResolutionsTest {

    @Test
    public void thePresetsThenTheNativeSizeRoundedDownToEven() {
        // A 2400x1081 panel, held landscape: the stream is 1080p
        List<ResolutionChoice> choices = StreamResolutions.choices(1920, 1080, 1081, 2401);

        assertEquals(4, choices.size());
        assertEquals("720p", choices.get(0).name);
        assertEquals(1280, choices.get(0).width);
        assertEquals("1080p", choices.get(1).name);
        assertEquals("1440p", choices.get(2).name);
        assertEquals(2560, choices.get(2).width);
        assertNull(choices.get(3).name);
        assertEquals(2400, choices.get(3).width);
        assertEquals(1080, choices.get(3).height);
    }

    @Test
    public void theSizeInUseIsMarkedAndOnlyThat() {
        List<ResolutionChoice> choices = StreamResolutions.choices(1920, 1080, 2400, 1080);

        assertFalse(choices.get(0).current);
        assertTrue(choices.get(1).current);
        assertFalse(choices.get(2).current);
        assertFalse(choices.get(3).current);

        List<ResolutionChoice> native_ = StreamResolutions.choices(2400, 1080, 2400, 1080);
        assertTrue(native_.get(3).current);
    }

    @Test
    public void aNativeSizeThatIsAPresetIsNotOfferedTwice() {
        List<ResolutionChoice> choices = StreamResolutions.choices(1280, 720, 1920, 1080);

        assertEquals(3, choices.size());
        assertEquals("1080p", choices.get(1).name);
    }

    @Test
    public void aPortraitStreamGetsTheSizesTurnedOnTheirSide() {
        List<ResolutionChoice> choices = StreamResolutions.choices(1080, 1920, 1080, 2400);

        assertEquals(720, choices.get(0).width);
        assertEquals(1280, choices.get(0).height);
        assertTrue(choices.get(1).current);
        assertEquals(1080, choices.get(3).width);
        assertEquals(2400, choices.get(3).height);
    }

    @Test
    public void noNativeSizeLeavesThePresets() {
        assertEquals(3, StreamResolutions.choices(1920, 1080, 0, 0).size());
    }

    @Test
    public void bothSidesFitInAnArgument() {
        int packed = StreamResolutions.pack(16384, 2160);
        assertEquals(16384, StreamResolutions.unpackWidth(packed));
        assertEquals(2160, StreamResolutions.unpackHeight(packed));
        assertTrue(packed > 0);
    }
}
