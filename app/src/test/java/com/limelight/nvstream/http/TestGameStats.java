package com.limelight.nvstream.http;

import org.json.JSONException;
import org.json.JSONObject;

/**
 * Builds a game's {@link GameStats} and {@link GameAchievements}, and a period's
 * {@link AppStats}, the way the host would send them, for tests outside this package, whose
 * parsers are package-private.
 */
public final class TestGameStats {
    private TestGameStats() {
    }

    /** {@code /appstats?appuuid=} as the host sends it. */
    public static GameStats stats(JSONObject json) {
        return GameStats.fromJson(json);
    }

    /** {@code /appstats?range=&offset=}, a period's page, as the host sends it. */
    public static AppStats appStats(JSONObject json) {
        return AppStats.fromJson(json);
    }

    /** {@code /appachievements} as the host sends it. */
    public static GameAchievements achievements(JSONObject json) {
        return GameAchievements.fromJson(json);
    }

    /** One achievement from its JSON, every field the host may send. */
    public static Achievement achievement(JSONObject json) {
        return Achievement.fromJson(json);
    }

    /** A JSONObject from alternating keys and values, without the checked exception. */
    public static JSONObject json(Object... keysAndValues) {
        try {
            JSONObject json = new JSONObject();
            for (int i = 0; i + 1 < keysAndValues.length; i += 2) {
                json.put((String) keysAndValues[i], keysAndValues[i + 1]);
            }
            return json;
        } catch (JSONException e) {
            throw new AssertionError(e);
        }
    }
}
