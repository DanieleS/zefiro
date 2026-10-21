package com.limelight.computers;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.limelight.LimeLog;
import com.limelight.library.LibraryModel;
import com.limelight.nvstream.http.NvApp;
import com.limelight.nvstream.http.NvHTTP;

import org.xmlpull.v1.XmlPullParserException;

import java.io.IOException;
import java.io.StringReader;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * How many games a host has, as the PCs section shows it: the games the Library would list for
 * that host, and no others.
 *
 * <p>That is the host's list without its specials (the virtual display and the remote session
 * entries, which the Library offers as actions, not covers), and without the games the user hid.
 * Counting the raw list instead would give a number the Library never shows.
 *
 * <h3>The memo</h3>
 *
 * Every host is polled every few seconds, and its list rarely changes, so parsing it on every
 * poll is wasted work. The last answer is kept per host with the list it was computed from and
 * the hidden ids at the time; a poll that changed neither gets that answer back without a parse.
 * The list is compared with {@code equals}, which costs a walk of the string at most, and nothing
 * when the service hands out the same instance again.
 *
 * <p>Thread-safe. {@link #countFor} parses, so keep it off the main thread.
 */
public final class GameCount {

    /** Turns a host's raw app list into apps. Swapped in tests, to count the parses. */
    interface Parser {
        List<NvApp> parse(String rawAppList) throws XmlPullParserException, IOException;
    }

    private static final class Memo {
        final String rawAppList;
        final Set<Integer> hiddenIds;
        final int count;

        Memo(String rawAppList, Set<Integer> hiddenIds, int count) {
            this.rawAppList = rawAppList;
            this.hiddenIds = hiddenIds;
            this.count = count;
        }
    }

    private final Parser parser;

    // Guarded by this.
    private final Map<String, Memo> memos = new HashMap<>();

    public GameCount() {
        this(raw -> NvHTTP.getAppListByReader(new StringReader(raw)));
    }

    GameCount(Parser parser) {
        this.parser = parser;
    }

    /**
     * The games of a host list the Library would show: the list less its specials, less the
     * hidden ids. A hidden id that is not in the list takes nothing away.
     *
     * @param hostList  everything the host listed, specials included
     * @param hiddenIds the host's hidden ids; null for none
     */
    public static int count(@NonNull List<NvApp> hostList, @Nullable Set<Integer> hiddenIds) {
        int count = 0;
        for (NvApp game : LibraryModel.reduce(Collections.<NvApp>emptyList(), hostList).games) {
            if (hiddenIds == null || !hiddenIds.contains(game.getAppId())) {
                count++;
            }
        }
        return count;
    }

    /**
     * The count for one host's raw app list, parsed only when the list or the hidden ids changed
     * since the last call for that host.
     *
     * @return the count, or {@link HostEntry#UNKNOWN_GAME_COUNT} for a host with no list, an
     *         empty answer, or one that does not parse
     */
    public synchronized int countFor(@NonNull String uuid, @Nullable String rawAppList,
                                     @NonNull Set<Integer> hiddenIds) {
        if (rawAppList == null || rawAppList.isEmpty()) {
            memos.remove(uuid);
            return HostEntry.UNKNOWN_GAME_COUNT;
        }

        Memo memo = memos.get(uuid);
        if (memo != null && memo.rawAppList.equals(rawAppList) && memo.hiddenIds.equals(hiddenIds)) {
            return memo.count;
        }

        int count;
        try {
            count = count(parser.parse(rawAppList), hiddenIds);
        } catch (XmlPullParserException | IOException e) {
            // Remembered like a good answer, so a broken list is not parsed again every poll.
            LimeLog.warning("Unable to count a host's games: " + e.getMessage());
            count = HostEntry.UNKNOWN_GAME_COUNT;
        }
        memos.put(uuid, new Memo(rawAppList, new HashSet<>(hiddenIds), count));
        return count;
    }

    /** Drops what is remembered for a host, which the user removed. */
    public synchronized void forget(@NonNull String uuid) {
        memos.remove(uuid);
    }
}
