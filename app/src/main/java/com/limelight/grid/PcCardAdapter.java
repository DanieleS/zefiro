package com.limelight.grid;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.limelight.R;
import com.limelight.computers.HostEntry;
import com.limelight.pcs.HostCardBinder;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;

/**
 * The PCs, one card each, for the legacy {@code PcView}.
 *
 * What a card shows (state, address, running game, its art, the action) is drawn by
 * {@link HostCardBinder}, which the new PCs section shares; this adapter only keeps PcView's
 * mutable list and its click wiring. It is deleted with PcView.
 */
public class PcCardAdapter extends RecyclerView.Adapter<PcCardAdapter.PcCardHolder> {
    /** A tap on a card, reported with the card's view and adapter position. */
    public interface OnItemClickListener {
        void onClick(View view, int position);
    }

    /** A tap on the card's action, which is a different thing from a tap on the card. */
    public interface OnActionClickListener {
        void onClick(HostEntry computer, int action);
    }

    public static final int ACTION_NONE = HostCardBinder.ACTION_NONE;
    public static final int ACTION_WAKE = HostCardBinder.ACTION_WAKE;
    public static final int ACTION_PAIR = HostCardBinder.ACTION_PAIR;
    public static final int ACTION_RESUME = HostCardBinder.ACTION_RESUME;

    private final LayoutInflater inflater;
    private final ArrayList<HostEntry> itemList = new ArrayList<>();
    private final HostCardBinder binder;

    private OnItemClickListener clickListener;
    private OnActionClickListener actionListener;
    private boolean gamepadPresent;

    public PcCardAdapter(Context context) {
        this.inflater = LayoutInflater.from(context);
        this.binder = new HostCardBinder(context);
    }

    public void setOnItemClickListener(OnItemClickListener listener) {
        this.clickListener = listener;
    }

    /**
     * Tell the cards there is a gamepad, which changes two things.
     *
     * They hold focus in touch mode, as the library's tiles do: a screen entered by touch must
     * still have a card focused, or the first d-pad press is spent reclaiming focus rather than
     * moving. And their action wears the letter of the gamepad button that fires it, because a
     * d-pad cannot reach a button that sits inside the focused card — PcView fires it instead.
     *
     * Touch-only devices get neither: no ring stranded by a tap, and no letter for a button the
     * user does not have.
     */
    public void setGamepadPresent(boolean present) {
        if (this.gamepadPresent != present) {
            this.gamepadPresent = present;
            notifyDataSetChanged();
        }
    }

    /**
     * The one thing this host is waiting for. Everything else stays in the context menu.
     *
     * PcView asks for this too, to give the gamepad's X button the same action the card shows.
     */
    public static int actionFor(HostEntry computer) {
        return HostCardBinder.actionFor(computer);
    }

    public void setOnActionClickListener(OnActionClickListener listener) {
        this.actionListener = listener;
    }

    public void addComputer(HostEntry computer) {
        itemList.add(computer);
        Collections.sort(itemList, new Comparator<HostEntry>() {
            @Override
            public int compare(HostEntry lhs, HostEntry rhs) {
                return lhs.details.name.toLowerCase().compareTo(rhs.details.name.toLowerCase());
            }
        });
    }

    public boolean removeComputer(HostEntry computer) {
        return itemList.remove(computer);
    }

    // Kept so PcView can read the list as it did against the old BaseAdapter.
    public int getCount() {
        return itemList.size();
    }

    public Object getItem(int i) {
        return itemList.get(i);
    }

    @Override
    public int getItemCount() {
        return itemList.size();
    }

    @Override
    public long getItemId(int position) {
        return position;
    }

    @NonNull
    @Override
    public PcCardHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        return new PcCardHolder(inflater.inflate(R.layout.pc_card_item, parent, false));
    }

    @Override
    public void onBindViewHolder(@NonNull PcCardHolder holder, int position) {
        final HostEntry computer = itemList.get(position);
        binder.bind(holder.views, computer, gamepadPresent, (host, action) -> {
            if (actionListener != null) {
                actionListener.onClick(host, action);
            }
        });

        holder.itemView.setOnClickListener(v -> {
            if (clickListener == null) {
                return;
            }
            int pos = holder.getBindingAdapterPosition();
            if (pos != RecyclerView.NO_POSITION) {
                clickListener.onClick(v, pos);
            }
        });
    }

    static class PcCardHolder extends RecyclerView.ViewHolder {
        final HostCardBinder.CardViews views;

        PcCardHolder(View itemView) {
            super(itemView);
            // Focusable for the d-pad, long-clickable so the framework raises the context menu
            // through ContextMenuRecyclerView, exactly as an AbsListView child was.
            views = new HostCardBinder.CardViews(itemView);
        }
    }
}
