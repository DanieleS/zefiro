package com.limelight.library;

import android.content.Context;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.RecyclerView;
import androidx.recyclerview.widget.SimpleItemAnimator;

import com.limelight.R;
import com.limelight.computers.HostEntry;
import com.limelight.computers.HostLibrary;
import com.limelight.computers.HostRepository;
import com.limelight.grid.AutofitGridLayoutManager;
import com.limelight.grid.GridSpacingItemDecoration;
import com.limelight.main.MainHost;
import com.limelight.main.Section;
import com.limelight.main.SectionFocus;
import com.limelight.main.SectionKeys;

import java.util.List;

/**
 * The Search section: a text field over the selected host's games, narrowed as the user types
 * to those whose name contains the text ({@link SearchFilter}).
 *
 * <p>The games are the shared {@code HostRepository}'s snapshot, as in See all, and drawn by the
 * same {@link GameGridAdapter}; a found game is launched, resumed or offered its actions exactly
 * as a browsed one. Where the host cannot list its games the {@link HostStatePanel} stands in
 * the results' place, as in See all, and the field stays, so the section is never without a
 * place for focus.
 *
 * <h3>Typing, by touch, keyboard or pad</h3>
 *
 * <pre>
 *   tap on the field           the soft keyboard, as any text field
 *   A on the field             the soft keyboard: a d-pad's confirm does not open it everywhere
 *   a letter or digit, typed   on a hardware keyboard (the Thor's) from anywhere in the section:
 *                              focus goes to the field and the key lands in it
 *   Search on the keyboard     the keyboard goes, and focus to the first result
 *   DOWN from the field        into the results; UP from their first row back to the field
 *   Y / long-press on a game   the game's actions sheet
 * </pre>
 *
 * <h3>Focus</h3>
 *
 * Entered, focus goes to the field. The section remembers the game (by app id) whose tile last
 * held focus, or the field, and the focus gate returns there from the navigation. A tile that
 * a new query filters away takes focus with it; the field gets it back.
 *
 * Main thread only; every callback checks that the view still exists.
 */
