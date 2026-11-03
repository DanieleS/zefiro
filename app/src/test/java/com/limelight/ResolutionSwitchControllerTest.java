package com.limelight;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.limelight.nvstream.jni.MoonBridge;

import org.junit.Before;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

public class ResolutionSwitchControllerTest {

    /** Records what the controller asks of the app, in order. */
    private static final class FakeHost implements ResolutionSwitchController.Host {
        final List<String> calls = new ArrayList<>();
        int requestResult = 0;
        // Hold the transition's ready callback, as a frame capture that takes a while does
        boolean holdTransition;
        Runnable ready;

        @Override
        public int requestChange(int width, int height) {
            calls.add("request " + width + "x" + height);
            return requestResult;
        }

        @Override
        public void awaitFrame(int width, int height) {
            calls.add("await " + width + "x" + height);
        }

        @Override
        public void cancelFrameWait() {
            calls.add("cancelWait");
        }

        @Override
        public void restartDecoder(int width, int height) {
            calls.add("restart " + width + "x" + height);
        }

        @Override
        public void beginTransition(int width, int height, Runnable ready) {
            calls.add("begin " + width + "x" + height);
            if (holdTransition) {
                this.ready = ready;
            } else {
                ready.run();
            }
        }

        @Override
        public void revealNewStream() {
            calls.add("reveal");
        }

        @Override
        public void abandonTransition(boolean reconnecting) {
            calls.add("abandon " + reconnecting);
        }

        @Override
        public void confirmed(int width, int height) {
            calls.add("confirmed " + width + "x" + height);
        }

        @Override
        public void reconnect(int width, int height) {
            calls.add("reconnect " + width + "x" + height);
        }

        int count(String prefix) {
            int n = 0;
            for (String call : calls) {
                if (call.startsWith(prefix)) {
                    n++;
                }
            }
            return n;
        }
    }

    private static final class FakeScheduler implements ResolutionSwitchController.Scheduler {
        final List<Runnable> pending = new ArrayList<>();
        long lastDelay = -1;

        @Override
        public void postDelayed(Runnable runnable, long delayMs) {
            pending.add(runnable);
            lastDelay = delayMs;
        }

        @Override
        public void cancel(Runnable runnable) {
            pending.remove(runnable);
        }

        /** The timer going off, whether or not it was cancelled meanwhile (a late one). */
        void fire(Runnable runnable) {
            runnable.run();
        }
    }

    private FakeHost host;
    private FakeScheduler scheduler;
    private ResolutionSwitchController controller;

    @Before
    public void setUp() {
        host = new FakeHost();
        scheduler = new FakeScheduler();
        controller = new ResolutionSwitchController(host, scheduler);
    }

    @Test
    public void aRequestWaitsForTheFrameOfTheNewSize() {
        assertTrue(controller.start(1920, 1080));

        assertEquals("begin 1920x1080", host.calls.get(0));
        assertEquals("await 1920x1080", host.calls.get(1));
        assertEquals("request 1920x1080", host.calls.get(2));
        assertEquals(ResolutionSwitchController.FRAME_CONFIRM_TIMEOUT_MS, scheduler.lastDelay);
        assertEquals(5_000, ResolutionSwitchController.FRAME_CONFIRM_TIMEOUT_MS);
        assertTrue(controller.isActive());
        // Sent is not applied
        assertEquals(0, host.count("confirmed"));
    }

    @Test
    public void theHostsReportRestartsTheDecoderButConfirmsNothing() {
        controller.start(2560, 1440);
        controller.onHostResolution(2560, 1440);

        assertEquals(1, host.count("restart 2560x1440"));
        assertEquals(0, host.count("confirmed"));
        assertTrue(controller.isActive());
    }

    @Test
    public void aReportOfAnotherSizeIsIgnored() {
        controller.start(2560, 1440);
        controller.onHostResolution(1920, 1080);

        assertEquals(0, host.count("restart"));
        assertTrue(controller.isActive());
    }

    @Test
    public void theFrameOfTheNewSizeConfirmsOnceAndStopsTheTimer() {
        controller.start(1280, 720);
        controller.onHostResolution(1280, 720);
        controller.onFramePresented(1280, 720);

        assertEquals(1, host.count("confirmed 1280x720"));
        assertEquals(1, host.count("reveal"));
        assertFalse(controller.isActive());
        assertTrue(scheduler.pending.isEmpty());

        // A second report of the same frame, or a late timer, changes nothing
        controller.onFramePresented(1280, 720);
        assertEquals(1, host.count("confirmed"));
        assertEquals(0, host.count("reconnect"));
    }

    @Test
    public void aFrameOfAnotherSizeDoesNotConfirm() {
        controller.start(1280, 720);
        controller.onFramePresented(1920, 1080);

        assertEquals(0, host.count("confirmed"));
        assertTrue(controller.isActive());
    }

