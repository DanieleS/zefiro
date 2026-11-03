package com.limelight.stream;

import androidx.annotation.NonNull;

import com.limelight.stream.StreamCapabilities.ResolutionChoice;

import java.util.ArrayList;
import java.util.List;

/**
 * The sizes the Resolution page offers: 720p, 1080p and 1440p, and the screen's own size ("Native").
 * Pure, so the rules are checked off the device.
 *
 * <p>Sizes are given in the orientation of the stream that is running, a portrait stream getting
 * the presets turned on their side. The native size is rounded down to even numbers, which is
 * what a host takes, and left out when it is one of the presets already.
 */
public final class StreamResolutions {

    private static final int[][] PRESETS = {
            {1280, 720},
            {1920, 1080},
            {2560, 1440},
    };

    private StreamResolutions() {
    }

    /** The preset's name: its height and a p. */
    @NonNull
    static String presetName(int shortSide) {
        return shortSide + "p";
    }

    /**
     * @param streamWidth  the size of the stream now
     * @param nativeWidth  the screen's size in pixels, whichever way it is turned; 0 when unknown
     */
    @NonNull
    public static List<ResolutionChoice> choices(int streamWidth, int streamHeight,
                                                 int nativeWidth, int nativeHeight) {
        List<ResolutionChoice> choices = new ArrayList<>();
        boolean portrait = streamHeight > streamWidth;
        for (int[] preset : PRESETS) {
            int width = portrait ? preset[1] : preset[0];
            int height = portrait ? preset[0] : preset[1];
            choices.add(new ResolutionChoice(width, height, presetName(preset[1]),
                    width == streamWidth && height == streamHeight));
        }
        if (nativeWidth > 0 && nativeHeight > 0) {
            int longSide = Math.max(nativeWidth, nativeHeight) & ~1;
            int shortSide = Math.min(nativeWidth, nativeHeight) & ~1;
            int width = portrait ? shortSide : longSide;
            int height = portrait ? longSide : shortSide;
            boolean isPreset = false;
            for (ResolutionChoice choice : choices) {
                isPreset |= choice.width == width && choice.height == height;
            }
            if (!isPreset && shortSide > 0) {
                choices.add(new ResolutionChoice(width, height, null,
                        width == streamWidth && height == streamHeight));
            }
        }
        return choices;
    }

    /** The two sides of a choice in one number, for a menu entry's argument. */
    public static int pack(int width, int height) {
        return (width << 16) | (height & 0xFFFF);
    }

    public static int unpackWidth(int packed) {
        return packed >>> 16;
    }

    public static int unpackHeight(int packed) {
        return packed & 0xFFFF;
    }
}
