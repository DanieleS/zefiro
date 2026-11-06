package com.limelight.binding.input;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.limelight.preferences.PreferenceConfiguration;

import org.junit.Test;

public class GuideHoldTest {
    private final GuideHold hold = new GuideHold();

    @Test
    public void offLetsGuideThrough() {
        assertFalse(hold.down(0, false));
        assertFalse(hold.active());
        assertEquals(GuideHold.Release.PASS, hold.up());
    }

    @Test
    public void releasedBeforeTheHoldTimeIsATap() {
        assertTrue(hold.down(1000, false));
        assertTrue(hold.active());
        assertEquals(GuideHold.Release.TAP, hold.up());
        assertFalse(hold.active());
    }

    @Test
    public void heldPastTheHoldTimeOpensTheMenuAndSwallowsTheRelease() {
        assertTrue(hold.down(1000, false));
        assertTrue(hold.expire());
        assertTrue(hold.active());
        assertEquals(GuideHold.Release.SWALLOW, hold.up());
        assertEquals(GuideHold.Release.PASS, hold.up());
    }

    @Test
    public void theMenuOpensOncePerPress() {
        hold.down(500, false);
        assertTrue(hold.expire());
        assertFalse(hold.expire());
    }

    @Test
    public void expiringWithoutAPressDoesNothing() {
        assertFalse(hold.expire());
    }

    @Test
    public void aKeyRepeatKeepsTheSamePress() {
        hold.down(1000, false);
        assertTrue(hold.down(1000, true));
        assertEquals(GuideHold.Release.TAP, hold.up());
    }

    @Test
    public void aRepeatAfterTheMenuOpenedStaysHeldBack() {
        hold.down(1000, false);
        hold.expire();
        assertTrue(hold.down(1000, true));
        assertFalse(hold.expire());
        assertEquals(GuideHold.Release.SWALLOW, hold.up());
    }

    @Test
    public void aNewPressEndsALostRelease() {
        hold.down(1000, false);
        hold.expire();
        assertTrue(hold.down(1000, false));
        assertEquals(GuideHold.Release.TAP, hold.up());
    }

    @Test
    public void aChordSendsGuideAtOnceAndItsReleaseIsOrdinary() {
        hold.down(1000, false);
        assertTrue(hold.chord());
        assertFalse(hold.active());
        assertFalse(hold.expire());
        assertEquals(GuideHold.Release.PASS, hold.up());
    }

    @Test
    public void aChordNeedsAWaitingGuide() {
        assertFalse(hold.chord());
        hold.down(1000, false);
        hold.expire();
        assertFalse(hold.chord());
    }

    @Test
    public void resetForgetsThePress() {
        hold.down(1000, false);
        hold.reset();
        assertFalse(hold.active());
        assertEquals(GuideHold.Release.PASS, hold.up());
    }

    @Test
    public void storedHoldTimesAreOneOfTheOffered() {
        assertEquals(0, PreferenceConfiguration.guideMenuHoldMs(null));
        assertEquals(0, PreferenceConfiguration.guideMenuHoldMs("0"));
        assertEquals(500, PreferenceConfiguration.guideMenuHoldMs("500"));
        assertEquals(1000, PreferenceConfiguration.guideMenuHoldMs("1000"));
        assertEquals(1500, PreferenceConfiguration.guideMenuHoldMs("1500"));
        assertEquals(2000, PreferenceConfiguration.guideMenuHoldMs("2000"));
        assertEquals(0, PreferenceConfiguration.guideMenuHoldMs("750"));
        assertEquals(0, PreferenceConfiguration.guideMenuHoldMs("abc"));
    }
}
