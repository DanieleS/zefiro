package com.limelight.stats;

import android.os.Bundle;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.Objects;

/**
 * Which game a game page is about, and where it was opened from, as the page's fragment
 * arguments.
 *
 * <p>A page is opened from places that know the game differently: Stats and the unlock toast
 * by its Playnite UUID only (statistics speak in UUIDs), the Library by its app. The UUID is what
 * the host's {@code /appstats?appuuid=} and {@code /appachievements} are asked with, so it is
 * always there; the app id is 0 when the opener could not find the game in the library, and the
 * page then looks it up itself, by UUID, in every snapshot it is given.
 *
 * <p>Immutable. It travels as a {@link Bundle}, so a restored page knows its game again.
 */
public final class GamePageArgs {
    private static final String KEY_HOST = "gamePage.host";
    private static final String KEY_APP_UUID = "gamePage.appUuid";
    private static final String KEY_APP_ID = "gamePage.appId";
    private static final String KEY_NAME = "gamePage.name";
    private static final String KEY_ORIGIN = "gamePage.origin";

    @NonNull
    public final String hostUuid;
    @NonNull
    public final String appUuid;
    /** The host's id for the game ({@code NvApp.getAppId()}); 0 when unknown. */
    public final int appId;
    @NonNull
    public final String name;
    /**
     * The crumb beside the back arrow: where back returns to ("Stats · This month"). Empty from
     * an opener that cannot know where the page lands (the actions sheet, the unlock toast):
     * {@code MainHost.openGamePage} then names the section it pushes the page on.
     */
    @NonNull
    public final String originLabel;

    public GamePageArgs(@NonNull String hostUuid, @NonNull String appUuid, int appId,
                        @Nullable String name, @Nullable String originLabel) {
        this.hostUuid = hostUuid;
        this.appUuid = appUuid;
        this.appId = appId;
        this.name = name != null ? name : "";
        this.originLabel = originLabel != null ? originLabel : "";
    }

    /** The same game with another crumb. */
    @NonNull
    public GamePageArgs withOrigin(@Nullable String origin) {
        return new GamePageArgs(hostUuid, appUuid, appId, name, origin);
    }

    /**
     * Whether both are about the same game of the same host, wherever they were opened from. By
     * UUID when both know it; by app id otherwise (the Library's game without a Playnite UUID).
     */
    public boolean sameGame(@NonNull GamePageArgs other) {
        if (!hostUuid.equalsIgnoreCase(other.hostUuid)) {
            return false;
        }
        if (!appUuid.isEmpty() && !other.appUuid.isEmpty()) {
            return appUuid.equalsIgnoreCase(other.appUuid);
        }
        return appId != 0 && appId == other.appId;
    }

    @NonNull
    public Bundle toBundle() {
        Bundle bundle = new Bundle();
        bundle.putString(KEY_HOST, hostUuid);
        bundle.putString(KEY_APP_UUID, appUuid);
        bundle.putInt(KEY_APP_ID, appId);
        bundle.putString(KEY_NAME, name);
        bundle.putString(KEY_ORIGIN, originLabel);
        return bundle;
    }

    /** The arguments a bundle carries, or null when it carries none (not a page's bundle). */
    @Nullable
    public static GamePageArgs fromBundle(@Nullable Bundle bundle) {
        if (bundle == null) {
            return null;
        }
        String host = bundle.getString(KEY_HOST);
        String uuid = bundle.getString(KEY_APP_UUID);
        if (host == null || uuid == null) {
            return null;
        }
        return new GamePageArgs(host, uuid, bundle.getInt(KEY_APP_ID),
                bundle.getString(KEY_NAME), bundle.getString(KEY_ORIGIN));
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof GamePageArgs)) {
            return false;
        }
        GamePageArgs that = (GamePageArgs) o;
        return appId == that.appId
                && hostUuid.equals(that.hostUuid)
                && appUuid.equals(that.appUuid)
                && name.equals(that.name)
                && originLabel.equals(that.originLabel);
    }

    @Override
    public int hashCode() {
        return Objects.hash(hostUuid, appUuid, appId, name, originLabel);
    }
}
