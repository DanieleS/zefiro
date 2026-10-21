package com.limelight.settings;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Finds settings by what they are called, what they say about themselves and where they live,
 * directly or through {@link SettingsSynonyms}: "rumble" finds "Enable rumble" by its title and
 * "Force vibration on device" by meaning.
 *
 * <h3>Matching</h3>
 *
 * The query and every entry are folded the same way ({@link #fold}): lower case, accents
 * stripped, anything but letters and digits a word break. The query is cut into tokens, a
 * synonym phrase ("dead zone") kept whole, and an entry matches when <em>every</em> token is found
 * in its title, summary, subgroup or section: as written (anywhere in a word, so a half-typed
 * word already finds), or as one of the token's synonyms (at the start of a word, so a short
 * synonym like "pad" does not light up every word that contains it).
 *
 * <h3>Order</h3>
 *
 * <pre>
 *   0  the title starts with the token
 *   1  the title contains it
 *   2  the title has a synonym of it
 *   3  only the summary, subgroup or section has it, or a synonym of it
 * </pre>
 *
 * An entry's rank is the sum over its tokens. Results are grouped by section, as the list is;
 * groups go best first, rows within a group best first, and ties keep the screen's order.
 *
 * <h3>Filters</h3>
 *
 * The chips under the field narrow the results to one section, or to the settings that differ
 * from their defaults ({@link Filter}); the counts on the chips are of the query's matches before
 * that narrowing. With no query, Changed lists every changed setting and the others list nothing.
 *
 * <p>Pure and immutable: the entries are copied from the live settings by {@link SettingsIndex},
 * so this is tested on the JVM.
 */
public final class SettingsSearch {

    /** What a click on a result does, which decides what its row shows on the right. */
    public enum Kind { LIST, TOGGLE, SEEK, TEXT, ACTION }

    /** One setting as the search sees it. Immutable. */
    public static final class Entry {
        @NonNull
        public final String key;
        @NonNull
        public final String title;
        @NonNull
        public final String summary;
        @NonNull
        public final String sectionId;
        @NonNull
        public final String sectionTitle;
        @NonNull
        public final String subgroup;
        @NonNull
        public final Kind kind;
        public final boolean external;
        // The entry's place in the list, which breaks ties.
        final int order;

        final String foldedTitle;
        final String foldedSummary;
        final String foldedPlace;

        public Entry(@NonNull String key, @NonNull String title, @Nullable String summary,
                     @NonNull String sectionId, @NonNull String sectionTitle,
                     @NonNull String subgroup, @NonNull Kind kind, boolean external, int order) {
            this.key = key;
            this.title = title;
            this.summary = summary != null ? summary : "";
            this.sectionId = sectionId;
            this.sectionTitle = sectionTitle;
            this.subgroup = subgroup;
            this.kind = kind;
            this.external = external;
            this.order = order;
            this.foldedTitle = fold(title);
            this.foldedSummary = fold(this.summary);
            this.foldedPlace = fold(subgroup + " " + sectionTitle);
        }
    }

    /** Which results the chips let through: all, one section's, or only the changed ones. */
    public static final class Filter {
        public static final Filter ALL = new Filter(null, false);
        public static final Filter CHANGED = new Filter(null, true);

        /** The one section let through; null for every section. */
        @Nullable
        public final String sectionId;
        public final boolean changedOnly;

        private Filter(@Nullable String sectionId, boolean changedOnly) {
            this.sectionId = sectionId;
            this.changedOnly = changedOnly;
        }

        @NonNull
        public static Filter section(@NonNull String sectionId) {
            return new Filter(sectionId, false);
        }

        @Override
        public boolean equals(Object o) {
            if (!(o instanceof Filter)) {
                return false;
            }
            Filter that = (Filter) o;
            return changedOnly == that.changedOnly
                    && (sectionId == null ? that.sectionId == null : sectionId.equals(that.sectionId));
        }

        @Override
        public int hashCode() {
            return (sectionId != null ? sectionId.hashCode() : 0) * 31 + (changedOnly ? 1 : 0);
        }
    }

    /** The results of one section, under its header. */
    public static final class Group {
        @NonNull
        public final String sectionId;
        @NonNull
        public final String sectionTitle;
        @NonNull
        public final List<Entry> entries;

        Group(@NonNull String sectionId, @NonNull String sectionTitle, @NonNull List<Entry> entries) {
            this.sectionId = sectionId;
            this.sectionTitle = sectionTitle;
            this.entries = Collections.unmodifiableList(entries);
        }
    }

    /** What a search found. Immutable. */
    public static final class Result {
        /** The groups the filter lets through, best first. */
        @NonNull
        public final List<Group> groups;
        /** How many settings the query matched, before the filter narrowed them. */
        public final int total;
        /** The query's matches per section id, before the filter, in the groups' order. */
        @NonNull
        public final Map<String, Integer> sectionCounts;
        /** The section titles behind {@link #sectionCounts}' ids. */
        @NonNull
        public final Map<String, String> sectionTitles;
        /** The synonyms that brought in a result the query's own words did not. */
        @NonNull
        public final List<String> usedSynonyms;

        Result(@NonNull List<Group> groups, int total, @NonNull Map<String, Integer> sectionCounts,
               @NonNull Map<String, String> sectionTitles, @NonNull List<String> usedSynonyms) {
            this.groups = Collections.unmodifiableList(groups);
            this.total = total;
            this.sectionCounts = Collections.unmodifiableMap(sectionCounts);
            this.sectionTitles = Collections.unmodifiableMap(sectionTitles);
            this.usedSynonyms = Collections.unmodifiableList(usedSynonyms);
        }

        /** The rows shown, across the groups. */
        public int size() {
            int n = 0;
            for (Group g : groups) {
                n += g.entries.size();
            }
            return n;
        }

        /** Every row shown, in order. */
        @NonNull
        public List<Entry> entries() {
            List<Entry> out = new ArrayList<>();
            for (Group g : groups) {
                out.addAll(g.entries);
            }
            return out;
        }
    }

    private static final int TITLE_PREFIX = 0;
    private static final int TITLE_CONTAINS = 1;
    private static final int TITLE_SYNONYM = 2;
    private static final int ELSEWHERE = 3;
    private static final int NO_MATCH = -1;

    private final List<Entry> entries;

    public SettingsSearch(@NonNull List<Entry> entries) {
        this.entries = Collections.unmodifiableList(new ArrayList<>(entries));
    }

    /** How many settings there are to search: the search field's "Search 144 settings". */
    public int size() {
        return entries.size();
    }

    @NonNull
    public List<Entry> entries() {
        return entries;
    }

    /**
     * @param changedKeys the keys whose value differs from the default, for {@link Filter#CHANGED}
     */
    @NonNull
    public Result search(@Nullable String query, @NonNull Filter filter,
                         @NonNull Set<String> changedKeys) {
        List<String> tokens = tokens(fold(query != null ? query : ""));
        Set<String> used = new LinkedHashSet<>();
        List<Scored> matched = new ArrayList<>();
        if (tokens.isEmpty()) {
            if (filter.changedOnly) {
                for (Entry e : entries) {
                    if (changedKeys.contains(e.key)) {
                        matched.add(new Scored(e, 0));
                    }
                }
            }
        } else {
            for (Entry e : entries) {
                Set<String> synonyms = new LinkedHashSet<>();
                int score = score(e, tokens, synonyms);
                if (score != NO_MATCH) {
                    matched.add(new Scored(e, score));
                    used.addAll(synonyms);
                }
            }
        }

        Map<String, Integer> counts = new LinkedHashMap<>();
        Map<String, String> titles = new LinkedHashMap<>();
        List<Group> groups = group(matched);
        for (Group g : groups) {
            counts.put(g.sectionId, g.entries.size());
            titles.put(g.sectionId, g.sectionTitle);
        }
        int total = matched.size();

        List<Scored> shown = new ArrayList<>();
        for (Scored s : matched) {
            if (filter.changedOnly && !changedKeys.contains(s.entry.key)) {
                continue;
            }
            if (filter.sectionId != null && !filter.sectionId.equals(s.entry.sectionId)) {
                continue;
            }
            shown.add(s);
        }
        // A synonym only counts as used if a row it brought in is on screen.
        List<String> usedShown = new ArrayList<>();
        if (!used.isEmpty()) {
            for (String synonym : used) {
                for (Scored s : shown) {
                    if (s.entry.foldedTitle.contains(synonym) || s.entry.foldedSummary.contains(synonym)
                            || s.entry.foldedPlace.contains(synonym)) {
                        usedShown.add(synonym);
                        break;
                    }
                }
            }
        }
        return new Result(group(shown), total, counts, titles, usedShown);
    }

    /**
     * The entry's rank for the query, or {@link #NO_MATCH}; the synonyms that alone matched a
     * token are added to {@code synonymsUsed}.
     */
    private static int score(Entry e, List<String> tokens, Set<String> synonymsUsed) {
        int total = 0;
        for (String token : tokens) {
            int best;
            String synonymUsed = null;
            boolean directElsewhere = e.foldedSummary.contains(token) || e.foldedPlace.contains(token);
            if (e.foldedTitle.startsWith(token)) {
                best = TITLE_PREFIX;
            } else if (e.foldedTitle.contains(token)) {
                best = TITLE_CONTAINS;
            } else {
                String inTitle = synonymAtWord(e.foldedTitle, token);
                if (inTitle != null) {
                    best = TITLE_SYNONYM;
                    if (!directElsewhere) {
                        synonymUsed = inTitle;
                    }
                } else if (directElsewhere) {
                    best = ELSEWHERE;
                } else {
                    String elsewhere = synonymAtWord(e.foldedSummary, token);
                    if (elsewhere == null) {
                        elsewhere = synonymAtWord(e.foldedPlace, token);
                    }
                    if (elsewhere == null) {
                        return NO_MATCH;
                    }
                    best = ELSEWHERE;
                    synonymUsed = elsewhere;
                }
            }
            if (synonymUsed != null) {
                synonymsUsed.add(synonymUsed);
            }
            total += best;
        }
        return total;
    }

    // The first synonym of the token that starts a word of the text, or null.
    @Nullable
    private static String synonymAtWord(String folded, String token) {
        String padded = " " + folded;
        for (String synonym : SettingsSynonyms.of(token)) {
            if (padded.contains(" " + synonym)) {
                return synonym;
            }
        }
        return null;
    }

    private static List<Group> group(List<Scored> scored) {
        Map<String, List<Scored>> bySection = new LinkedHashMap<>();
        for (Scored s : scored) {
            List<Scored> list = bySection.get(s.entry.sectionId);
            if (list == null) {
                list = new ArrayList<>();
                bySection.put(s.entry.sectionId, list);
            }
            list.add(s);
        }
        List<List<Scored>> lists = new ArrayList<>(bySection.values());
        for (List<Scored> list : lists) {
            Collections.sort(list, BY_RANK);
        }
        // Best group first: by its best row, then where the section is on screen.
        Collections.sort(lists, new Comparator<List<Scored>>() {
            @Override
            public int compare(List<Scored> a, List<Scored> b) {
                return BY_RANK.compare(a.get(0), b.get(0));
            }
        });
        List<Group> groups = new ArrayList<>();
        for (List<Scored> list : lists) {
            List<Entry> rows = new ArrayList<>();
            for (Scored s : list) {
                rows.add(s.entry);
            }
            Entry first = list.get(0).entry;
            groups.add(new Group(first.sectionId, first.sectionTitle, rows));
        }
        return groups;
    }

    private static final Comparator<Scored> BY_RANK = new Comparator<Scored>() {
        @Override
        public int compare(Scored a, Scored b) {
            if (a.score != b.score) {
                return a.score - b.score;
            }
            return a.entry.order - b.entry.order;
        }
    };

    private static final class Scored {
        final Entry entry;
        final int score;

        Scored(Entry entry, int score) {
            this.entry = entry;
            this.score = score;
        }
    }

    /**
     * Lower case, accents stripped (NFD, marks dropped), and every run of anything that is not a
     * letter or a digit made one space: "Vidéo-FPS" and "video fps" fold alike.
     */
    @NonNull
    public static String fold(@NonNull String text) {
        String stripped = Normalizer.normalize(text, Normalizer.Form.NFD)
                .replaceAll("\\p{M}+", "");
        String lower = stripped.toLowerCase(Locale.ROOT);
        StringBuilder out = new StringBuilder(lower.length());
        boolean space = true;
        for (int i = 0; i < lower.length(); i++) {
            char c = lower.charAt(i);
            if (Character.isLetterOrDigit(c)) {
                out.append(c);
                space = false;
            } else if (!space) {
                out.append(' ');
                space = true;
            }
        }
        int end = out.length();
        if (end > 0 && out.charAt(end - 1) == ' ') {
            out.setLength(end - 1);
        }
        return out.toString();
    }

    /** A folded query's tokens: synonym phrases kept whole, the rest split into words. */
    @NonNull
    static List<String> tokens(@NonNull String folded) {
        List<String> tokens = new ArrayList<>();
        String rest = " " + folded + " ";
        for (String phrase : SettingsSynonyms.phrases()) {
            String padded = " " + phrase + " ";
            int at = rest.indexOf(padded);
            while (at >= 0) {
                tokens.add(phrase);
                rest = rest.substring(0, at) + " " + rest.substring(at + padded.length() - 1);
                at = rest.indexOf(padded);
            }
        }
        for (String word : rest.trim().split(" ")) {
            if (!word.isEmpty()) {
                tokens.add(word);
            }
        }
        return tokens;
    }
}
