package com.limelight.binding.video;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;
import java.util.Collections;

import org.junit.Test;

public class SnapdragonDeclaredExtrasTest {
    @Test
    public void onlyDeclaredKeysAreReturned() {
        assertEquals(Arrays.asList("vendor.qti-ext-dec-info-misr.disable",
                        "vendor.qti-ext-dec-error-correction.conceal"),
                SnapdragonDeclaredExtras.declaredIn(Arrays.asList(
                        "vendor.qti-ext-dec-low-latency.enable",
                        "vendor.qti-ext-dec-error-correction.conceal",
                        "vendor.qti-ext-dec-info-misr.disable")));
    }

    @Test
    public void matchingIgnoresCase() {
        assertEquals(Collections.singletonList("vendor.qti-ext-dec-instant-decode.enable"),
                SnapdragonDeclaredExtras.declaredIn(
                        Collections.singletonList("Vendor.QTI-ext-dec-instant-decode.enable")));
    }

    @Test
    public void nothingDeclaredMeansNothingSet() {
        assertTrue(SnapdragonDeclaredExtras.declaredIn(null).isEmpty());
        assertTrue(SnapdragonDeclaredExtras.declaredIn(Collections.<String>emptyList()).isEmpty());
        assertTrue(SnapdragonDeclaredExtras.declaredIn(
                Arrays.asList("vendor.qti-ext-dec-low-latency.enable")).isEmpty());
    }
}
