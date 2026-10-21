package com.limelight.binding.input;

import android.view.MotionEvent;

import com.limelight.nvstream.NvConnection;
import com.limelight.nvstream.input.MouseButtonPacket;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.mockito.ArgumentMatchers.anyByte;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/** The mouse buttons the host holds as the controls open, and their release. */
public class HeldMouseButtonsTest {

    private static final int LEFT = HeldMouseButtons.of(MouseButtonPacket.BUTTON_LEFT);
    private static final int MIDDLE = HeldMouseButtons.of(MouseButtonPacket.BUTTON_MIDDLE);
    private static final int RIGHT = HeldMouseButtons.of(MouseButtonPacket.BUTTON_RIGHT);
    private static final int X1 = HeldMouseButtons.of(MouseButtonPacket.BUTTON_X1);
    private static final int X2 = HeldMouseButtons.of(MouseButtonPacket.BUTTON_X2);

    @Test
    public void nothingPressed_holdsNothing() {
        assertEquals(0, HeldMouseButtons.fromButtonState(0, true));
    }

    @Test
    public void mouseButtons_mapAsTheStreamSendsThem() {
        assertEquals(LEFT, HeldMouseButtons.fromButtonState(MotionEvent.BUTTON_PRIMARY, false));
        assertEquals(RIGHT, HeldMouseButtons.fromButtonState(MotionEvent.BUTTON_SECONDARY, false));
        assertEquals(MIDDLE, HeldMouseButtons.fromButtonState(MotionEvent.BUTTON_TERTIARY, false));
        assertEquals(LEFT | RIGHT, HeldMouseButtons.fromButtonState(
                MotionEvent.BUTTON_PRIMARY | MotionEvent.BUTTON_SECONDARY, false));
    }

    @Test
    public void stylusButtons_areRightAndMiddle() {
        assertEquals(RIGHT, HeldMouseButtons.fromButtonState(MotionEvent.BUTTON_STYLUS_PRIMARY, false));
        assertEquals(MIDDLE, HeldMouseButtons.fromButtonState(MotionEvent.BUTTON_STYLUS_SECONDARY, false));
    }

    @Test
    public void backAndForward_areButtonsOnlyWhenSentAsSuch() {
        int state = MotionEvent.BUTTON_BACK | MotionEvent.BUTTON_FORWARD;
        assertEquals(X1 | X2, HeldMouseButtons.fromButtonState(state, true));
        assertEquals(0, HeldMouseButtons.fromButtonState(state, false));
    }

    @Test
    public void release_letsGoOfEachButtonOnce() {
        NvConnection conn = mock(NvConnection.class);

        HeldMouseButtons.release(conn, LEFT | RIGHT | X2);

        verify(conn, times(1)).sendMouseButtonUp(MouseButtonPacket.BUTTON_LEFT);
        verify(conn, times(1)).sendMouseButtonUp(MouseButtonPacket.BUTTON_RIGHT);
        verify(conn, times(1)).sendMouseButtonUp(MouseButtonPacket.BUTTON_X2);
        verify(conn, never()).sendMouseButtonUp(MouseButtonPacket.BUTTON_MIDDLE);
        verify(conn, never()).sendMouseButtonUp(MouseButtonPacket.BUTTON_X1);
    }

    @Test
    public void release_ofNothing_sendsNothing() {
        NvConnection conn = mock(NvConnection.class);

        HeldMouseButtons.release(conn, 0);

        verify(conn, never()).sendMouseButtonUp(anyByte());
    }
}
