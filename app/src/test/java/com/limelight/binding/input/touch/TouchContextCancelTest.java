package com.limelight.binding.input.touch;

import android.app.Activity;
import android.os.Looper;
import android.view.View;

import com.limelight.nvstream.NvConnection;
import com.limelight.nvstream.input.MouseButtonPacket;
import com.limelight.preferences.PreferenceConfiguration;
import com.limelight.shadows.ShadowMoonBridge;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.LooperMode;

import java.time.Duration;

import static org.mockito.ArgumentMatchers.anyByte;
import static org.mockito.ArgumentMatchers.anyShort;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.robolectric.Shadows.shadowOf;

/**
 * A touch gesture cut off by the in-stream controls opening: the cancel ends it at once. A
 * button it holds is released, a tap's delayed release goes out now rather than later, a flick
 * stops moving the mouse, and a gesture that already ended releases nothing twice.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {33}, shadows = {ShadowMoonBridge.class})
@LooperMode(LooperMode.Mode.PAUSED)
public class TouchContextCancelTest {

    private NvConnection conn;
    private View view;
    private PreferenceConfiguration prefs;

    @Before
    public void setUp() {
        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        conn = mock(NvConnection.class);
        view = new View(activity);
        view.layout(0, 0, 1280, 720);
        prefs = PreferenceConfiguration.readPreferences(activity);
    }

    @Test
    public void trackpad_aTapsPendingRelease_goesOutAtTheCancel() {
        TrackpadContext context = new TrackpadContext(conn, 0);
        tap(context);
        verify(conn).sendMouseButtonDown(MouseButtonPacket.BUTTON_LEFT);

        context.cancelTouch();
        verify(conn, times(1)).sendMouseButtonUp(MouseButtonPacket.BUTTON_LEFT);

        idle(500);
        verify(conn, times(1)).sendMouseButtonUp(anyByte());
    }

    @Test
    public void trackpad_aFlick_movesTheMouseOnItsOwn() {
        TrackpadContext context = new TrackpadContext(conn, 0);
        flick(context);
        clearInvocations(conn);

        idle(100);
        verify(conn, atLeastOnce()).sendMouseMove(anyShort(), anyShort());
    }

    @Test
    public void trackpad_aFlick_stopsAtTheCancel() {
        TrackpadContext context = new TrackpadContext(conn, 0);
        flick(context);
        clearInvocations(conn);

        context.cancelTouch();
        idle(500);
        verify(conn, never()).sendMouseMove(anyShort(), anyShort());
    }

    @Test
    public void relative_aTapsPendingRelease_goesOutAtTheCancel() {
        RelativeTouchContext context = new RelativeTouchContext(conn, 0, 1280, 720, view, prefs);
        tap(context);
        verify(conn).sendMouseButtonDown(MouseButtonPacket.BUTTON_LEFT);

        context.cancelTouch();
        verify(conn, times(1)).sendMouseButtonUp(MouseButtonPacket.BUTTON_LEFT);

        idle(500);
        verify(conn, times(1)).sendMouseButtonUp(anyByte());
    }

    @Test
    public void relative_aDragThatEnded_isNotReleasedAgain() {
        RelativeTouchContext context = new RelativeTouchContext(conn, 0, 1280, 720, view, prefs);
        context.setPointerCount(1);
        context.touchDownEvent(100, 100, 0, true);
        // Held still past the drag time: the button goes down
        idle(700);
        verify(conn).sendMouseButtonDown(MouseButtonPacket.BUTTON_LEFT);
        context.touchUpEvent(100, 100, 700);
        context.setPointerCount(0);
        verify(conn, times(1)).sendMouseButtonUp(MouseButtonPacket.BUTTON_LEFT);

        context.cancelTouch();
        verify(conn, times(1)).sendMouseButtonUp(anyByte());
    }

    @Test
    public void relative_aDragUnderWay_isReleasedAtTheCancel() {
        RelativeTouchContext context = new RelativeTouchContext(conn, 0, 1280, 720, view, prefs);
        context.setPointerCount(1);
        context.touchDownEvent(100, 100, 0, true);
        idle(700);

        context.cancelTouch();
        verify(conn, times(1)).sendMouseButtonUp(MouseButtonPacket.BUTTON_LEFT);
    }

    @Test
    public void absolute_aTapsPendingRelease_goesOutOnceAtTheCancel() {
        AbsoluteTouchContext context = new AbsoluteTouchContext(conn, 0, view, false);
        // Lifted inside the touch-down dead zone: the click is sent at the lift, its release late
        tap(context);
        verify(conn).sendMouseButtonDown(MouseButtonPacket.BUTTON_LEFT);

        context.cancelTouch();
        verify(conn, times(1)).sendMouseButtonUp(MouseButtonPacket.BUTTON_LEFT);

        idle(500);
        verify(conn, times(1)).sendMouseButtonUp(anyByte());
    }

    @Test
    public void absolute_aLongPressThatEnded_isNotReleasedAgain() {
        AbsoluteTouchContext context = new AbsoluteTouchContext(conn, 0, view, false);
        context.setPointerCount(1);
        context.touchDownEvent(100, 100, 0, true);
        // Held past the long press: left went down, then up for right
        idle(700);
        verify(conn).sendMouseButtonDown(MouseButtonPacket.BUTTON_RIGHT);
        context.touchUpEvent(100, 100, 700);
        verify(conn, times(1)).sendMouseButtonUp(MouseButtonPacket.BUTTON_RIGHT);

        context.cancelTouch();
        verify(conn, times(1)).sendMouseButtonUp(MouseButtonPacket.BUTTON_RIGHT);
        verify(conn, times(1)).sendMouseButtonUp(MouseButtonPacket.BUTTON_LEFT);
    }

    private static void tap(TouchContext context) {
        context.setPointerCount(1);
        context.touchDownEvent(100, 100, 0, true);
        context.touchUpEvent(100, 100, 50);
        context.setPointerCount(0);
    }

    // A quick one-finger swipe, lifted while still moving
    private static void flick(TouchContext context) {
        context.setPointerCount(1);
        context.touchDownEvent(0, 0, 0, true);
        context.touchMoveEvent(100, 0, 10);
        context.touchUpEvent(100, 0, 10);
        context.setPointerCount(0);
    }

    private static void idle(long ms) {
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms));
    }
}
