package com.limelight.stream;

import android.view.KeyEvent;

import androidx.annotation.NonNull;

import com.limelight.nvstream.input.ControllerPacket;
import com.limelight.stream.ControlsSurface.Nav;

import org.junit.Before;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * The controls' input rules: which key means what, that a hat or stick moves once per push, and
 * that nothing (a key-up included) gets past while the controls are open.
 */
public class ControlsInputRouterTest {

    /** A surface that records what it was told and can be opened and closed by the test. */
    private static final class FakeTarget implements ControlsInputRouter.Target {
        boolean open;
        final List<Nav> navs = new ArrayList<>();
        // What a nav does to the surface, for the presses that close it.
        Nav closesOn;

        @Override
        public boolean isOpen() {
            return open;
        }

        @Override
        public boolean onNav(@NonNull Nav nav) {
            navs.add(nav);
            if (nav == closesOn) {
                open = false;
            }
            return true;
        }
    }

    private FakeTarget target;
    private ControlsInputRouter router;

    @Before
    public void setUp() {
        target = new FakeTarget();
        router = new ControlsInputRouter(target);
    }

    @Test
    public void keysMapToTheirIntent() {
        assertEquals(Nav.UP, ControlsInputRouter.navForKey(KeyEvent.KEYCODE_DPAD_UP));
        assertEquals(Nav.DOWN, ControlsInputRouter.navForKey(KeyEvent.KEYCODE_DPAD_DOWN));
        assertEquals(Nav.LEFT, ControlsInputRouter.navForKey(KeyEvent.KEYCODE_DPAD_LEFT));
        assertEquals(Nav.RIGHT, ControlsInputRouter.navForKey(KeyEvent.KEYCODE_DPAD_RIGHT));

        assertEquals(Nav.ACTIVATE, ControlsInputRouter.navForKey(KeyEvent.KEYCODE_BUTTON_A));
        assertEquals(Nav.ACTIVATE, ControlsInputRouter.navForKey(KeyEvent.KEYCODE_DPAD_CENTER));
        assertEquals(Nav.ACTIVATE, ControlsInputRouter.navForKey(KeyEvent.KEYCODE_ENTER));

        assertEquals(Nav.BACK, ControlsInputRouter.navForKey(KeyEvent.KEYCODE_BUTTON_B));
        assertEquals(Nav.BACK, ControlsInputRouter.navForKey(KeyEvent.KEYCODE_BACK));
        assertEquals(Nav.BACK, ControlsInputRouter.navForKey(KeyEvent.KEYCODE_ESCAPE));

        assertEquals(Nav.CLOSE, ControlsInputRouter.navForKey(KeyEvent.KEYCODE_BUTTON_START));
        assertEquals(Nav.CLOSE, ControlsInputRouter.navForKey(KeyEvent.KEYCODE_MENU));

        assertNull(ControlsInputRouter.navForKey(KeyEvent.KEYCODE_BUTTON_X));
        assertNull(ControlsInputRouter.navForKey(KeyEvent.KEYCODE_W));
    }

    @Test
    public void closedControlsTakeNothing() {
        assertFalse(router.onKeyDown(KeyEvent.KEYCODE_BUTTON_A, 0));
        assertFalse(router.onKeyUp(KeyEvent.KEYCODE_BUTTON_A));
        assertFalse(router.onAxes(0, 1, 0, 0));
        assertTrue(target.navs.isEmpty());
    }

    @Test
    public void openControlsTakeEveryKeyEvenOnesWithNoMeaning() {
        target.open = true;
        assertTrue(router.onKeyDown(KeyEvent.KEYCODE_BUTTON_X, 0));
        assertTrue(router.onKeyUp(KeyEvent.KEYCODE_BUTTON_X));
        assertTrue(router.onKeyDown(KeyEvent.KEYCODE_DPAD_DOWN, 0));
        assertTrue(router.onKeyUp(KeyEvent.KEYCODE_DPAD_DOWN));
        assertEquals(Collections.singletonList(Nav.DOWN), target.navs);
    }

    @Test
    public void volumeKeysAlwaysReachTheSystem() {
        target.open = true;
        for (int key : new int[] {KeyEvent.KEYCODE_VOLUME_UP, KeyEvent.KEYCODE_VOLUME_DOWN,
                KeyEvent.KEYCODE_VOLUME_MUTE}) {
            assertFalse(router.onKeyDown(key, 0));
            assertFalse(router.onKeyUp(key));
        }
        assertTrue(target.navs.isEmpty());
    }

    @Test
    public void heldDirectionRepeatsButHeldButtonsActOnce() {
        target.open = true;
        router.onKeyDown(KeyEvent.KEYCODE_DPAD_DOWN, 0);
        router.onKeyDown(KeyEvent.KEYCODE_DPAD_DOWN, 1);
        router.onKeyDown(KeyEvent.KEYCODE_BUTTON_A, 0);
        router.onKeyDown(KeyEvent.KEYCODE_BUTTON_A, 1);
        router.onKeyDown(KeyEvent.KEYCODE_BUTTON_B, 3);

        assertEquals(Arrays.asList(Nav.DOWN, Nav.DOWN, Nav.ACTIVATE), target.navs);
    }

