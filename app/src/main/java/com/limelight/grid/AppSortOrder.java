package com.limelight.grid;

import com.limelight.R;
import com.limelight.nvstream.http.AppMetadata;
import com.limelight.nvstream.http.NvApp;
import com.limelight.utils.AppLaunchHistory;

import java.util.Comparator;
import java.util.Map;

/**
 * The orders the library can be sorted in.
 *
 * <p>Only {@link #HOST} and {@link #NAME} work off the Moonlight protocol alone. The rest lean on
 * data that may be missing — Playnite metadata the host only serves for some apps, or launches this
 * device has never seen — so every comparator falls back to the name for anything it cannot rank.
 * A game without metadata sorts among its peers alphabetically rather than being swept to the end.
 */
public enum AppSortOrder {
    /** The order the host lists its apps in, i.e. the IDX it reports. This is the default. */
    HOST("host", R.string.sort_order_host),

    /** Alphabetical by name, case-insensitive. */
    NAME("name", R.string.sort_order_name),

    /**
     * Most recently played first, by Playnite's last-played where the host serves it (PC sessions
     * included), falling back to this device's own launch record. Never-played games follow, by name.
     */
    RECENT("recent", R.string.sort_order_recent),

    /** Newest release first, for the games the host gave a release date. */
    RELEASE("release", R.string.sort_order_release),

    /** Highest community score first, for the games the host gave one. */
    SCORE("score", R.string.sort_order_score);

    private final String key;
    private final int labelRes;

    AppSortOrder(String key, int labelRes) {
        this.key = key;
        this.labelRes = labelRes;
    }

    /** The stable identifier this order is persisted under; never the enum name or ordinal. */
    public String getKey() {
        return key;
    }

    public int getLabelRes() {
        return labelRes;
    }

    public static AppSortOrder fromKey(String key, AppSortOrder fallback) {
        if (key != null) {
            for (AppSortOrder order : values()) {
                if (order.key.equals(key)) {
                    return order;
                }
            }
        }
        return fallback;
    }

    /**
     * Ordered on the bare {@link NvApp} rather than on any screen's list item, so the same rule
     * serves every list that shows games (the grid, the shelves, See all) without each one
     * wrapping its items into another's type first.
     *
     * @param metadata Playnite metadata keyed by upper-cased app UUID; empty on stock hosts.
     * @param history  this device's launch record; null when it is not available.
     */
    public Comparator<NvApp> comparator(final Map<String, AppMetadata> metadata,
                                        final AppLaunchHistory history) {
        switch (this) {
            case NAME:
                return byName();

            case RECENT:
                return (lhs, rhs) -> {
                    long lTime = lastPlayed(lhs, metadata, history);
                    long rTime = lastPlayed(rhs, metadata, history);
                    if (lTime == rTime) {
                        return compareNames(lhs, rhs);
                    }
                    if (lTime == 0 || rTime == 0) {
                        // Played games come before never-played ones.
                        return lTime == 0 ? 1 : -1;
                    }
                    // Most recent first, so the descending comparison.
                    return Long.compare(rTime, lTime);
                };

            case RELEASE:
                return (lhs, rhs) -> {
                    String lDate = releaseDate(metadata, lhs);
                    String rDate = releaseDate(metadata, rhs);
                    if (lDate.isEmpty() || rDate.isEmpty() || lDate.equals(rDate)) {
                        // One of them has no date to rank, or they share one: name decides.
                        if (lDate.isEmpty() != rDate.isEmpty()) {
                            // Dated games come before undated ones.
                            return lDate.isEmpty() ? 1 : -1;
                        }
                        return compareNames(lhs, rhs);
                    }
                    // ISO8601 dates sort correctly as strings; newest first.
                    return rDate.compareTo(lDate);
                };

            case SCORE:
                return (lhs, rhs) -> {
                    int lScore = communityScore(metadata, lhs);
                    int rScore = communityScore(metadata, rhs);
                    if (lScore == rScore) {
                        return compareNames(lhs, rhs);
                    }
                    if (lScore == AppMetadata.NO_SCORE || rScore == AppMetadata.NO_SCORE) {
                        // Scored games come before unscored ones.
                        return lScore == AppMetadata.NO_SCORE ? 1 : -1;
                    }
                    return Integer.compare(rScore, lScore);
                };

            case HOST:
            default:
                return (lhs, rhs) -> {
                    int lIndex = lhs.getAppIndex();
                    int rIndex = rhs.getAppIndex();
                    if (lIndex == rIndex) {
                        return compareNames(lhs, rhs);
                    }
                    return lIndex - rIndex;
                };
        }
    }

    private static Comparator<NvApp> byName() {
        return AppSortOrder::compareNames;
    }

    private static int compareNames(NvApp lhs, NvApp rhs) {
        return lhs.getAppName().toLowerCase().compareTo(rhs.getAppName().toLowerCase());
    }

    private static AppMetadata metadataFor(Map<String, AppMetadata> metadata, NvApp app) {
        String uuid = app.getAppUUID();
        if (metadata == null || uuid == null || uuid.isEmpty()) {
            return null;
        }
        return metadata.get(uuid.toUpperCase());
    }

    /**
     * When the game was last played, in milliseconds since the epoch, or 0 when it never was as far
     * as anyone here knows. Public because "was it ever played" is also a filter, not only an
     * order: a "Recently played" shelf must agree with {@link #RECENT} on which games qualify.
     *
     * <p>Most-recent wins. Playnite's own figure leads because it sees sessions played at the PC
     * itself; this device's launch record is the fallback for games the host does not date (a stock
     * host, or a game Playnite does not track), and the later of the two wins where both exist so a
     * launch from here is never hidden by a stale host timestamp.
     *
     * @param metadata Playnite metadata keyed by upper-cased app UUID; may be null or empty.
     * @param history  this device's launch record; may be null.
     */
    public static long lastPlayed(NvApp app, Map<String, AppMetadata> metadata,
                                  AppLaunchHistory history) {
        AppMetadata meta = metadataFor(metadata, app);
        long hostTime = meta == null ? 0 : meta.getLastPlayedEpochMillis();
        long localTime = history == null ? 0 : history.getLastPlayed(app);
        return Math.max(hostTime, localTime);
    }

    private static String releaseDate(Map<String, AppMetadata> metadata, NvApp app) {
        AppMetadata meta = metadataFor(metadata, app);
        return meta == null || meta.getReleaseDate() == null ? "" : meta.getReleaseDate();
    }

    private static int communityScore(Map<String, AppMetadata> metadata, NvApp app) {
        AppMetadata meta = metadataFor(metadata, app);
        return meta == null ? AppMetadata.NO_SCORE : meta.getCommunityScore();
    }
}
