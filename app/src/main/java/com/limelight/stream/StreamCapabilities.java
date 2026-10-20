package com.limelight.stream;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.limelight.utils.KeyConfigHelper;
import com.limelight.utils.KeyMapper;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * What the stream can do right now, as {@link StreamActions#build} needs it: one snapshot taken
 * from {@code Game} when the controls open, so the tree is a pure function of plain values and
 * every visibility rule can be checked off the device.
 *
 * <p>Taken fresh on each open, never kept: the companion can come and go, the bitrate moves, and
 * a stale snapshot would offer what is no longer there.
 */
public final class StreamCapabilities {

    /** A gamepad's own entry (its mouse emulation), as the device offered it. */
    public static final class DeviceOption {
        @NonNull
        public final String label;
        public final boolean withGameFocus;

        public DeviceOption(@NonNull String label, boolean withGameFocus) {
            this.label = label;
            this.withGameFocus = withGameFocus;
        }
    }

    /** A shortcut the user imported in the settings: a name and the keys it presses. */
    public static final class CustomKey {
        @NonNull
        public final String name;
        @NonNull
        final short[] keys;

        public CustomKey(@NonNull String name, @NonNull short[] keys) {
            this.name = name;
            this.keys = keys.clone();
        }
    }

    /** {@link #parseCustomKeys}'s answer: the shortcuts, and whether the stored text was bad. */
    public static final class CustomKeys {
        @NonNull
        public final List<CustomKey> keys;
        public final boolean invalid;

        CustomKeys(@NonNull List<CustomKey> keys, boolean invalid) {
            this.keys = keys;
            this.invalid = invalid;
        }
    }

    public final boolean captureEnabled;

    /** Whether the host can change the bitrate mid-stream; the fields below mean nothing if not. */
    public final boolean liveBitrate;
    public final boolean bitrateAdaptive;
    public final int bitrateCurrentKbps;
    public final int bitrateChosenKbps;
    /** For printing bitrates, as the stream's resources would. */
    @NonNull
    public final Locale locale;

    public final boolean allowChangeMouseMode;
    public final boolean onExternalDisplay;
    public final boolean companionConfigured;
    public final boolean companionShowing;
    public final boolean companionAppSupported;
    /** Only where the controls are drawn over the game itself; not from the external display's phone. */
    public final boolean canRotate;
    public final boolean zoomEnabled;
    public final boolean hudVisible;
    public final boolean virtualControllerShown;

    @NonNull
    public final List<String> serverCmds;
    /** Empty unless the controls were opened from a gamepad. */
    @NonNull
    public final List<DeviceOption> deviceOptions;

    /** The fourteen built-in key combos; off when the user cleared them in the settings. */
    public final boolean defaultSpecialKeys;
    @NonNull
    public final List<CustomKey> customKeys;
    /** The stored shortcuts would not parse; the router says so when Special keys opens. */
    public final boolean customKeysInvalid;

    private StreamCapabilities(Builder b) {
        captureEnabled = b.captureEnabled;
        liveBitrate = b.liveBitrate;
        bitrateAdaptive = b.bitrateAdaptive;
        bitrateCurrentKbps = b.bitrateCurrentKbps;
        bitrateChosenKbps = b.bitrateChosenKbps;
        locale = b.locale;
        allowChangeMouseMode = b.allowChangeMouseMode;
        onExternalDisplay = b.onExternalDisplay;
        companionConfigured = b.companionConfigured;
        companionShowing = b.companionShowing;
        companionAppSupported = b.companionAppSupported;
        canRotate = b.canRotate;
        zoomEnabled = b.zoomEnabled;
        hudVisible = b.hudVisible;
        virtualControllerShown = b.virtualControllerShown;
        serverCmds = Collections.unmodifiableList(new ArrayList<>(b.serverCmds));
        deviceOptions = Collections.unmodifiableList(new ArrayList<>(b.deviceOptions));
        defaultSpecialKeys = b.defaultSpecialKeys;
        customKeys = Collections.unmodifiableList(new ArrayList<>(b.customKeys));
        customKeysInvalid = b.customKeysInvalid;
    }

    /**
     * The shortcuts stored by the settings' import (specialPrefs/special_key). Each key is a
     * literal ("0x1B") or a {@link KeyMapper} constant's name ("VK_ESCAPE"). Parsing stops at the
     * first bad key, keeping the shortcuts before it, as the menu always has; {@code invalid}
     * then says the list is short.
     */
    @NonNull
    public static CustomKeys parseCustomKeys(@Nullable String json) {
        List<CustomKey> result = new ArrayList<>();
        if (json == null || json.isEmpty()) {
            return new CustomKeys(result, false);
        }
        try {
            KeyConfigHelper.ShortcutFile file = KeyConfigHelper.parseShortcutFile(json);
            if (file == null || file.data == null) {
                return new CustomKeys(result, false);
            }
            for (KeyConfigHelper.Shortcut sc : file.data) {
                short[] codes = new short[sc.keys.size()];
                for (int i = 0; i < codes.length; i++) {
                    codes[i] = (short) keyCode(sc.keys.get(i));
                }
                result.add(new CustomKey(sc.name, codes));
            }
            return new CustomKeys(result, false);
        } catch (Exception e) {
            return new CustomKeys(result, true);
        }
    }

    private static int keyCode(String code) throws ReflectiveOperationException {
        if (code.startsWith("0x")) {
            return Integer.parseInt(code.substring(2), 16);
        }
        if (code.startsWith("VK_")) {
            Field field = KeyMapper.class.getDeclaredField(code);
            return field.getInt(null);
        }
        throw new IllegalArgumentException("Unknown key code: " + code);
    }

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private boolean captureEnabled;
        private boolean liveBitrate;
        private boolean bitrateAdaptive;
        private int bitrateCurrentKbps;
        private int bitrateChosenKbps;
        private Locale locale = Locale.getDefault();
        private boolean allowChangeMouseMode;
        private boolean onExternalDisplay;
        private boolean companionConfigured;
        private boolean companionShowing;
        private boolean companionAppSupported;
        private boolean canRotate;
        private boolean zoomEnabled;
        private boolean hudVisible;
        private boolean virtualControllerShown;
        private List<String> serverCmds = Collections.emptyList();
        private List<DeviceOption> deviceOptions = Collections.emptyList();
        private boolean defaultSpecialKeys = true;
        private List<CustomKey> customKeys = Collections.emptyList();
        private boolean customKeysInvalid;

        private Builder() {
        }

        public Builder captureEnabled(boolean v) {
            captureEnabled = v;
            return this;
        }

        /** The host can change the bitrate live; its state now and the one the settings chose. */
        public Builder liveBitrate(boolean adaptive, int currentKbps, int chosenKbps) {
            liveBitrate = true;
            bitrateAdaptive = adaptive;
            bitrateCurrentKbps = currentKbps;
            bitrateChosenKbps = chosenKbps;
            return this;
        }

        public Builder locale(@NonNull Locale v) {
            locale = v;
            return this;
        }

        public Builder allowChangeMouseMode(boolean v) {
            allowChangeMouseMode = v;
            return this;
        }

        public Builder onExternalDisplay(boolean v) {
            onExternalDisplay = v;
            return this;
        }

        public Builder companion(boolean configured, boolean showing) {
            companionConfigured = configured;
            companionShowing = showing;
            return this;
        }

        public Builder companionAppSupported(boolean v) {
            companionAppSupported = v;
            return this;
        }

        public Builder canRotate(boolean v) {
            canRotate = v;
            return this;
        }

        public Builder zoomEnabled(boolean v) {
            zoomEnabled = v;
            return this;
        }

        public Builder hudVisible(boolean v) {
            hudVisible = v;
            return this;
        }

        public Builder virtualControllerShown(boolean v) {
            virtualControllerShown = v;
            return this;
        }

        public Builder serverCmds(@Nullable List<String> v) {
            serverCmds = v == null ? Collections.emptyList() : v;
            return this;
        }

        public Builder deviceOptions(@NonNull List<DeviceOption> v) {
            deviceOptions = v;
            return this;
        }

        public Builder defaultSpecialKeys(boolean v) {
            defaultSpecialKeys = v;
            return this;
        }

        public Builder customKeys(@NonNull CustomKeys v) {
            customKeys = v.keys;
            customKeysInvalid = v.invalid;
            return this;
        }

        public StreamCapabilities build() {
            return new StreamCapabilities(this);
        }
    }

}
