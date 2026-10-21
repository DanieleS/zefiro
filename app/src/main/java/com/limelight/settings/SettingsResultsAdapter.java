package com.limelight.settings;

import android.content.SharedPreferences;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.preference.Preference;
import androidx.recyclerview.widget.RecyclerView;

import com.limelight.R;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * The Settings section's search results: section headers, then the matching settings, each a row
 * bound from its live {@link Preference} ({@link SettingRowBinder}), so a switch shows its state
 * and a list its choice exactly as the row in the list does, and changes there show here.
 *
 * <p>Besides results, two kinds of line: a note ("No settings match …", "Everything is at its
 * default"), which is not a stop, and the "Search help instead" row under a search that found
 * nothing, which is.
 *
 * <p>The rows are keyed, not positional: what a click acts on is looked up by key at the click,
 * through {@link Host#find}, so a list rebuilt in the meantime (unlock FPS reloads it) is still
 * acted on correctly. Main thread only.
 */
final class SettingsResultsAdapter extends RecyclerView.Adapter<SettingsResultsAdapter.Holder> {

    /** What the rows read and where their clicks go. */
    interface Host {
        /** The live preference behind a key, or null when the list no longer has it. */
        @Nullable
        Preference find(@NonNull String key);

        @Nullable
        SettingsDefaults defaults();

        @Nullable
        SharedPreferences prefs();

        void onResultClicked(@NonNull String key);

        /** X or a long-press on a result: its reset and pin menu. */
        boolean onResultLongPressed(@NonNull String key, @NonNull View row);

        /** The "Search help instead" row. */
        void onSearchHelp();
    }

    static final int TYPE_HEADER = 0;
    static final int TYPE_ROW = 1;
    static final int TYPE_NOTE = 2;
    static final int TYPE_HELP = 3;

    /** One line of the results. Immutable. */
    static final class Item {
        final int type;
        // A row's key; null otherwise.
        @Nullable
        final String key;
        // A header's title, a note's text, a row's "Controllers › Rumble".
        @NonNull
        final CharSequence text;

        private Item(int type, @Nullable String key, @NonNull CharSequence text) {
            this.type = type;
            this.key = key;
            this.text = text;
        }

        static Item header(@NonNull CharSequence title) {
            return new Item(TYPE_HEADER, null, title);
        }

        static Item row(@NonNull String key, @NonNull CharSequence place) {
            return new Item(TYPE_ROW, key, place);
        }

        static Item note(@NonNull CharSequence text) {
            return new Item(TYPE_NOTE, null, text);
        }

        static Item help(@NonNull CharSequence place) {
            return new Item(TYPE_HELP, null, place);
        }
    }

    // A rebind of the rows' values in place, without the change animation's flicker.
    private static final Object PAYLOAD_VALUES = new Object();

    private final Host host;
    private List<Item> items = Collections.emptyList();
    // The query the items answer: its words are marked in the titles.
    @Nullable
    private String query;

    SettingsResultsAdapter(@NonNull Host host) {
        this.host = host;
    }

    void submit(@NonNull List<Item> next, @Nullable String query) {
        items = Collections.unmodifiableList(new ArrayList<>(next));
        this.query = query;
        notifyDataSetChanged();
    }

    /** A stored value changed: every row reads its preference again. */
    void refreshValues() {
        if (!items.isEmpty()) {
            notifyItemRangeChanged(0, items.size(), PAYLOAD_VALUES);
        }
    }

    /** The key of the row at a position, or null for a header, a note or the help row. */
    @Nullable
    String keyAt(int position) {
        return position >= 0 && position < items.size() ? items.get(position).key : null;
    }

    /** The position of a key's row, or {@link RecyclerView#NO_POSITION}. */
    int positionOf(@NonNull String key) {
        for (int i = 0; i < items.size(); i++) {
            if (key.equals(items.get(i).key)) {
                return i;
            }
        }
        return RecyclerView.NO_POSITION;
    }

    /** The key of the first result row, or null when there is none. */
    @Nullable
    String firstKey() {
        for (Item item : items) {
            if (item.type == TYPE_ROW) {
                return item.key;
            }
        }
        return null;
    }

    /** The first position a d-pad can stop on, or {@link RecyclerView#NO_POSITION}. */
    int firstStop() {
        for (int i = 0; i < items.size(); i++) {
            int type = items.get(i).type;
            if (type == TYPE_ROW || type == TYPE_HELP) {
                return i;
            }
        }
        return RecyclerView.NO_POSITION;
    }

    @Override
    public int getItemCount() {
        return items.size();
    }

    @Override
    public int getItemViewType(int position) {
        return items.get(position).type;
    }

    @NonNull
    @Override
    public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        LayoutInflater inflater = LayoutInflater.from(parent.getContext());
        int layout;
        switch (viewType) {
            case TYPE_HEADER:
                layout = R.layout.item_settings_result_header;
                break;
            case TYPE_NOTE:
                layout = R.layout.item_settings_result_note;
                break;
            case TYPE_HELP:
            case TYPE_ROW:
            default:
                layout = R.layout.item_settings_result;
                break;
        }
        return new Holder(inflater.inflate(layout, parent, false));
    }

    @Override
    public void onBindViewHolder(@NonNull Holder holder, int position) {
        Item item = items.get(position);
        switch (item.type) {
            case TYPE_HEADER:
            case TYPE_NOTE:
                ((TextView) holder.itemView).setText(item.text);
                break;
            case TYPE_HELP:
                bindHelp(holder, item);
                break;
            case TYPE_ROW:
            default:
                bindRow(holder, item);
                break;
        }
    }

    private void bindRow(Holder holder, Item item) {
        final String key = item.key;
        Preference preference = key != null ? host.find(key) : null;
        SettingsDefaults defaults = host.defaults();
        if (preference == null || defaults == null) {
            // The list is being rebuilt; the next refresh binds the row again.
            return;
        }
        SettingRowBinder.bindResult(holder.itemView, preference, item.text, defaults, host.prefs(),
                query);
        holder.itemView.setOnClickListener(v -> host.onResultClicked(key));
        holder.itemView.setOnLongClickListener(v -> host.onResultLongPressed(key, v));
    }

    private void bindHelp(Holder holder, Item item) {
        View row = holder.itemView;
        ((TextView) row.findViewById(R.id.settingResultTitle)).setText(R.string.settings_search_help);
        ((TextView) row.findViewById(R.id.settingResultPlace)).setText(item.text);
        row.findViewById(R.id.settingValue).setVisibility(View.GONE);
        row.findViewById(R.id.settingChangedDot).setVisibility(View.GONE);
        row.findViewById(R.id.settingResultSwitch).setVisibility(View.GONE);
        row.findViewById(R.id.settingExternal).setVisibility(View.VISIBLE);
        row.setOnClickListener(v -> host.onSearchHelp());
        row.setOnLongClickListener(null);
        row.setLongClickable(false);
    }

    static final class Holder extends RecyclerView.ViewHolder {
        Holder(@NonNull View itemView) {
            super(itemView);
        }
    }
}
