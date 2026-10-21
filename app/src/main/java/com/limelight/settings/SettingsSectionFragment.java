package com.limelight.settings;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.hardware.input.InputManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Parcelable;
import android.text.TextUtils;
import android.view.Display;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.view.ViewTreeObserver;
import android.view.inputmethod.InputMethodManager;
import android.widget.FrameLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentManager;
import androidx.preference.ListPreference;
import androidx.preference.Preference;
import androidx.preference.PreferenceFragmentCompat;
import androidx.preference.PreferenceGroup;
import androidx.preference.PreferenceManager;
import androidx.preference.PreferenceScreen;
import androidx.preference.TwoStatePreference;
import androidx.recyclerview.widget.ConcatAdapter;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.recyclerview.widget.SimpleItemAnimator;

import com.limelight.ProfilesActivity;
import com.limelight.R;
import com.limelight.companion.CompanionDisplayManager;
import com.limelight.companion.CompanionPage;
import com.limelight.main.CompanionPages;
import com.limelight.main.MainHost;
import com.limelight.main.SectionBack;
import com.limelight.main.SectionFocus;
import com.limelight.main.SectionKeys;
import com.limelight.main.WindowClass;
import com.limelight.preferences.PreferenceConfiguration;
import com.limelight.preferences.SeekBarPreference;
import com.limelight.preferences.SettingsHost;
import com.limelight.preferences.StreamPreferencesFragment;
import com.limelight.profiles.ProfilesManager;
import com.limelight.profiles.SettingsProfile;
import com.limelight.ui.MenuSheet;
import com.limelight.utils.ServerHelper;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * The Settings section, after Settings_C: every stream setting in one list, in the catalogue's
 * sections, under a header with search, the pinned card and jump chips.
 *
 * <p>The list is a {@link StreamPreferencesFragment}, the same one the settings activity used to
 * host, with all of its device removals, listeners, dialogs and intents. This section adds ways
 * to reach and change those same {@link Preference} objects; it never stores a value itself.
 * Every change it makes goes through {@link SettingsEditor}, which asks the preference's change
 * listener first, so a reset, a Left on a list row and a choice in its dialog have the same
 * effects.
 *
 * <h3>Layout</h3>
 *
 * <pre>
 *   the Thor                 a phone, either way up          searching (either)
 *   ┌────────────────────┐   ┌────────────────────┐          ┌────────────────────┐
 *   │ header (fixed)     │   │ ┌ list ──────────┐ │          │ header: search bar │
 *   ├────────────────────┤   │ │ header (item 0)│ │          │         filters    │
 *   │ VIDEO 34 (sticky)  │   │ │ VIDEO 34       │ │          ├────────────────────┤
 *   │ list               │   │ │ rows…          │ │          │ results            │
 *   └────────────────────┘   └─┴────────────────┴─┘          └────────────────────┘
 * </pre>
 *
 * There is one header view; {@link #placeHeader()} hangs it in the fixed slot or makes it the
 * list's first item ({@link SettingsHeaderAdapter}) as the window and the search mode require
 * ({@link SettingsLayout#headerInList}): a phone in landscape scrolls it away too, or it would
 * take most of the height. A row's position in the list is therefore the header's count plus the
 * settings adapter's own position ({@link #scrollToKey}).
 *
 * <p>Over the list's top, once the header is out of view, a slim sticky label names the section
 * the rows under it are in ("VIDEO 34"), or where the focused row is ("VIDEO · FRAME PACING · 8 OF
 * 34"). A section's own header row is hidden while it is under that label, which says the same.
 *
 * <h3>Keys</h3>
 *
 * <pre>
 *   A / tap            a row's own click: toggle, dialog, page; a pin goes to its row
 *   LEFT / RIGHT       on a list, slider or switch row: the next value, in place; at an end
 *                      (or on any other row) the key goes on, so LEFT still reaches the rail
 *   X / long-press     reset to default and pin or unpin; no single press resets a value
 *   Y                  search, from anywhere; a letter typed on a keyboard starts one too
 *   LB / RB            the previous and next chip at rest (Pinned, Video, …), jumping there;
 *                      the previous and next filter while searching
 *   B / back           leaves the search, then the section
 * </pre>
 *
 * <h3>Being a section</h3>
 *
 * A section is shown and hidden, not resumed, so what the settings activity did on resume
 * (the companion row's title) is also done when the section comes back into view. The list
 * reloads itself through {@link SettingsHost} when a change reshapes it (unlock FPS, a custom
 * resolution), and here also when the display's size changes, as the activity did; the row that
 * had focus has it again in the new list.
 *
 * <h3>The companion panel</h3>
 *
 * While the panel is up ({@link CompanionDisplayManager#panelShowing()}) and this section is on
 * screen, the panel spells out the setting whose row has focus, in the list or among the results
 * ({@link SettingsChoicesPage}): a list's choices, a switch, a slider's − and +. A or a tap on a
 * list row binds the panel to that row instead of opening its dialog on top, as the mock has it;
 * without the panel the dialog opens as before ({@link SettingsListDialog}). Searching, the field
 * and the filters spell out the first result. Focus anywhere else, in the header at rest or out
 * of the section (the rail), shows the essentials, the panel's resting card
 * ({@link SettingsPinnedPage}). The d-pad stays here, on the main screen; the panel is touch
 * only, and every tap on it acts through the live preference and {@link SettingsEditor}, exactly
 * as Left and Right on the row do. The page is built again from the preference after every stored
 * change, so it follows a value changed from either screen, and a dependent enabled or disabled
 * by it. The section hidden takes the page off, and the panel's browsing surface comes back.
 *
 * <p>The panel can come and go while the section is up (the Thor's lower screen switched off and
 * on): every choice that depends on it is made again as it changes, never once at creation. The
 * rows' descriptions, the dialog or the panel for a list row, and the page itself follow it, and
 * the focused row, the scroll, a search and its filter stay as they were.
 *
 * <p>A tap on the panel puts this window in touch mode too (touch mode is global up to Android
 * 13), which takes focus off the row; the next d-pad press then gives focus to the window's
 * default view. The row in focus is that default ({@code setFocusedByDefault}), so the press
 * lands back on it rather than on the rail's first item.
 *
 * Main thread only; every callback checks that the view still exists.
 */
public class SettingsSectionFragment extends Fragment
        implements SectionKeys, SectionFocus, SectionBack, SettingsHost,
        StreamPreferencesFragment.ListHeader, SettingsHeader.Listener, SettingsResultsAdapter.Host,
        CompanionPages.PageOwner, PreferenceFragmentCompat.OnPreferenceDisplayDialogCallback {

    /** The tag the settings list is added under. */
    public static final String TAG_LIST = "settings:list";

    private static final String KEY_COMPANION_DISPLAY = "checkbox_enable_companion_display";
    private static final String KEY_FULL_EX_DISPLAY = "checkbox_enable_fullexdisplay";
    private static final String KEY_HELP = "pref_help";
    private static final String KEY_CODEC = "video_format";
    // androidx's own tag for a preference's dialog, so only one is ever up.
    private static final String DIALOG_TAG = "androidx.preference.PreferenceFragment.DIALOG";

    private MainHost host;
    private PinnedSettings pinned;
    private SharedPreferences prefs;

    @Nullable
    private SettingsHeader header;
    @Nullable
    private SettingsHeaderAdapter headerAdapter;
    @Nullable
    private FrameLayout headerSlot;
    @Nullable
    private View listContainer;
    @Nullable
    private RecyclerView results;
    @Nullable
    private SettingsResultsAdapter resultsAdapter;
    @Nullable
    private View sticky;
    @Nullable
    private TextView stickySection;
    @Nullable
    private TextView stickyDetail;

    // The list view this section is listening to, and the list fragment it belongs to; replaced
    // on every reload.
    @Nullable
    private RecyclerView wiredList;
    @Nullable
    private StreamPreferencesFragment wiredFragment;

    private boolean wide;
    // A wide window too short to keep the header fixed (a phone in landscape).
    private boolean shortWindow;
    private boolean searching;
    @NonNull
    private SettingsSearch.Filter filter = SettingsSearch.Filter.ALL;
    // The section of the first row in view: the "on" chip, and LB / RB's start without a row.
    @Nullable
    private String onSection;

    // Focus memory. Survives the view, so a section shown again lands where it was left.
    @Nullable
    private String lastFocusedKey;
    // Where focus was when a search began, to put it back when the search ends.
    @Nullable
    private String keyBeforeSearch;
    @Nullable
    private View viewBeforeSearch;
    // Where the list was scrolled when a search began, to scroll it back when the search ends.
    @Nullable
    private Parcelable scrollBeforeSearch;
    // The row to focus once a reloaded list is up.
    @Nullable
    private String pendingFocusKey;

    private int previousDisplayPixelCount;

    // The companion panel: whether it is up, the setting it is to show (null for the pinned
    // essentials), and the page last handed to it, null while none is.
    private boolean panelUp;
    @Nullable
    private String pageKey;
    @Nullable
    private CompanionPage page;

    // The row the window's next d-pad press lands on after touch mode took focus off it.
    @Nullable
    private View defaultFocusRow;

    // Posted, not run, after a stored change: the change listener runs inside the preference's
    // own write, before it has redrawn and before its dependents have been enabled or disabled.
    private final Runnable republish = this::publishPage;

    private final SharedPreferences.OnSharedPreferenceChangeListener storedChange =
            (sharedPreferences, key) -> onStoredChange(key);

    // The manager may notify from any thread; the section redraws on its own.
    private final ProfilesManager.ProfileChangeListener profilesChanged = () -> {
        View view = getView();
        if (view != null) {
            view.post(() -> {
                refreshHeader();
                // The pinned page names the profile and shows the values it sets.
                publishPage();
            });
        }
    };

    private final ViewTreeObserver.OnGlobalFocusChangeListener focusChange =
            (oldFocus, newFocus) -> onFocusChanged(newFocus);

    private final RecyclerView.OnScrollListener listScrolled = new RecyclerView.OnScrollListener() {
        @Override
        public void onScrolled(@NonNull RecyclerView recyclerView, int dx, int dy) {
            updateOnChip();
            updateSticky();
        }
    };

    // A row rebound or the header moved in or out lays the list out without a scroll. Posted:
    // the label is laid out itself as it comes and goes, which is not for the list's own pass.
    private final Runnable stickyUpdate = this::updateSticky;
    private final View.OnLayoutChangeListener listLaidOut =
            (v, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom) -> {
                v.removeCallbacks(stickyUpdate);
                v.post(stickyUpdate);
            };

    // Touch mode takes focus off the rows (a tap here, or on the panel): the row the user was on
    // becomes the window's default focus, which the next d-pad press goes to. Heard both ways,
    // and on leaving before the window picks that view, so the mark is fresh whatever was
    // scrolled meanwhile.
    private final ViewTreeObserver.OnTouchModeChangeListener touchModeChange = inTouchMode -> {
        if (isOnScreen()) {
            markDefaultFocus(restoreFocusTarget());
        }
    };

    // A gamepad connected or gone: the header's Y, LB and RB glyphs follow.
    private final InputManager.InputDeviceListener padsChanged = new InputManager.InputDeviceListener() {
        @Override
        public void onInputDeviceAdded(int deviceId) {
            applyGlyphs();
        }

        @Override
        public void onInputDeviceRemoved(int deviceId) {
            applyGlyphs();
        }

        @Override
        public void onInputDeviceChanged(int deviceId) {
            applyGlyphs();
        }
    };

    private final FragmentManager.FragmentLifecycleCallbacks listLifecycle =
            new FragmentManager.FragmentLifecycleCallbacks() {
                @Override
                public void onFragmentViewCreated(@NonNull FragmentManager fm, @NonNull Fragment f,
                                                  @NonNull View v, @Nullable Bundle savedInstanceState) {
                    if (f instanceof StreamPreferencesFragment && TAG_LIST.equals(f.getTag())) {
                        wireList((StreamPreferencesFragment) f);
                    }
                }

                @Override
                public void onFragmentViewDestroyed(@NonNull FragmentManager fm, @NonNull Fragment f) {
                    if (f == wiredFragment) {
                        unwireList();
                    }
                }
            };

    public SettingsSectionFragment() {
    }

    @Override
    public void onAttach(@NonNull Context context) {
        super.onAttach(context);
        if (!(context instanceof MainHost)) {
            throw new IllegalStateException("SettingsSectionFragment needs a MainHost activity");
        }
        host = (MainHost) context;
        pinned = new PinnedSettings(context);
        prefs = PreferenceManager.getDefaultSharedPreferences(context);
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_settings, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        headerSlot = view.findViewById(R.id.settingsHeaderSlot);
        listContainer = view.findViewById(R.id.settingsList);
        results = view.findViewById(R.id.settingsResults);
        sticky = view.findViewById(R.id.settingsSticky);
        stickySection = view.findViewById(R.id.settingsStickySection);
        stickyDetail = view.findViewById(R.id.settingsStickyDetail);

        View headerView = LayoutInflater.from(view.getContext())
                .inflate(R.layout.view_settings_header, headerSlot, false);
        header = new SettingsHeader(headerView, this);
        headerAdapter = new SettingsHeaderAdapter(headerView);
        Configuration config = getResources().getConfiguration();
        wide = WindowClass.from(config.screenWidthDp) == WindowClass.WIDE;
        shortWindow = WindowClass.isShortWide(config.screenWidthDp, config.screenHeightDp);
        header.setWide(wide, gamepadConnected());
        // Before the list is made: its adapter asks for the header's at once.
        placeHeader();

        resultsAdapter = new SettingsResultsAdapter(this);
        results.setLayoutManager(new LinearLayoutManager(view.getContext()));
        results.setAdapter(resultsAdapter);
        noChangeAnimations(results);
        // The rows take focus, never the list itself.
        results.setFocusable(false);

        getChildFragmentManager().registerFragmentLifecycleCallbacks(listLifecycle, false);
        StreamPreferencesFragment list = list();
        if (list == null) {
            getChildFragmentManager().beginTransaction()
                    .replace(R.id.settingsList, new StreamPreferencesFragment(), TAG_LIST)
                    .commitNow();
        } else if (list.getView() != null) {
            wireList(list);
        }

        prefs.registerOnSharedPreferenceChangeListener(storedChange);
        ProfilesManager.getInstance().addListener(profilesChanged);
        view.getViewTreeObserver().addOnGlobalFocusChangeListener(focusChange);
        view.getViewTreeObserver().addOnTouchModeChangeListener(touchModeChange);
        InputManager input = ContextCompat.getSystemService(requireContext(), InputManager.class);
        if (input != null) {
            input.registerInputDeviceListener(padsChanged, null);
        }
        // The panel shown or hidden while this list is up: from the host sheet, or the Thor's
        // lower screen switched off and on. The companion row's title says what a click does,
        // so it follows; so do the count it is part of, the rows' description lines, where a
        // list row's choices go (SettingsLayout) and whether the focused setting is spelled out
        // down there. Nothing else moves: the row in focus, the scroll and a search stay.
        CompanionDisplayManager.panelShowing().observe(getViewLifecycleOwner(), showing -> {
            panelUp = Boolean.TRUE.equals(showing);
            refreshCompanionRow();
            applyRowLines();
            refreshHeader();
            publishPage();
        });
        previousDisplayPixelCount = displayPixelCount();
        refreshHeader();
    }

    @Override
    public void onDestroyView() {
        View view = getView();
        if (view != null) {
            view.removeCallbacks(republish);
            if (view.getViewTreeObserver().isAlive()) {
                view.getViewTreeObserver().removeOnGlobalFocusChangeListener(focusChange);
                view.getViewTreeObserver().removeOnTouchModeChangeListener(touchModeChange);
            }
        }
        InputManager input = ContextCompat.getSystemService(requireContext(), InputManager.class);
        if (input != null) {
            input.unregisterInputDeviceListener(padsChanged);
        }
        markDefaultFocus(null);
        clearPage();
        prefs.unregisterOnSharedPreferenceChangeListener(storedChange);
        ProfilesManager.getInstance().removeListener(profilesChanged);
        getChildFragmentManager().unregisterFragmentLifecycleCallbacks(listLifecycle);
        unwireList();
        if (results != null) {
            results.setAdapter(null);
        }
        header = null;
        headerAdapter = null;
        headerSlot = null;
        listContainer = null;
        results = null;
        resultsAdapter = null;
        sticky = null;
        stickySection = null;
        stickyDetail = null;
        viewBeforeSearch = null;
        super.onDestroyView();
    }

    @Override
    public void onHiddenChanged(boolean hidden) {
        super.onHiddenChanged(hidden);
        if (hidden) {
            // A keyboard left up over another section would type into nothing there.
            hideKeyboard();
            // Another section's d-pad must not come back here after a tap.
            markDefaultFocus(null);
            // The section shown now owns the panel; Settings' page must not linger over it.
            clearPage();
        } else {
            // What the settings activity did on resume: the companion may have been switched on
            // or off, shown or hidden, while another section was up.
            refreshCompanionRow();
            // A pad may have been connected meanwhile.
            applyGlyphs();
            refreshHeader();
            View view = getView();
            if (view != null) {
                // Once the section is laid out and its focus restored.
                view.post(republish);
            }
        }
    }

    @Override
    public void onConfigurationChanged(@NonNull Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        if (header == null) {
            return;
        }
        boolean nextWide = WindowClass.from(newConfig.screenWidthDp) == WindowClass.WIDE;
        boolean nextShort = WindowClass.isShortWide(newConfig.screenWidthDp, newConfig.screenHeightDp);
        StreamPreferencesFragment list = list();
        if (list != null && list.getPreferenceScreen() != null) {
            // Turned: the native resolutions say which way round they are meant.
            list.relabelNativeResolutions();
        }
        if (nextWide != wide || nextShort != shortWindow) {
            View focus = currentFocus();
            boolean headerHadFocus = header.contains(focus);
            wide = nextWide;
            shortWindow = nextShort;
            header.setWide(wide, gamepadConnected());
            placeHeader();
            applyRowLines();
            refreshHeader();
            if (headerHadFocus) {
                // Moved between the slot and the list, and focus did not come along.
                View target = searching ? header.field() : header.searchRest();
                target.requestFocus();
            }
        }
        // As the settings activity did: a display of another size (a foldable's other screen) is
        // a new display, with other resolutions and frame rates to offer. The display id stays
        // the same between a foldable's screens, so the size is what tells them apart.
        int pixels = displayPixelCount();
        if (pixels != previousDisplayPixelCount) {
            reloadPreferences();
        }
    }

    // --- The list ---

    @Nullable
    private StreamPreferencesFragment list() {
        if (!isAdded()) {
            return null;
        }
        Fragment f = getChildFragmentManager().findFragmentByTag(TAG_LIST);
        return f instanceof StreamPreferencesFragment ? (StreamPreferencesFragment) f : null;
    }

    @Nullable
    private PreferenceScreen screen() {
        StreamPreferencesFragment list = list();
        return list != null ? list.getPreferenceScreen() : null;
    }

    @Nullable
    private SettingsAdapter settingsAdapter() {
        StreamPreferencesFragment list = list();
        return list != null ? list.getSettingsAdapter() : null;
    }

    @Nullable
    private RecyclerView listView() {
        StreamPreferencesFragment list = list();
        return list != null && list.getView() != null ? list.getListView() : null;
    }

    @NonNull
    @Override
    public RecyclerView.Adapter<?> listHeaderAdapter() {
        if (headerAdapter == null) {
            throw new IllegalStateException("The settings list was made without this section's view");
        }
        return headerAdapter;
    }

    @NonNull
    @Override
    public RecyclerView.LayoutManager listLayoutManager(@NonNull Context context) {
        // A row focused at the top stops below the sticky label rather than under it.
        return new SettingsLayoutManager(context, () -> {
            View label = sticky;
            return label != null && label.getVisibility() == View.VISIBLE ? label.getHeight() : 0;
        });
    }

    /** A list fragment's view is up: listen to it, and put back the focus a reload took. */
    private void wireList(@NonNull StreamPreferencesFragment fragment) {
        unwireList();
        RecyclerView list = fragment.getListView();
        wiredFragment = fragment;
        wiredList = list;
        list.addOnScrollListener(listScrolled);
        list.addOnLayoutChangeListener(listLaidOut);
        // A value changed in place rebinds its row; the change animation would swap the row's
        // view for a new one, and the d-pad would lose it on every Left and Right.
        noChangeAnimations(list);
        SettingsAdapter adapter = fragment.getSettingsAdapter();
        if (adapter != null) {
            adapter.setRowLongPressListener(this::showRowMenu);
        }
        applyRowLines();
        // Before the header counts the rows: the list shows or hides it only when resumed.
        refreshCompanionRow();
        refreshHeader();
        // A reloaded list has new preferences: the panel's page is built from them again.
        publishPage();
        if (searching) {
            runSearch();
        }
        final String key = pendingFocusKey;
        pendingFocusKey = null;
        if (key != null && !searching) {
            list.post(() -> scrollToKey(key, true));
        }
    }

    private void unwireList() {
        RecyclerView list = wiredList;
        if (list != null) {
            list.removeOnScrollListener(listScrolled);
            list.removeOnLayoutChangeListener(listLaidOut);
            list.removeCallbacks(stickyUpdate);
            RecyclerView.Adapter<?> adapter = list.getAdapter();
            if (adapter instanceof ConcatAdapter && headerAdapter != null) {
                // The header's adapter outlives the list; left in the old list's adapter, it
                // would keep that list, and its whole settings screen, reachable.
                ((ConcatAdapter) adapter).removeAdapter(headerAdapter);
            }
            list.setAdapter(null);
        }
        wiredList = null;
        wiredFragment = null;
    }

    private static void noChangeAnimations(RecyclerView list) {
        RecyclerView.ItemAnimator animator = list.getItemAnimator();
        if (animator instanceof SimpleItemAnimator) {
            ((SimpleItemAnimator) animator).setSupportsChangeAnimations(false);
        }
    }

    /**
     * Hangs the header where the window and the mode want it ({@link SettingsLayout#headerInList}):
     * in the list as its first item on a phone at rest, either way up, in the fixed slot
     * otherwise.
     */
    private void placeHeader() {
        if (header == null || headerAdapter == null || headerSlot == null) {
            return;
        }
        View view = header.view();
        boolean inList = headerInList();
        if (inList) {
            if (view.getParent() == headerSlot) {
                headerSlot.removeView(view);
            }
            // The item's bind takes the view in.
            headerAdapter.setShown(true);
        } else {
            headerAdapter.setShown(false);
            ViewParent parent = view.getParent();
            if (parent != headerSlot) {
                if (parent instanceof ViewGroup) {
                    ((ViewGroup) parent).removeView(view);
                }
                headerSlot.addView(view);
            }
        }
    }

    private boolean headerInList() {
        return SettingsLayout.headerInList(wide, shortWindow, searching);
    }

    /** The rows' description lines, for the window and the panel as they are now. */
    private void applyRowLines() {
        SettingsAdapter adapter = settingsAdapter();
        if (adapter != null) {
            adapter.setRowLines(wide, panelUp);
        }
    }

    private int headerOffset() {
        return headerAdapter != null ? headerAdapter.getItemCount() : 0;
    }

    /**
     * Scrolls the list to a key's row (or a section's header) and, if asked, focuses it once it
     * is laid out. {@code scrollToPreference} cannot be used: it refuses an adapter that is not
     * androidx's own, and the list's is a {@link ConcatAdapter} under a header.
     *
     * @return false when the list does not have the key
     */
    private boolean scrollToKey(@NonNull String key, boolean focus) {
        RecyclerView list = listView();
        SettingsAdapter adapter = settingsAdapter();
        if (list == null || adapter == null
                || !(list.getLayoutManager() instanceof LinearLayoutManager)) {
            return false;
        }
        int local = adapter.getPreferenceAdapterPosition(key);
        if (local == RecyclerView.NO_POSITION) {
            return false;
        }
        final int position = headerOffset() + local;
        boolean sectionHeader = key.startsWith(SectionCategory.KEY_PREFIX);
        // A row lands a little below the top, so the rows before it still say where it is.
        int offset = sectionHeader ? 0 : list.getHeight() / 4;
        ((LinearLayoutManager) list.getLayoutManager()).scrollToPositionWithOffset(position, offset);
        if (focus) {
            list.post(() -> focusPosition(position));
        }
        return true;
    }

    private void focusPosition(int position) {
        RecyclerView list = listView();
        if (list == null) {
            return;
        }
        RecyclerView.ViewHolder holder = list.findViewHolderForAdapterPosition(position);
        if (holder != null && holder.itemView.isShown()) {
            holder.itemView.requestFocus();
        }
    }

    /** The list row of a key on screen, or null. */
    @Nullable
    private View rowViewFor(@Nullable String key) {
        RecyclerView list = listView();
        SettingsAdapter adapter = settingsAdapter();
        if (key == null || list == null || adapter == null || searching) {
            return null;
        }
        int local = adapter.getPreferenceAdapterPosition(key);
        if (local == RecyclerView.NO_POSITION) {
            return null;
        }
        RecyclerView.ViewHolder holder = list.findViewHolderForAdapterPosition(headerOffset() + local);
        return holder != null && holder.itemView.isShown() && holder.itemView.isFocusable()
                ? holder.itemView : null;
    }

    /** The first row in view that takes focus, or null. */
    @Nullable
    private View firstRowInView() {
        RecyclerView list = listView();
        if (list == null) {
            return null;
        }
        for (int i = 0; i < list.getChildCount(); i++) {
            View child = list.getChildAt(i);
            RecyclerView.ViewHolder holder = list.getChildViewHolder(child);
            if (holder.getBindingAdapter() instanceof SettingsAdapter && child.isFocusable()
                    && child.isShown()) {
                return child;
            }
        }
        return null;
    }

    /** The setting a focused view is the row of, in the list or among the results; else null. */
    @Nullable
    private Preference preferenceAt(@Nullable View focus) {
        if (focus == null) {
            return null;
        }
        RecyclerView list = listView();
        if (!searching && list != null && isWithin(focus, list)) {
            RecyclerView.ViewHolder holder = list.findContainingViewHolder(focus);
            if (holder != null && holder.getBindingAdapter() instanceof SettingsAdapter) {
                int position = holder.getBindingAdapterPosition();
                if (position != RecyclerView.NO_POSITION) {
                    Preference p = ((SettingsAdapter) holder.getBindingAdapter()).getItem(position);
                    return p != null && p.getKey() != null && !(p instanceof PreferenceGroup)
                            ? p : null;
                }
            }
            return null;
        }
        if (searching && results != null && resultsAdapter != null && isWithin(focus, results)) {
            RecyclerView.ViewHolder holder = results.findContainingViewHolder(focus);
            String key = holder != null ? resultsAdapter.keyAt(holder.getBindingAdapterPosition()) : null;
            return key != null ? find(key) : null;
        }
        return null;
    }

    // --- Reloading ---

    @Override
    public void reloadPreferences() {
        if (!isAdded() || getView() == null) {
            return;
        }
        Preference focused = preferenceAt(currentFocus());
        pendingFocusKey = focused != null ? focused.getKey() : null;
        previousDisplayPixelCount = displayPixelCount();
        getChildFragmentManager().beginTransaction()
                .replace(R.id.settingsList, new StreamPreferencesFragment(), TAG_LIST)
                .commitNowAllowingStateLoss();
    }

    private int displayPixelCount() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M || getActivity() == null) {
            return 0;
        }
        Display display = ServerHelper.getActiveDisplay(requireActivity(),
                PreferenceConfiguration.readPreferences(requireContext()));
        Display.Mode mode = display.getMode();
        return mode.getPhysicalWidth() * mode.getPhysicalHeight();
    }

    // --- Stored changes ---

    private void onStoredChange(@Nullable String key) {
        if (!isAdded() || getView() == null) {
            return;
        }
        if (KEY_COMPANION_DISPLAY.equals(key) || KEY_FULL_EX_DISPLAY.equals(key)) {
            // Before the header counts the rows: the companion row is one of them.
            refreshCompanionRow();
        }
        refreshHeader();
        if (searching && resultsAdapter != null) {
            // A result's value, a dependent's enabled state: read again from the preferences.
            resultsAdapter.refreshValues();
        }
        if (panelUp) {
            // Any key: a resolution step rewrites the bitrate, a switch enables its dependents.
            View view = getView();
            view.removeCallbacks(republish);
            view.post(republish);
        }
    }

    private void refreshCompanionRow() {
        StreamPreferencesFragment list = list();
        if (list != null && list.getPreferenceScreen() != null) {
            list.refreshCompanionScreenEntry();
        }
    }

    // --- The header ---

    /** Draws the header at rest from the live list: the count, the profile, the card, the chips. */
    private void refreshHeader() {
        PreferenceScreen screen = screen();
        SettingsAdapter adapter = settingsAdapter();
        if (header == null || screen == null || adapter == null || !isAdded()) {
            return;
        }
        SettingsDefaults defaults = adapter.getDefaults();
        Set<String> visible = SettingsIndex.visibleKeys(screen);
        Set<String> changed = SettingsIndex.changedKeys(screen, defaults, prefs);
        List<SettingsHeader.Pin> pins = new ArrayList<>();
        SettingsProfile profile = ProfilesManager.getInstance().getActive();
        Map<String, Object> overrides = profile != null ? profile.getOptions() : null;
        for (String key : pinned.shown(visible)) {
            Preference p = screen.findPreference(key);
            if (p != null) {
                pins.add(pinFor(p, changed.contains(key), overrides));
            }
        }
        String name = ProfilesManager.getInstance().getActiveName().trim();
        header.bindRest(visible.size(), name.isEmpty() ? null : name, changed.size(), pins,
                SettingsIndex.sectionsShown(screen, SettingsCatalog.get()));
        updateOnChip();
        updateSticky();
    }

    private SettingsHeader.Pin pinFor(Preference p, boolean changed,
                                      @Nullable Map<String, Object> overrides) {
        String key = p.getKey();
        CharSequence label = pinLabel(p);
        boolean fromProfile = overrides != null && overrides.containsKey(key);
        CharSequence value;
        if (fromProfile) {
            Object override = overrides.get(key);
            value = PreferenceConfiguration.BITRATE_PREF_STRING.equals(key) && !wide
                    && override instanceof Number
                    ? mbps(((Number) override).intValue())
                    : KEY_CODEC.equals(key) && override != null
                    ? codec(String.valueOf(override), SettingValues.displayFor(p, override))
                    : SettingValues.displayFor(p, override);
        } else if (KEY_CODEC.equals(key) && p instanceof ListPreference) {
            value = codec(((ListPreference) p).getValue(), SettingValues.display(p));
        } else if (p instanceof TwoStatePreference) {
            value = SettingValues.onOff(p, ((TwoStatePreference) p).isChecked());
        } else if (PreferenceConfiguration.BITRATE_PREF_STRING.equals(key) && !wide
                && p instanceof SeekBarPreference) {
            // The phone card's pill is a fifth of the width: the number alone, "Mbps" below it.
            value = mbps(((SeekBarPreference) p).getValue());
        } else {
            value = SettingValues.display(p);
        }
        return new SettingsHeader.Pin(key, label, value != null ? value : "", changed, fromProfile);
    }

    private CharSequence pinLabel(Preference p) {
        String key = p.getKey();
        if (PreferenceConfiguration.RESOLUTION_PREF_STRING.equals(key)) {
            return getString(wide ? R.string.settings_pin_resolution
                    : R.string.settings_pin_resolution_short);
        }
        if (PreferenceConfiguration.FPS_PREF_STRING.equals(key)) {
            return getString(R.string.settings_pin_fps);
        }
        if (PreferenceConfiguration.BITRATE_PREF_STRING.equals(key)) {
            return getString(wide ? R.string.settings_pin_bitrate : R.string.settings_pin_bitrate_short);
        }
        if ("video_format".equals(key)) {
            return getString(R.string.settings_pin_codec);
        }
        if ("checkbox_enable_hdr".equals(key)) {
            return getString(R.string.settings_pin_hdr);
        }
        return p.getTitle() != null ? p.getTitle() : key;
    }

    // The codec as the card names it, short: "Auto", "AV1", "HEVC", "H.264". The list's own
    // entries ("Automatic (Recommended)", "Prefer HEVC") do not fit a fifth of the card.
    @Nullable
    private CharSequence codec(@Nullable String value, @Nullable CharSequence fallback) {
        if (value == null) {
            return fallback;
        }
        switch (value) {
            case "auto":
                return getString(R.string.settings_codec_auto);
            case "forceav1":
                return getString(R.string.settings_codec_av1);
            case "forceh265":
                return getString(R.string.settings_codec_hevc);
            case "neverh265":
                return getString(R.string.settings_codec_h264);
            default:
                return fallback;
        }
    }

    // "40", "40.5": a bitrate in Mbps without its unit, for the phone card.
    private static String mbps(int kbps) {
        return kbps % 1000 == 0 ? String.valueOf(kbps / 1000)
                : String.format(Locale.getDefault(), "%.1f", kbps / 1000f);
    }

    /** The "on" chip follows the section of the first row in view. */
    private void updateOnChip() {
        RecyclerView list = listView();
        SettingsAdapter adapter = settingsAdapter();
        if (header == null || list == null || adapter == null || searching
                || !(list.getLayoutManager() instanceof LinearLayoutManager)) {
            return;
        }
        int first = ((LinearLayoutManager) list.getLayoutManager()).findFirstVisibleItemPosition();
        int local = Math.max(0, first - headerOffset());
        String section = null;
        if (first != RecyclerView.NO_POSITION && local < adapter.getItemCount()) {
            Preference p = adapter.getItem(local);
            SectionCategory category = p instanceof SectionCategory ? (SectionCategory) p
                    : p != null ? SettingsIndex.sectionOf(p) : null;
            section = category != null ? SettingsIndex.sectionIdOf(category) : null;
        }
        onSection = section;
        header.setOnSection(section);
    }

    /**
     * The sticky label over the list's top: shown at rest once the header is out of view, it
     * names the section of the row passing under it, with the section's count, or, when the row
     * in focus is in that section, where that row is ("FRAME PACING · 8 OF 34"). A section's own
     * header row passing under the label is hidden, so the two never show the same name twice.
     */
    private void updateSticky() {
        View label = sticky;
        RecyclerView list = listView();
        SettingsAdapter adapter = settingsAdapter();
        if (label == null || stickySection == null || stickyDetail == null) {
            return;
        }
        boolean shown = !searching && list != null && adapter != null && !headerInView(list);
        int edge = shown ? stickyHeight(label, list) : 0;
        SectionCategory section = null;
        int sectionTop = Integer.MAX_VALUE;
        if (list != null && adapter != null) {
            for (int i = 0; i < list.getChildCount(); i++) {
                View child = list.getChildAt(i);
                RecyclerView.ViewHolder holder = list.getChildViewHolder(child);
                int position = holder.getBindingAdapter() == adapter
                        ? holder.getBindingAdapterPosition() : RecyclerView.NO_POSITION;
                Preference p = position != RecyclerView.NO_POSITION
                        && position < adapter.getItemCount() ? adapter.getItem(position) : null;
                if (p == null) {
                    continue;
                }
                boolean sectionRow = p instanceof SectionCategory;
                if (sectionRow) {
                    child.setAlpha(shown && child.getTop() < edge ? 0f : 1f);
                }
                // The row the label's bottom edge crosses: the topmost still showing below it.
                if (child.getBottom() > edge && child.getTop() < sectionTop) {
                    SectionCategory of = sectionRow ? (SectionCategory) p : SettingsIndex.sectionOf(p);
                    if (of != null) {
                        section = of;
                        sectionTop = child.getTop();
                    }
                }
            }
        }
        if (!shown || section == null || section.getTitle() == null) {
            label.setVisibility(View.GONE);
            return;
        }
        label.setVisibility(View.VISIBLE);
        setIfChanged(stickySection, section.getTitle());
        setIfChanged(stickyDetail, stickyDetail(section));
    }

    // Where the focused row is, when it is in the section the label names; else the count.
    @NonNull
    private CharSequence stickyDetail(@NonNull SectionCategory section) {
        Preference focused = preferenceAt(currentFocus());
        int[] place = focused != null && SettingsIndex.sectionOf(focused) == section
                ? SettingsIndex.placeInSection(focused) : null;
        if (place == null) {
            return String.valueOf(section.getVisibleCount());
        }
        SettingsCatalog.Subgroup subgroup = SettingsCatalog.get().subgroupOf(focused.getKey());
        return subgroup != null
                ? getString(R.string.settings_sticky_place, getString(subgroup.title),
                        place[0], place[1])
                : getString(R.string.settings_sticky_place_plain, place[0], place[1]);
    }

    // Whether the list's own header item (a phone's search, card and chips) shows any of itself.
    private boolean headerInView(@NonNull RecyclerView list) {
        if (headerAdapter == null || !headerAdapter.isShown()) {
            return false;
        }
        for (int i = 0; i < list.getChildCount(); i++) {
            View child = list.getChildAt(i);
            if (list.getChildViewHolder(child).getBindingAdapter() == headerAdapter
                    && child.getBottom() > 0) {
                return true;
            }
        }
        return false;
    }

    // The label's height, measured if it has not been laid out yet (it was gone until now).
    private static int stickyHeight(@NonNull View label, @NonNull RecyclerView list) {
        if (label.getHeight() > 0) {
            return label.getHeight();
        }
        label.measure(View.MeasureSpec.makeMeasureSpec(list.getWidth(), View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
        return label.getMeasuredHeight();
    }

    private static void setIfChanged(@NonNull TextView view, @NonNull CharSequence text) {
        if (!TextUtils.equals(view.getText(), text)) {
            view.setText(text);
        }
    }

    // --- Focus ---

    private void onFocusChanged(@Nullable View focus) {
        // Null when touch mode takes focus away (a tap, on this screen or on the panel): the
        // panel keeps the setting it shows, which is what the finger is on.
        if (focus == null || !isOnScreen()) {
            return;
        }
        Preference p = preferenceAt(focus);
        if (p != null && !searching) {
            lastFocusedKey = p.getKey();
        }
        if (p != null) {
            markDefaultFocus(rowOf(focus));
            showOnPanel(p.getKey());
        } else if (searching && header != null && header.contains(focus)) {
            // Typing, or on a filter: the result the field stands for, the first.
            showOnPanel(resultsAdapter != null ? resultsAdapter.firstKey() : null);
        } else {
            // The header at rest, the rail, the bottom bar: the panel's resting card.
            showOnPanel(null);
        }
        updateSticky();
    }

    /**
     * Makes a row the window's default focus, the view a d-pad press gives focus to once touch
     * mode is over (see the class comment); null takes the mark off. Android 8 and later: before
     * that there is no default to set, and the press goes where it always went.
     */
    private void markDefaultFocus(@Nullable View row) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O || row == defaultFocusRow) {
            return;
        }
        if (defaultFocusRow != null) {
            defaultFocusRow.setFocusedByDefault(false);
        }
        defaultFocusRow = row;
        if (row != null) {
            row.setFocusedByDefault(true);
        }
    }

    // The list or results row a focused view is, or is inside; null elsewhere.
    @Nullable
    private View rowOf(@NonNull View focus) {
        RecyclerView list = searching ? results : listView();
        return list != null && isWithin(focus, list) ? list.findContainingItemView(focus) : null;
    }

    @Nullable
    @Override
    public View restoreFocusTarget() {
        if (!isAdded() || getView() == null || header == null) {
            return null;
        }
        if (searching) {
            // The result the panel shows first: a tap there leaves the main window in touch
            // mode, and the d-pad coming back lands on the row the tap changed.
            View row = resultRowFor(pageKey);
            if (row == null) {
                row = firstResult();
            }
            return row != null ? row : header.field();
        }
        View row = rowViewFor(lastFocusedKey);
        if (row != null) {
            return row;
        }
        row = firstRowInView();
        return row != null ? row : header.searchRest();
    }

    @Override
    public void requestInitialFocus() {
        if (!isAdded() || getView() == null) {
            return;
        }
        View target = restoreFocusTarget();
        if (target != null) {
            target.requestFocus();
        }
        RecyclerView list = listView();
        if (list != null && (target == null || header == null || target == header.searchRest())) {
            // Shown for the first time: the rows may not be laid out yet.
            list.post(() -> {
                if (!isOnScreen()) {
                    return;
                }
                View row = rowViewFor(lastFocusedKey);
                if (row == null) {
                    row = firstRowInView();
                }
                if (row != null) {
                    row.requestFocus();
                }
            });
        }
    }

    @Nullable
    private View resultRowFor(@Nullable String key) {
        if (key == null || results == null || resultsAdapter == null) {
            return null;
        }
        int position = resultsAdapter.positionOf(key);
        RecyclerView.ViewHolder holder = position == RecyclerView.NO_POSITION ? null
                : results.findViewHolderForAdapterPosition(position);
        return holder != null && holder.itemView.isShown() ? holder.itemView : null;
    }

    @Nullable
    private View firstResult() {
        if (results == null || resultsAdapter == null) {
            return null;
        }
        int position = resultsAdapter.firstStop();
        RecyclerView.ViewHolder holder = position == RecyclerView.NO_POSITION ? null
                : results.findViewHolderForAdapterPosition(position);
        return holder != null && holder.itemView.isShown() ? holder.itemView : null;
    }

    // --- Back ---

    @Override
    public boolean hasBackWork() {
        return searching;
    }

    @Override
    public void onBack() {
        leaveSearch();
    }

    // --- Keys ---

    @Override
    public boolean onSectionKey(KeyEvent event) {
        if (!isOnScreen() || header == null) {
            return false;
        }
        int code = event.getKeyCode();
        boolean first = event.getRepeatCount() == 0;
        View focus = currentFocus();
        switch (code) {
            case KeyEvent.KEYCODE_BUTTON_Y:
                if (first) {
                    enterSearch(true);
                }
                return true;
            case KeyEvent.KEYCODE_BUTTON_L1:
            case KeyEvent.KEYCODE_BUTTON_R1:
                if (first) {
                    cycleChip(code == KeyEvent.KEYCODE_BUTTON_R1 ? 1 : -1, focus);
                }
                return true;
            case KeyEvent.KEYCODE_BUTTON_X: {
                if (!first) {
                    return true;
                }
                Preference p = preferenceAt(focus);
                if (p == null) {
                    String pin = header.pinKeyOf(focus);
                    p = pin != null ? find(pin) : null;
                }
                return p != null && focus != null && showRowMenu(p, focus);
            }
            case KeyEvent.KEYCODE_DPAD_LEFT:
            case KeyEvent.KEYCODE_DPAD_RIGHT: {
                Preference p = preferenceAt(focus);
                Boolean moved = p != null
                        ? editInPlace(p, code == KeyEvent.KEYCODE_DPAD_RIGHT ? 1 : -1) : null;
                if (moved == null) {
                    return false;
                }
                // At an end the first press goes on (LEFT to the rail); a held key does not, or
                // holding it down a list would carry focus off the row once the list ran out.
                return moved || !first;
            }
            case KeyEvent.KEYCODE_BUTTON_A:
                if (searching && focus == header.field() && first) {
                    showKeyboard();
                    return true;
                }
                return false;
            default:
                break;
        }
        // Typed on a hardware keyboard away from the field: a search starts, and since the key
        // is not consumed, the activity hands it on to the field, where it is typed.
        if (first && startsTyping(event) && focus != header.field()) {
            enterSearch(false);
        }
        return false;
    }

    /**
     * Left or Right on a row, without a dialog. Null for a row that has no next value (an
     * action, a text): the key then moves focus as usual.
     */
    @Nullable
    private static Boolean editInPlace(@NonNull Preference p, int dir) {
        if (p instanceof ListPreference) {
            return SettingsEditor.cycle((ListPreference) p, dir);
        }
        if (p instanceof SeekBarPreference) {
            return SettingsEditor.step((SeekBarPreference) p, dir);
        }
        if (p instanceof TwoStatePreference) {
            return SettingsEditor.set((TwoStatePreference) p, dir > 0);
        }
        return null;
    }

    // A letter or digit only: space and enter confirm a focused row, and a gamepad button has
    // no character at all.
    private static boolean startsTyping(KeyEvent event) {
        if (event.isCtrlPressed() || event.isAltPressed() || event.isMetaPressed()) {
            return false;
        }
        int c = event.getUnicodeChar();
        return c != 0 && Character.isLetterOrDigit(c);
    }

    /**
     * LB / RB. At rest, from the chip in focus, the card, or the section of the row in focus (of
     * the first row in view otherwise), to the previous or next chip, jumping there. Searching,
     * to the previous or next filter.
     */
    private void cycleChip(int dir, @Nullable View focus) {
        if (header == null) {
            return;
        }
        if (searching) {
            List<SettingsSearch.Filter> filters = header.filterChips();
            int next = filters.indexOf(filter) + dir;
            if (next >= 0 && next < filters.size()) {
                onFilterChosen(filters.get(next));
                View chip = header.chipAt(next);
                if (chip != null && header.contains(focus)) {
                    chip.requestFocus();
                }
            }
            return;
        }
        List<String> targets = header.jumpTargets();
        int base;
        String chip = header.jumpOf(focus);
        if (!"".equals(chip)) {
            base = targets.indexOf(chip);
        } else if (header.isOnCard(focus)) {
            // The card is above the first section: Pinned on a wide window's chips, before the
            // first chip on a phone's.
            base = targets.indexOf(null);
        } else {
            Preference p = preferenceAt(focus);
            SectionCategory section = p != null ? SettingsIndex.sectionOf(p) : null;
            String id = section != null ? SettingsIndex.sectionIdOf(section) : onSection;
            base = id != null ? targets.indexOf(id) : -1;
        }
        int next = base + dir;
        if (next < 0 || next >= targets.size()) {
            return;
        }
        onJumpChosen(targets.get(next));
    }

    // --- Header choices ---

    @Override
    public void onSearchRequested() {
        enterSearch(true);
    }

    @Override
    public void onSearchLeave() {
        leaveSearch();
    }

    @Override
    public void onQueryChanged(@NonNull String query) {
        if (!searching) {
            return;
        }
        runSearch();
    }

    @Override
    public void onQuerySubmitted() {
        hideKeyboard();
        if (results != null) {
            results.post(() -> {
                View row = firstResult();
                if (row != null) {
                    row.requestFocus();
                }
            });
        }
    }

    @Override
    public void onProfileRequested(@NonNull View anchor) {
        ProfilesManager profiles = ProfilesManager.getInstance();
        SettingsProfile active = profiles.getActive();
        MenuSheet sheet = new MenuSheet(requireContext())
                .setTitle(getString(R.string.settings_pin_profile));
        for (SettingsProfile profile : profiles.getProfiles()) {
            final SettingsProfile chosen = profile;
            sheet.addChecked(profile.getName(),
                    active != null && active.getUuid().equals(profile.getUuid()),
                    () -> ProfilesManager.getInstance().setActive(chosen.getUuid()));
        }
        if (!profiles.getProfiles().isEmpty()) {
            sheet.addChecked(getString(R.string.settings_profile_none), active == null,
                    () -> ProfilesManager.getInstance().setActive(null));
        }
        sheet.add(getString(R.string.settings_profile_manage),
                () -> startActivity(new Intent(requireContext(), ProfilesActivity.class)));
        show(sheet, anchor);
    }

    @Override
    public void onPinChosen(@NonNull String key) {
        scrollToKey(key, true);
    }

    @Override
    public boolean onPinLongPressed(@NonNull String key, @NonNull View pin) {
        Preference p = find(key);
        return p != null && showRowMenu(p, pin);
    }

    @Override
    public void onJumpChosen(@Nullable String sectionId) {
        if (header == null) {
            return;
        }
        if (sectionId == null) {
            focusCard();
            return;
        }
        PreferenceScreen screen = screen();
        if (screen == null) {
            return;
        }
        Preference row = SettingsIndex.firstRow(screen, sectionId);
        scrollToKey(SectionCategory.KEY_PREFIX + sectionId, false);
        header.setOnSection(sectionId);
        RecyclerView list = listView();
        if (row != null && list != null) {
            final String key = row.getKey();
            list.post(() -> {
                View view = rowViewFor(key);
                if (view != null) {
                    view.requestFocus();
                }
            });
        }
    }

    private void focusCard() {
        if (header == null) {
            return;
        }
        RecyclerView list = listView();
        if (headerInList() && list != null) {
            // The card is the list's first item on a phone: back to the top for it.
            list.scrollToPosition(0);
            list.post(() -> {
                View pin = header != null ? header.firstPin() : null;
                if (pin != null) {
                    pin.requestFocus();
                }
            });
            return;
        }
        View pin = header.firstPin();
        if (pin != null) {
            pin.requestFocus();
        }
    }

    @Override
    public void onFilterChosen(@NonNull SettingsSearch.Filter chosen) {
        filter = chosen;
        runSearch();
    }

    @Override
    public void onChangedChosen() {
        enterSearch(false);
        filter = SettingsSearch.Filter.CHANGED;
        runSearch();
        if (results != null) {
            results.post(() -> {
                View row = firstResult();
                if (row != null) {
                    row.requestFocus();
                }
            });
        }
    }

    // --- Search ---

    private void enterSearch(boolean keyboard) {
        if (header == null || results == null || listContainer == null) {
            return;
        }
        if (!searching) {
            View focus = currentFocus();
            Preference p = preferenceAt(focus);
            keyBeforeSearch = p != null ? p.getKey() : null;
            viewBeforeSearch = p == null ? focus : null;
            RecyclerView list = listView();
            scrollBeforeSearch = list != null && list.getLayoutManager() != null
                    ? list.getLayoutManager().onSaveInstanceState() : null;
            searching = true;
            filter = SettingsSearch.Filter.ALL;
            header.setSearching(true);
            header.resetChips();
            placeHeader();
            listContainer.setVisibility(View.GONE);
            results.setVisibility(View.VISIBLE);
            host.backWorkChanged();
            runSearch();
        }
        header.field().requestFocus();
        if (keyboard) {
            showKeyboard();
        }
    }

    private void leaveSearch() {
        if (!searching || header == null || results == null || listContainer == null
                || resultsAdapter == null) {
            return;
        }
        hideKeyboard();
        searching = false;
        filter = SettingsSearch.Filter.ALL;
        header.setSearching(false);
        header.resetChips();
        resultsAdapter.submit(Collections.<SettingsResultsAdapter.Item>emptyList(), null);
        results.setVisibility(View.GONE);
        listContainer.setVisibility(View.VISIBLE);
        placeHeader();
        // The list as it was left, header and all, before anything is focused in it: on a
        // phone the header was taken out and put back, and the rows would otherwise stay where
        // the header's return pushed them, scrolled past it.
        RecyclerView list = listView();
        Parcelable scroll = scrollBeforeSearch;
        scrollBeforeSearch = null;
        if (list != null && list.getLayoutManager() != null) {
            if (scroll != null) {
                list.getLayoutManager().onRestoreInstanceState(scroll);
            } else {
                list.scrollToPosition(0);
            }
        }
        refreshHeader();
        host.backWorkChanged();

        final String key = keyBeforeSearch;
        final View view = viewBeforeSearch;
        keyBeforeSearch = null;
        viewBeforeSearch = null;
        if (key != null && scrollToKey(key, true)) {
            return;
        }
        View target = view != null && view.isAttachedToWindow() && view.isShown() ? view
                : header.searchRest();
        target.requestFocus();
        if (!target.isFocused()) {
            // Laid out again after the header moved; ask once that is done.
            target.post(target::requestFocus);
        }
    }

    /** Runs the query against the live list and shows what it finds. */
    private void runSearch() {
        PreferenceScreen screen = screen();
        SettingsAdapter adapter = settingsAdapter();
        if (header == null || resultsAdapter == null || results == null || screen == null
                || adapter == null) {
            return;
        }
        // Built again each time, from the rows shown right now: a row can come and go at run
        // time (the companion row), and 144 entries cost nothing to fold.
        SettingsSearch search = SettingsIndex.search(screen, SettingsCatalog.get());
        Set<String> changed = SettingsIndex.changedKeys(screen, adapter.getDefaults(), prefs);
        String query = header.query();
        SettingsSearch.Result result = search.search(query, filter, changed);
        // Counted whatever the filter in force, for the Changed chip.
        int changedMatches = filter.changedOnly ? result.total
                : search.search(query, SettingsSearch.Filter.CHANGED, changed).total;
        header.bindFilters(result, filter, changedMatches);
        header.setAlsoMatches(result.usedSynonyms.isEmpty() ? null
                : TextUtils.join(", ", result.usedSynonyms));

        Context context = requireContext();
        List<SettingsResultsAdapter.Item> items = new ArrayList<>();
        if (result.groups.isEmpty()) {
            boolean noQuery = SettingsSearch.fold(query).isEmpty();
            if (filter.changedOnly && changed.isEmpty()) {
                items.add(SettingsResultsAdapter.Item.note(getString(R.string.settings_nothing_changed)));
            } else if (!noQuery) {
                items.add(SettingsResultsAdapter.Item.note(
                        getString(R.string.settings_no_match, query.trim())));
                Preference help = screen.findPreference(KEY_HELP);
                if (help != null && help.isVisible() && help.getTitle() != null) {
                    items.add(SettingsResultsAdapter.Item.help(help.getTitle()));
                }
            }
        } else {
            for (SettingsSearch.Group group : result.groups) {
                items.add(SettingsResultsAdapter.Item.header(group.sectionTitle));
                for (SettingsSearch.Entry entry : group.entries) {
                    items.add(SettingsResultsAdapter.Item.row(entry.key,
                            SettingsIndex.breadcrumb(context, entry)));
                }
            }
        }
        boolean focusInResults = results.hasFocus();
        resultsAdapter.submit(items, query);
        if (header.contains(currentFocus())) {
            // Still typing: the panel follows the first result as the query changes.
            showOnPanel(resultsAdapter.firstKey());
        }
        if (focusInResults) {
            // The focused row was filtered away and took focus with it: the field has it back.
            results.post(() -> {
                if (header != null && results != null && !results.hasFocus() && isOnScreen()
                        && currentFocus() == null) {
                    header.field().requestFocus();
                }
            });
        }
    }

    // --- A list row's choices ---

    /**
     * A list row's A or tap (in the list or among the results), as androidx asks before it opens
     * the row's dialog. With the panel up the choices are already there, so the panel is bound
     * to this row instead and nothing covers the main screen; without it, the choices open in
     * {@link SettingsListDialog}. Decided at the click, from the panel as it is then.
     */
    @SuppressWarnings("deprecation") // androidx finds the dialog's preference by target fragment
    @Override
    public boolean onPreferenceDisplayDialog(@NonNull PreferenceFragmentCompat caller,
                                             @NonNull Preference pref) {
        String key = pref.getKey();
        if (!(pref instanceof ListPreference) || key == null || !isAdded()) {
            return false;
        }
        if (SettingsLayout.choicesOnPanel(panelUp, SettingsIndex.kindOf(pref))) {
            hideKeyboard();
            if (!searching) {
                lastFocusedKey = key;
            }
            // A tap leaves the row unfocused in touch mode: the d-pad comes back to it.
            markDefaultFocus(searching ? resultRowFor(key) : rowViewFor(key));
            showOnPanel(key);
            return true;
        }
        FragmentManager manager = caller.getParentFragmentManager();
        if (manager.findFragmentByTag(DIALOG_TAG) != null || manager.isStateSaved()) {
            return true;
        }
        SettingsListDialog dialog = SettingsListDialog.newInstance(key);
        dialog.setTargetFragment(caller, 0);
        dialog.show(manager, DIALOG_TAG);
        return true;
    }

    // --- Glyphs ---

    private void applyGlyphs() {
        if (header != null) {
            header.setWide(wide, gamepadConnected());
        }
    }

    /** Whether a gamepad is connected, whose buttons the header's glyphs would name. */
    private static boolean gamepadConnected() {
        for (int id : InputDevice.getDeviceIds()) {
            InputDevice device = InputDevice.getDevice(id);
            if (device != null && !device.isVirtual()
                    && (device.getSources() & InputDevice.SOURCE_GAMEPAD) == InputDevice.SOURCE_GAMEPAD) {
                return true;
            }
        }
        return false;
    }

    // --- Results ---

    @Nullable
    @Override
    public Preference find(@NonNull String key) {
        StreamPreferencesFragment list = list();
        return list != null ? list.findPreference(key) : null;
    }

    @Nullable
    @Override
    public SettingsDefaults defaults() {
        SettingsAdapter adapter = settingsAdapter();
        return adapter != null ? adapter.getDefaults() : null;
    }

    @Nullable
    @Override
    public SharedPreferences prefs() {
        return prefs;
    }

    /**
     * A result's click is its preference's own, as on the list: a switch flips (through its
     * change listener), a list or slider opens its dialog, an action runs.
     */
    @SuppressLint("RestrictedApi") // the click a row in the list would give it, from elsewhere
    @Override
    public void onResultClicked(@NonNull String key) {
        Preference p = find(key);
        if (p != null) {
            hideKeyboard();
            p.performClick();
        }
    }

    @Override
    public boolean onResultLongPressed(@NonNull String key, @NonNull View row) {
        Preference p = find(key);
        return p != null && showRowMenu(p, row);
    }

    @SuppressLint("RestrictedApi")
    @Override
    public void onSearchHelp() {
        Preference help = find(KEY_HELP);
        if (help != null) {
            hideKeyboard();
            help.performClick();
        }
    }

    // --- A row's menu ---

    /**
     * X or a long-press on a row or a pin: "Reset to default ({default})" while the value is
     * not its default, and "Pin to top" or "Unpin" for anything but the essentials. No single
     * press resets a value: the reset is always a choice in this menu.
     *
     * @return false when the row has nothing to offer
     */
    private boolean showRowMenu(@NonNull Preference p, @NonNull View anchor) {
        final String key = p.getKey();
        final SettingsDefaults defaults = defaults();
        if (key == null || !isAdded()) {
            return false;
        }
        MenuSheet sheet = new MenuSheet(requireContext());
        if (p.getTitle() != null) {
            sheet.setTitle(p.getTitle());
        }
        if (defaults != null && p.isEnabled() && defaults.isChanged(p, prefs)) {
            CharSequence def = SettingValues.displayFor(p, defaults.defaultFor(key, prefs));
            if (def != null) {
                sheet.add(getString(R.string.settings_row_reset, def),
                        () -> SettingsEditor.reset(p, defaults, prefs));
            }
        }
        if (!PinnedSettings.isEssential(key)) {
            if (pinned.isPinned(key)) {
                sheet.add(getString(R.string.settings_row_unpin), () -> {
                    pinned.unpin(key);
                    refreshHeader();
                });
            } else {
                sheet.add(getString(R.string.settings_row_pin), () -> {
                    if (!pinned.pin(key)) {
                        Toast.makeText(requireContext(), getString(R.string.settings_pins_full,
                                PinnedSettings.MAX_USER_PINS), Toast.LENGTH_SHORT).show();
                    }
                    refreshHeader();
                });
            }
        }
        if (sheet.isEmpty()) {
            return false;
        }
        show(sheet, anchor);
        return true;
    }

    private void show(@NonNull MenuSheet sheet, @NonNull View anchor) {
        hideKeyboard();
        if (wide) {
            sheet.showAnchored(anchor);
        } else {
            sheet.showBottom(requireContext());
        }
    }

    // --- The companion panel ---

    /** What the panel is to show: a setting by key, or the pinned essentials for null. */
    private void showOnPanel(@Nullable String key) {
        pageKey = key;
        publishPage();
    }

    /**
     * Hands the panel the page for {@link #pageKey}, built from the live preference as it stands;
     * takes it off when the panel is down or the section is not on screen. An unchanged page is
     * dropped by the panel's state, so this may run on every focus move and stored change.
     */
    private void publishPage() {
        if (!panelUp || !isOnScreen() || host == null) {
            clearPage();
            return;
        }
        CompanionPage next = null;
        if (pageKey != null) {
            Preference p = find(pageKey);
            if (p != null && p.isVisible()) {
                next = SettingsChoicesPage.of(p, defaults(), prefs);
            }
        }
        if (next == null) {
            // In the header, or the setting is gone from this list (a reload, a device removal).
            next = pinnedPage();
        }
        if (next == null) {
            // The list is not up yet; it publishes once wired.
            return;
        }
        page = next;
        host.companionPages().publish(this, next);
    }

    private void clearPage() {
        if (page != null && host != null) {
            page = null;
            host.companionPages().clear(this);
        }
    }

    /** The five essentials as the card shows them, for the panel; null before the list is up. */
    @Nullable
    private SettingsPinnedPage pinnedPage() {
        PreferenceScreen screen = screen();
        SettingsAdapter adapter = settingsAdapter();
        if (screen == null || adapter == null) {
            return null;
        }
        SettingsDefaults defaults = adapter.getDefaults();
        SettingsProfile profile = ProfilesManager.getInstance().getActive();
        Map<String, Object> overrides = profile != null ? profile.getOptions() : null;
        List<SettingsPinnedPage.Row> rows = new ArrayList<>();
        for (String key : PinnedSettings.ESSENTIALS) {
            Preference p = screen.findPreference(key);
            if (p == null || !p.isVisible()) {
                continue;
            }
            SettingsHeader.Pin pin = pinFor(p, defaults.isChanged(p, prefs), overrides);
            rows.add(new SettingsPinnedPage.Row(key, pin.label.toString(), pin.value.toString(),
                    pin.changed, pin.fromProfile));
        }
        String name = ProfilesManager.getInstance().getActiveName().trim();
        return new SettingsPinnedPage(name.isEmpty() ? null : name, rows);
    }

    /**
     * A tap on the panel (CompanionPages.PageOwner): a choice, the switch, − or +, through
     * {@link SettingsEditor} as Left and Right on the row go, so the change listeners run as
     * from a dialog. A disabled setting, or a refused change, leaves the value; the page is built
     * again from the preference either way. The feed hands the d-pad back to the main screen
     * after this, on the row it was on.
     */
    @Override
    public void onPageTap(int stopId) {
        if (!isOnScreen()) {
            return;
        }
        CompanionPage shown = page;
        if (shown instanceof SettingsPinnedPage) {
            SettingsPinnedPage.Row row = ((SettingsPinnedPage) shown).rowAt(stopId);
            if (row != null) {
                goToPinnedRow(row.key);
            }
            return;
        }
        if (!(shown instanceof SettingsChoicesPage)) {
            return;
        }
        SettingsChoicesPage choices = (SettingsChoicesPage) shown;
        Preference p = find(choices.key);
        if (p == null || !p.isEnabled()) {
            return;
        }
        if (p instanceof ListPreference) {
            SettingsChoicesPage.Choice choice = choices.choiceAt(stopId);
            if (choice != null) {
                SettingsEditor.setChoice((ListPreference) p, choice.value);
            }
        } else if (p instanceof TwoStatePreference && stopId == SettingsChoicesPage.STOP_SWITCH) {
            TwoStatePreference toggle = (TwoStatePreference) p;
            SettingsEditor.set(toggle, !toggle.isChecked());
        } else if (p instanceof SeekBarPreference && (stopId == SettingsChoicesPage.STOP_MINUS
                || stopId == SettingsChoicesPage.STOP_PLUS)) {
            SettingsEditor.step((SeekBarPreference) p,
                    stopId == SettingsChoicesPage.STOP_PLUS ? 1 : -1);
        }
    }

    /**
     * An essential touched on the panel: its row in the list, as its pin on the card goes. The
     * tap may have left the main window in touch mode, where the row cannot take focus; it is
     * remembered, so the d-pad coming back lands there.
     */
    private void goToPinnedRow(@NonNull String key) {
        if (searching) {
            leaveSearch();
        }
        lastFocusedKey = key;
        scrollToKey(key, true);
    }

    // --- Keyboard ---

    private void showKeyboard() {
        InputMethodManager imm = ContextCompat.getSystemService(requireContext(), InputMethodManager.class);
        if (imm != null && header != null) {
            imm.showSoftInput(header.field(), 0);
        }
    }

    private void hideKeyboard() {
        if (!isAdded() || header == null) {
            return;
        }
        InputMethodManager imm = ContextCompat.getSystemService(requireContext(), InputMethodManager.class);
        if (imm != null) {
            imm.hideSoftInputFromWindow(header.field().getWindowToken(), 0);
        }
    }

    // --- Helpers ---

    // The window's focused view, as Activity.getCurrentFocus() answers it, read from this
    // section's own window so a test window that was never given window focus answers too.
    @Nullable
    private View currentFocus() {
        View view = getView();
        return view != null ? view.getRootView().findFocus() : null;
    }

    // Shown to the user: added, with a view, and the section not hidden. A hidden section's
    // fragment is still resumed, so the lifecycle cannot tell.
    private boolean isOnScreen() {
        View view = getView();
        return isAdded() && view != null && view.isShown();
    }

    private static boolean isWithin(@NonNull View view, @NonNull View ancestor) {
        for (View v = view; v != null; ) {
            if (v == ancestor) {
                return true;
            }
            ViewParent parent = v.getParent();
            v = parent instanceof View ? (View) parent : null;
        }
        return false;
    }
}