    @Test
    public void noFrameInFiveSecondsReconnectsOnceAtTheNewSize() {
        controller.start(1280, 720);
        Runnable timeout = scheduler.pending.get(0);
        scheduler.fire(timeout);

        assertEquals(1, host.count("reconnect 1280x720"));
        assertEquals(1, host.count("abandon true"));
        assertFalse(controller.isActive());

        // Neither a late timer nor a late frame starts a second fallback or confirms
        scheduler.fire(timeout);
        controller.onFramePresented(1280, 720);
        assertEquals(1, host.count("reconnect"));
        assertEquals(0, host.count("confirmed"));
    }

    @Test
    public void aRefusedRequestReconnectsAtOnce() {
        host.requestResult = MoonBridge.LI_ERR_UNSUPPORTED;
        controller.start(1920, 1080);

        assertEquals(1, host.count("reconnect 1920x1080"));
        assertFalse(controller.isActive());
        assertTrue(scheduler.pending.isEmpty());
    }

    @Test
    public void theConnectionEndingWhileWaitingReconnects() {
        controller.start(1920, 1080);

        assertTrue(controller.onConnectionTerminated());
        assertEquals(1, host.count("reconnect 1920x1080"));
        // Nothing waits any more: the next end is the stream's own
        assertFalse(controller.onConnectionTerminated());
    }

    @Test
    public void theConnectionEndingWithNothingWaitingIsNotTaken() {
        assertFalse(controller.onConnectionTerminated());
        controller.start(1920, 1080);
        controller.onFramePresented(1920, 1080);
        assertFalse(controller.onConnectionTerminated());
    }

    @Test
    public void cancellingForgetsTheChange() {
        controller.start(1920, 1080);
        controller.cancel();

        assertEquals(1, host.count("abandon false"));
        assertFalse(controller.isActive());
        assertTrue(scheduler.pending.isEmpty());
        controller.onFramePresented(1920, 1080);
        controller.onHostResolution(1920, 1080);
        assertEquals(0, host.count("confirmed"));
        assertEquals(0, host.count("restart"));
        assertEquals(0, host.count("reconnect"));
    }

    @Test
    public void cancellingBeforeTheFrameIsHeldSendsNoRequest() {
        host.holdTransition = true;
        controller.start(1920, 1080);
        assertEquals(0, host.count("request"));
        controller.cancel();
        host.ready.run();

        assertEquals(0, host.count("request"));
        assertEquals(0, host.count("await"));
    }

    @Test
    public void onlyOneChangeIsUnderWayAtATime() {
        assertTrue(controller.start(1920, 1080));
        assertFalse(controller.start(2560, 1440));
        assertEquals(1, host.count("begin"));
    }

    @Test
    public void aNewChangeCanStartAfterTheLastEnded() {
        controller.start(1920, 1080);
        controller.onFramePresented(1920, 1080);
        assertTrue(controller.start(1280, 720));
        assertEquals(2, host.count("request"));
    }

    @Test
    public void sdrH264HevcAndAv1ChangeInPlaceOnADecoderThatCan() {
        assertTrue(ResolutionSwitchController.isLiveSwitchSupported(MoonBridge.VIDEO_FORMAT_H264, true));
        assertTrue(ResolutionSwitchController.isLiveSwitchSupported(MoonBridge.VIDEO_FORMAT_H265, true));
        assertTrue(ResolutionSwitchController.isLiveSwitchSupported(MoonBridge.VIDEO_FORMAT_AV1_MAIN8, true));
    }

    @Test
    public void hdrTenBitPyroWaveAndALimitedDecoderReconnectInstead() {
        assertFalse(ResolutionSwitchController.isLiveSwitchSupported(MoonBridge.VIDEO_FORMAT_H265_MAIN10, true));
        assertFalse(ResolutionSwitchController.isLiveSwitchSupported(MoonBridge.VIDEO_FORMAT_AV1_MAIN10, true));
        assertFalse(ResolutionSwitchController.isLiveSwitchSupported(MoonBridge.VIDEO_FORMAT_PYROWAVE, true));
        assertFalse(ResolutionSwitchController.isLiveSwitchSupported(MoonBridge.VIDEO_FORMAT_H264, false));
        assertFalse(ResolutionSwitchController.isLiveSwitchSupported(0, true));
    }

    @Test
    public void sizesAreEvenAndWithinTheHostsLimits() {
        assertTrue(ResolutionSwitchController.isValidSize(1920, 1080));
        assertTrue(ResolutionSwitchController.isValidSize(256, 256));
        assertTrue(ResolutionSwitchController.isValidSize(16384, 16384));
        assertFalse(ResolutionSwitchController.isValidSize(255, 256));
        assertFalse(ResolutionSwitchController.isValidSize(1921, 1080));
        assertFalse(ResolutionSwitchController.isValidSize(1920, 1081));
        assertFalse(ResolutionSwitchController.isValidSize(16386, 1080));
        assertFalse(ResolutionSwitchController.isValidSize(0, 0));
        assertFalse(ResolutionSwitchController.isValidSize(-1920, 1080));
    }
}