    @Test
    public void releaseOfAPressThatClosedTheControlsNeverReachesTheHost() {
        // A on a leaf: the controls close on the press, and the release follows them.
        target.open = true;
        target.closesOn = Nav.ACTIVATE;
        assertTrue(router.onKeyDown(KeyEvent.KEYCODE_BUTTON_A, 0));
        assertFalse(target.open);
        assertTrue("the host never saw A go down", router.onKeyUp(KeyEvent.KEYCODE_BUTTON_A));

        // Taken once only: the next press and release are the game's.
        assertFalse(router.onKeyDown(KeyEvent.KEYCODE_BUTTON_A, 0));
        assertFalse(router.onKeyUp(KeyEvent.KEYCODE_BUTTON_A));
    }

    @Test
    public void aReleaseLostWhileOpenNeverEatsALaterOne() {
        target.open = true;
        router.onKeyDown(KeyEvent.KEYCODE_BUTTON_B, 0);
        // Its release never came (the window lost focus); the controls close some other way.
        target.open = false;

        // The game's own press of B, then its release, must both reach the host.
        assertFalse(router.onKeyDown(KeyEvent.KEYCODE_BUTTON_B, 0));
        assertFalse(router.onKeyUp(KeyEvent.KEYCODE_BUTTON_B));
    }

    @Test
    public void hatMovesOncePerPush() {
        target.open = true;
        router.onAxes(0, 1, 0, 0);
        router.onAxes(0, 1, 0, 0);
        router.onAxes(0, 0, 0, 0);
        router.onAxes(0, 1, 0, 0);
        router.onAxes(-1, 0, 0, 0);
        router.onAxes(-1, 0, 0, 0);

        assertEquals(Arrays.asList(Nav.DOWN, Nav.DOWN, Nav.LEFT), target.navs);
    }

    @Test
    public void stickNeedsToLeanPastTheThreshold() {
        target.open = true;
        router.onAxes(0, 0, 0, 0.3f);
        assertTrue(target.navs.isEmpty());

        router.onAxes(0, 0, 0, -0.8f);
        router.onAxes(0, 0, 0, -0.9f);
        router.onAxes(0, 0, 0, -0.2f);
        router.onAxes(0, 0, 0.7f, 0);

        assertEquals(Arrays.asList(Nav.UP, Nav.RIGHT), target.navs);
    }

    @Test
    public void axesAreFourWayAndTheHatWins() {
        // A diagonal counts as its stronger axis.
        assertEquals(Nav.RIGHT, ControlsInputRouter.axisNav(0, 0, 0.9f, 0.6f));
        assertEquals(Nav.UP, ControlsInputRouter.axisNav(0, 0, 0.6f, -0.9f));
        // The hat is the deliberate one of the two.
        assertEquals(Nav.LEFT, ControlsInputRouter.axisNav(-1, 0, 0, 0.9f));
        assertNull(ControlsInputRouter.axisNav(0, 0, 0.1f, -0.1f));
    }

    @Test
    public void edgeStartsAfreshEachTimeTheControlsOpen() {
        target.open = true;
        router.onAxes(0, 1, 0, 0);
        target.open = false;
        router.onAxes(0, 1, 0, 0);
        target.open = true;
        // Still held as they reopen: that counts as a fresh push.
        router.onAxes(0, 1, 0, 0);

        assertEquals(Arrays.asList(Nav.DOWN, Nav.DOWN), target.navs);
    }

    @Test
    public void usbPadPressesAreEdgesAndMapLikeKeys() {
        ControlsInputRouter.PadReports pad = new ControlsInputRouter.PadReports();
        List<Nav> navs = new ArrayList<>();

        navs.addAll(pad.whileOpen(ControllerPacket.A_FLAG, 0, 0));
        // Still held: no second press.
        navs.addAll(pad.whileOpen(ControllerPacket.A_FLAG, 0, 0));
        navs.addAll(pad.whileOpen(ControllerPacket.B_FLAG, 0, 0));
        navs.addAll(pad.whileOpen(ControllerPacket.PLAY_FLAG, 0, 0));
        navs.addAll(pad.whileOpen(ControllerPacket.DOWN_FLAG, 0, 0));
        navs.addAll(pad.whileOpen(0, 0, 0));
        navs.addAll(pad.whileOpen(0, 0.9f, 0));
        navs.addAll(pad.whileOpen(0, 0.8f, 0.1f));

        assertEquals(Arrays.asList(Nav.ACTIVATE, Nav.BACK, Nav.CLOSE, Nav.DOWN, Nav.RIGHT),
                navs);
    }

    @Test
    public void usbPadButtonPressedIntoTheControlsIsHiddenUntilLetGo() {
        ControlsInputRouter.PadReports pad = new ControlsInputRouter.PadReports();
        pad.whileOpen(ControllerPacket.A_FLAG, 0, 0);

        // The A that closed the controls is still down: the host must not see it.
        assertEquals(ControllerPacket.X_FLAG,
                pad.forHost(ControllerPacket.A_FLAG | ControllerPacket.X_FLAG));
        // Let go, then pressed again: now it is the game's.
        assertEquals(0, pad.forHost(0));
        assertEquals(ControllerPacket.A_FLAG, pad.forHost(ControllerPacket.A_FLAG));
    }

    @Test
    public void usbPadButtonHeldAsTheControlsOpenIsNotAPress() {
        ControlsInputRouter.PadReports pad = new ControlsInputRouter.PadReports();
        pad.forHost(ControllerPacket.A_FLAG);

        assertTrue(pad.whileOpen(ControllerPacket.A_FLAG, 0, 0).isEmpty());
    }
}
