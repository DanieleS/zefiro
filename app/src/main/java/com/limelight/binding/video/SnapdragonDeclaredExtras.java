package com.limelight.binding.video;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.List;

/**
 * Snapdragon vendor keys that are set only when the decoder declares them in
 * getSupportedVendorParameters (Android 12 and newer), never by name, and on the first configure
 * try only: a Snapdragon key set by name has hung a Retroid before, and a decoder that rejects
 * one of them must still get the confirmed Qualcomm keys on the next tries.
 */
final class SnapdragonDeclaredExtras {
    /** Skip the per-frame MISR checksum, decode without output batching, conceal lost slices. */
    static final List<String> KEYS = Collections.unmodifiableList(Arrays.asList(
            "vendor.qti-ext-dec-info-misr.disable",
            "vendor.qti-ext-dec-instant-decode.enable",
            "vendor.qti-ext-dec-error-correction.conceal"));

    private SnapdragonDeclaredExtras() {}

    /** The KEYS (with the casing of our list) that appear among the decoder's declared parameters. */
    static List<String> declaredIn(Collection<String> declaredParams) {
        List<String> declared = new ArrayList<>();
        if (declaredParams == null) {
            return declared;
        }
        for (String key : KEYS) {
            for (String param : declaredParams) {
                if (key.equalsIgnoreCase(param)) {
                    declared.add(key);
                    break;
                }
            }
        }
        return declared;
    }
}
