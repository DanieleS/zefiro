package com.limelight.grid;

import com.limelight.nvstream.http.AppMetadata;
import com.limelight.nvstream.http.NvApp;
import com.limelight.utils.AppLaunchHistory;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public class AppSortOrderTest {

    private final Map<String, AppMetadata> metadata = new HashMap<>();
    private final AppLaunchHistory history = mock(AppLaunchHistory.class);

    private static NvApp app(String name, String uuid, int id) {
        return new NvApp(name, uuid, id, false);
    }

    private void hostPlayed(NvApp app, long epochMillis) {
        AppMetadata meta = mock(AppMetadata.class);
        when(meta.getLastPlayedEpochMillis()).thenReturn(epochMillis);
        // The comparator looks metadata up by the upper-cased UUID, as NvHTTP stores it.
        metadata.put(app.getAppUUID().toUpperCase(), meta);
    }

    private void locallyPlayed(NvApp app, long epochMillis) {
        when(history.getLastPlayed(app)).thenReturn(epochMillis);
    }

    private List<String> sortedNames(AppSortOrder order, NvApp... apps) {
        List<NvApp> list = new ArrayList<>(Arrays.asList(apps));
        Collections.sort(list, order.comparator(metadata, history));
        List<String> names = new ArrayList<>();
        for (NvApp app : list) {
            names.add(app.getAppName());
        }
        return names;
    }

    @Test
    public void recentPutsTheMostRecentlyPlayedFirst() {
        NvApp old = app("Old", "aaaa", 1);
        NvApp newer = app("Newer", "bbbb", 2);
        NvApp newest = app("Newest", "cccc", 3);
        hostPlayed(old, 1_000L);
        hostPlayed(newer, 2_000L);
        locallyPlayed(newest, 3_000L);

        assertEquals(Arrays.asList("Newest", "Newer", "Old"),
                sortedNames(AppSortOrder.RECENT, old, newer, newest));
    }

    @Test
    public void recentPutsNeverPlayedGamesLastByName() {
        NvApp played = app("Zelda", "aaaa", 1);
        NvApp neverB = app("beta", "bbbb", 2);
        NvApp neverA = app("Alpha", "cccc", 3);
        hostPlayed(played, 5_000L);

        // Case-insensitive name order among the never-played, after every played game.
        assertEquals(Arrays.asList("Zelda", "Alpha", "beta"),
                sortedNames(AppSortOrder.RECENT, neverB, played, neverA));
    }

    @Test
    public void lastPlayedIsTheLaterOfHostAndLocalHistory() {
        NvApp hostLater = app("HostLater", "aaaa", 1);
        NvApp localLater = app("LocalLater", "bbbb", 2);
        hostPlayed(hostLater, 9_000L);
        locallyPlayed(hostLater, 4_000L);
        hostPlayed(localLater, 2_000L);
        locallyPlayed(localLater, 7_000L);

        assertEquals(9_000L, AppSortOrder.lastPlayed(hostLater, metadata, history));
        // A launch from this device is not hidden by an older host timestamp.
        assertEquals(7_000L, AppSortOrder.lastPlayed(localLater, metadata, history));
        assertEquals(Arrays.asList("HostLater", "LocalLater"),
                sortedNames(AppSortOrder.RECENT, localLater, hostLater));
    }

    @Test
    public void lastPlayedIsZeroWithoutAnySource() {
        NvApp unknown = app("Unknown", "dddd", 4);
        assertEquals(0L, AppSortOrder.lastPlayed(unknown, null, null));
        assertEquals(0L, AppSortOrder.lastPlayed(unknown, metadata, history));
        // No UUID: the metadata cannot be looked up, and that is not an error.
        assertEquals(0L, AppSortOrder.lastPlayed(app("NoUuid", null, 5), metadata, null));
    }
}