public class SearchFragment extends Fragment
        implements SectionKeys, SectionFocus, GameGridAdapter.Listener, HostStatePanel.Listener {

    private static final int NO_APP = Integer.MIN_VALUE;

    private MainHost host;

    @Nullable
    private EditText field;
    @Nullable
    private RecyclerView grid;
    @Nullable
    private GameGridAdapter adapter;
    @Nullable
    private HostStatePanel panel;
    @Nullable
    private TextView noMatch;

    // The last state drawn, kept to tell what changed on the next one.
    @Nullable
    private HostLibrary.Snapshot snapshot;
    private boolean showHidden;
    private int drawnArtVersion;
    @Nullable
    private Object drawnStandIns;
    // What the grid's list was last made from.
    @Nullable
    private HostLibrary.Snapshot submittedSnapshot;
    private boolean submittedShowHidden;
    @Nullable
    private String submittedQuery;

    // Focus memory. Survives the view, so a section shown again lands where it was left.
    private int lastFocusedAppId = NO_APP;
    private boolean lastFocusedField = true;

    public SearchFragment() {
    }

    @Override
    public void onAttach(@NonNull Context context) {
        super.onAttach(context);
        if (!(context instanceof MainHost)) {
            throw new IllegalStateException("SearchFragment needs a MainHost activity");
        }
        host = (MainHost) context;
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_search, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        field = view.findViewById(R.id.searchField);
        grid = view.findViewById(R.id.searchResults);
        noMatch = view.findViewById(R.id.searchNoMatch);
        panel = new HostStatePanel(view.findViewById(R.id.hostStatePanel), this);

        adapter = new GameGridAdapter(this);
        grid.setLayoutManager(new AutofitGridLayoutManager(requireContext(), GridSpanRule.SEE_ALL));
        grid.addItemDecoration(new GridSpacingItemDecoration(
                getResources().getDimensionPixelSize(R.dimen.see_all_tile_gap)));
        grid.setAdapter(adapter);
        // A change rebinds the tile in place: the default change animation would swap the
        // focused tile for a new view while a poll lands, and the d-pad would lose its place.
        RecyclerView.ItemAnimator animator = grid.getItemAnimator();
        if (animator instanceof SimpleItemAnimator) {
            ((SimpleItemAnimator) animator).setSupportsChangeAnimations(false);
        }
        // The tiles take focus, never the grid itself: an empty grid that held focus would
        // swallow the first d-pad press.
        grid.setFocusable(false);

        field.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
            }

            @Override
            public void afterTextChanged(Editable s) {
                render();
            }
        });
        field.setOnEditorActionListener((v, actionId, event) -> {
            boolean search = actionId == EditorInfo.IME_ACTION_SEARCH
                    || (event != null && event.getKeyCode() == KeyEvent.KEYCODE_ENTER
                    && event.getAction() == KeyEvent.ACTION_DOWN);
            if (!search) {
                return false;
            }
            hideKeyboard();
            View first = firstTile();
            if (first != null) {
                first.requestFocus();
            }
            return true;
        });
        field.setOnFocusChangeListener((v, hasFocus) -> {
            if (hasFocus) {
                lastFocusedField = true;
                // No game under the d-pad: the companion panel shows the selected host.
                host.gameFocus().focusHost(null);
            }
        });

        HostRepository repository = host.hostRepository();
        repository.getLibrary().observe(getViewLifecycleOwner(), s -> {
            snapshot = s;
            render();
        });
        // Only for the host's name, which the snapshot does not carry.
        repository.getHosts().observe(getViewLifecycleOwner(), hosts -> render());
        host.showHiddenGames().observe(getViewLifecycleOwner(), show -> {
            showHidden = Boolean.TRUE.equals(show);
            render();
        });
    }

    @Override
    public void onDestroyView() {
        if (grid != null) {
            grid.setAdapter(null);
        }
        field = null;
        grid = null;
        adapter = null;
        panel = null;
        noMatch = null;
        drawnStandIns = null;
        submittedSnapshot = null;
        submittedQuery = null;
        super.onDestroyView();
    }

    @Override
    public void onHiddenChanged(boolean hidden) {
        super.onHiddenChanged(hidden);
        if (hidden) {
            // A keyboard left up over another section would type into nothing there.
            hideKeyboard();
        }
    }

    // --- Drawing ---

    private void render() {
        if (!isAdded() || getView() == null || adapter == null || panel == null || grid == null
                || field == null || noMatch == null) {
            return;
        }
        HostLibrary.Snapshot s = snapshot;
        HostRepository repository = host.hostRepository();
        HostLibrary library = repository.currentLibrary();
        // The snapshot of a host whose library is not open yet comes without one.
        if (library != null && (s == null || !library.getHostUuid().equals(s.hostUuid))) {
            library = null;
        }
        if (adapter.setLibrary(library)) {
            // Cleared: whatever was submitted before is no longer there.
            submittedSnapshot = null;
        }

        HostEntry entry = s != null ? repository.getHost(s.hostUuid) : null;
        String hostName = entry != null ? entry.details.name : null;
        field.setHint(hostName != null
                ? getString(R.string.search_hint_host, hostName) : getString(R.string.search_hint));

        String query = field.getText().toString();
        // The panel speaks for the host, not the query: "no games" means none to search at all.
        List<LibraryApp> searchable = SeeAllFragment.visibleApps(s, showHidden);
        HostStatePanel.Mode mode = HostStatePanel.modeFor(s, searchable.size());
        boolean allHidden = s != null && !s.apps.isEmpty() && searchable.isEmpty();
        panel.show(mode, hostName, allHidden);

        List<LibraryApp> results = SearchFilter.filter(s != null ? s.apps : null, query, showHidden);
        boolean listing = mode == HostStatePanel.Mode.NONE;
        boolean nothingMatched = listing && results.isEmpty();
        noMatch.setVisibility(nothingMatched ? View.VISIBLE : View.GONE);
        if (nothingMatched) {
            noMatch.setText(getString(R.string.search_no_match, query.trim()));
        }
        boolean focusInGrid = grid.hasFocus();
        grid.setVisibility(listing && !nothingMatched ? View.VISIBLE : View.GONE);
        if (focusInGrid && grid.getVisibility() != View.VISIBLE) {
            // The tiles went from under the d-pad; the field is the one stop always there.
            field.requestFocus();
        }

        // The host list is published on every poll of every host; only a new snapshot, filter or
        // query is a new list for the grid, so the diff is not run every 1.5 s for nothing.
        if (s != submittedSnapshot || showHidden != submittedShowHidden
                || !query.equals(submittedQuery)) {
            submittedSnapshot = s;
            submittedShowHidden = showHidden;
            submittedQuery = query;
            adapter.submitList(results, this::onListCommitted);
        }

        // Covers moved on the host's side (landed on disk, or found to be its stand-in): the
        // games are the same, so the diff says nothing, and the tiles are asked to look again.
        if (s != null && (s.artVersion != drawnArtVersion || s.noArtFingerprints != drawnStandIns)) {
            boolean first = drawnStandIns == null;
            drawnArtVersion = s.artVersion;
            drawnStandIns = s.noArtFingerprints;
            if (!first) {
                adapter.refreshArt();
            }
        }
    }

    private void onListCommitted() {
        if (!isAdded() || getView() == null || field == null || !isOnScreen()) {
            return;
        }
        // The focused tile was filtered away (or the game left the host) and took focus with it.
        if (requireActivity().getCurrentFocus() == null) {
            field.requestFocus();
        }
    }

    // --- Tiles ---

    @Override
    public void onTileClicked(@NonNull LibraryApp app) {
        hideKeyboard();
        host.launchController().onTileClicked(app);
    }

    @Override
    public void onTileLongPressed(@NonNull LibraryApp app) {
        hideKeyboard();
        host.launchController().showActions(app);
    }

    @Override
    public void onTileFocused(@NonNull LibraryApp app) {
        lastFocusedAppId = app.appId();
        lastFocusedField = false;
        host.gameFocus().focusGame(app);
    }

    // --- The host state panel ---

    @Override
    public void onWake() {
        HostEntry entry = selectedEntry();
        if (entry != null) {
            host.hostActions().wake(entry.details);
        }
    }

    @Override
    public void onPair() {
        HostEntry entry = selectedEntry();
        if (entry != null) {
            host.hostActions().pair(entry.details, null, null);
        }
    }

    @Override
    public void onChooseHost() {
        host.showSection(Section.PCS);
    }

    @Nullable
    private HostEntry selectedEntry() {
        HostLibrary.Snapshot s = snapshot;
        return s != null ? host.hostRepository().getHost(s.hostUuid) : null;
    }

    // --- Keys ---

    @Override
    public boolean onSectionKey(KeyEvent event) {
        if (!isAdded() || getView() == null || field == null) {
            return false;
        }
        int code = event.getKeyCode();
        if (event.getRepeatCount() == 0 && code == KeyEvent.KEYCODE_BUTTON_Y) {
            LibraryApp focused = focusedApp();
            if (focused == null) {
                return false;
            }
            hideKeyboard();
            host.launchController().showActions(focused);
            return true;
        }
        if (field.isFocused()) {
            if (code == KeyEvent.KEYCODE_BUTTON_A && event.getRepeatCount() == 0) {
                showKeyboard();
                return true;
            }
            return false;
        }
        // Typed on a hardware keyboard away from the field: the field takes focus, and since
        // the key is not consumed, the activity hands it on to the field, where it is typed.
        if (startsTyping(event) && field.requestFocus()) {
            field.setSelection(field.length());
        }
        return false;
    }

    // A letter or digit only: space and enter confirm a focused tile, and a gamepad button has
    // no character at all.
    private static boolean startsTyping(KeyEvent event) {
        if (event.isCtrlPressed() || event.isAltPressed() || event.isMetaPressed()) {
            return false;
        }
        int c = event.getUnicodeChar();
        return c != 0 && Character.isLetterOrDigit(c);
    }

    /** The game whose tile holds focus, or null when focus is elsewhere (the field, the rail). */
    @Nullable
    private LibraryApp focusedApp() {
        View focus = requireActivity().getCurrentFocus();
        if (focus == null || grid == null || adapter == null) {
            return null;
        }
        RecyclerView.ViewHolder holder = grid.findContainingViewHolder(focus);
        return holder == null ? null : adapter.appAt(holder.getBindingAdapterPosition());
    }

    private void showKeyboard() {
        InputMethodManager imm = ContextCompat.getSystemService(requireContext(), InputMethodManager.class);
        if (imm != null && field != null) {
            imm.showSoftInput(field, 0);
        }
    }

    private void hideKeyboard() {
        if (!isAdded() || field == null) {
            return;
        }
        InputMethodManager imm = ContextCompat.getSystemService(requireContext(), InputMethodManager.class);
        if (imm != null) {
            imm.hideSoftInputFromWindow(field.getWindowToken(), 0);
        }
    }

    // --- Focus ---

    @Nullable
    @Override
    public View restoreFocusTarget() {
        if (!isAdded() || getView() == null) {
            return null;
        }
        if (!lastFocusedField) {
            View tile = tileFor(lastFocusedAppId);
            if (tile != null) {
                return tile;
            }
        }
        return field;
    }

    @Override
    public void requestInitialFocus() {
        if (!isAdded() || getView() == null || field == null) {
            return;
        }
        field.requestFocus();
    }

    @Nullable
    private View tileFor(int appId) {
        if (grid == null || appId == NO_APP || grid.getVisibility() != View.VISIBLE) {
            return null;
        }
        RecyclerView.ViewHolder holder = grid.findViewHolderForItemId(appId);
        return holder != null && holder.itemView.isShown() ? holder.itemView : null;
    }

    @Nullable
    private View firstTile() {
        if (grid == null || grid.getVisibility() != View.VISIBLE || grid.getChildCount() == 0) {
            return null;
        }
        View first = grid.getChildAt(0);
        return first.isShown() ? first : null;
    }

    // Shown to the user: added, with a view, and the section not hidden. A hidden section's
    // fragment is still resumed, so the lifecycle cannot tell.
    private boolean isOnScreen() {
        View view = getView();
        return isAdded() && view != null && view.isShown();
    }
}
