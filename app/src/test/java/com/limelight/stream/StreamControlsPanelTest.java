package com.limelight.stream;

import android.content.res.Resources;

import androidx.test.core.app.ApplicationProvider;

import com.limelight.binding.video.PerfStats;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import static org.junit.Assert.assertEquals;

/** The side panel's header lines, as the approved Stream mock words them. */
@Config(sdk = {33})
@RunWith(RobolectricTestRunner.class)
public class StreamControlsPanelTest {

    private static Resources res() {
        return ApplicationProvider.getApplicationContext().getResources();
    }

    private static PerfStats stats(int width, int height, float fps, float kbps, int latencyMs) {
        return new PerfStats(width, height, "c2.test", fps, fps, fps, 0f, kbps, latencyMs, 1, 2f,
                false, 0f, 0f, 0f);
    }

    @Test
    public void resolutionIsNamedByItsShortSide() {
        assertEquals("4K", StreamControlsPanel.resolutionLabel(res(), 3840, 2160));
        assertEquals("1080p", StreamControlsPanel.resolutionLabel(res(), 1920, 1080));
        // A portrait stream reads the same as its landscape twin.
        assertEquals("1080p", StreamControlsPanel.resolutionLabel(res(), 1080, 1920));
        assertEquals("1440p", StreamControlsPanel.resolutionLabel(res(), 2560, 1440));
    }

    @Test
    public void statsLineCarriesFpsLatencyAndBitrate() {
        assertEquals("4K 120 · 8 ms · 80 Mb",
                StreamControlsPanel.statsText(res(), stats(3840, 2160, 119.9f, 80400f, 8)));
        assertEquals("1080p60 · 12 ms",
                StreamControlsPanel.statsText(res(), stats(1920, 1080, 60f,
                        PerfStats.BANDWIDTH_UNAVAILABLE, 12)));
    }

    @Test
    public void modeSpacesANamedSizeFromItsRate() {
        assertEquals("4K 120", StreamControlsPanel.modeLabel(res(), 3840, 2160, 120));
        assertEquals("1440p144", StreamControlsPanel.modeLabel(res(), 2560, 1440, 144));
        assertEquals("720p30", StreamControlsPanel.modeLabel(res(), 720, 1280, 30));
    }

    @Test
    public void sessionLengthSwitchesToHoursPastTheHour() {
        assertEquals("0 min", StreamControlsPanel.sessionLength(res(), 20_000));
        assertEquals("59 min", StreamControlsPanel.sessionLength(res(), 59 * 60_000L));
        assertEquals("1 h 12 min", StreamControlsPanel.sessionLength(res(), 72 * 60_000L));
    }
}
