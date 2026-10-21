package com.limelight.settings;

import androidx.annotation.NonNull;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * The words the settings search treats as meaning the same thing: "rumble" finds the vibration
 * settings, "drift" the deadzone, "lag" the latency ones.
 *
 * <p>"Search by meaning" is this table, nothing more: no model and no network. Each group is
 * bidirectional, so every term finds every other term in its group. Terms are written folded
 * ({@link SettingsSearch#fold(String)}: lower case, no accents, words split by single spaces), and
 * a term may be a phrase ("dead zone"), which the search keeps together as one token when the
 * query holds it.
 *
 * <p>Pure: no Android types, so the search is tested on the JVM.
 */
final class SettingsSynonyms {

    private static final String[][] GROUPS = {
            {"rumble", "vibration", "vibrate", "haptic"},
            {"deadzone", "dead zone", "drift"},
            {"latency", "lag", "delay"},
            {"hdr", "high dynamic range"},
            {"fps", "frame rate", "framerate", "refresh"},
            {"bitrate", "quality", "mbps"},
            {"resolution", "4k", "1080p", "720p"},
            {"codec", "hevc", "h265", "av1", "h264", "format"},
            {"mouse", "cursor", "pointer", "trackpad", "touchpad"},
            {"keyboard", "keys", "qwerty"},
            {"controller", "gamepad", "joystick", "pad"},
            {"companion", "bottom screen", "second screen", "dual screen"},
            {"overlay", "hud", "performance", "stats"},
            {"audio", "sound", "surround"},
            {"clipboard", "copy", "paste"},
            {"reconnect", "timeout", "disconnect"},
            {"help", "guide", "wiki", "troubleshooting"},
    };

    // Every term of more than one word, longest first, so the tokenizer takes "second screen"
    // whole before "screen" alone could match anything.
    private static final List<String> PHRASES;

    static {
        List<String> phrases = new ArrayList<>();
        for (String[] group : GROUPS) {
            for (String term : group) {
                if (term.indexOf(' ') >= 0) {
                    phrases.add(term);
                }
            }
        }
        Collections.sort(phrases, new Comparator<String>() {
            @Override
            public int compare(String a, String b) {
                return b.length() - a.length();
            }
        });
        PHRASES = Collections.unmodifiableList(phrases);
    }

    private SettingsSynonyms() {
    }

    /** The other terms that mean what {@code term} means, in table order; empty for none. */
    @NonNull
    static Set<String> of(@NonNull String term) {
        Set<String> out = new LinkedHashSet<>();
        for (String[] group : GROUPS) {
            boolean member = false;
            for (String t : group) {
                if (t.equals(term)) {
                    member = true;
                    break;
                }
            }
            if (member) {
                for (String t : group) {
                    if (!t.equals(term)) {
                        out.add(t);
                    }
                }
            }
        }
        return out;
    }

    /** The multi-word terms, longest first. */
    @NonNull
    static List<String> phrases() {
        return PHRASES;
    }
}
