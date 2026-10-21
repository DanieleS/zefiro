package com.limelight.stats;

import android.content.Context;
import android.content.res.ColorStateList;
import android.content.res.Configuration;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.SpannableString;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.TextUtils;
import android.text.style.ForegroundColorSpan;
import android.text.style.StyleSpan;
import android.text.style.TypefaceSpan;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.core.view.ViewCompat;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.ViewModelProvider;

import com.limelight.LimeLog;
import com.limelight.R;
import com.limelight.computers.HostEntry;
import com.limelight.computers.HostLibrary;
import com.limelight.computers.HostRepository;
import com.limelight.library.HostSheet;
import com.limelight.library.LibraryApp;
import com.limelight.main.MainHost;
import com.limelight.main.SectionBack;
import com.limelight.main.SectionFocus;
import com.limelight.main.SectionKeys;
import com.limelight.main.StackedSectionFragment;
import com.limelight.main.WindowClass;
import com.limelight.nvstream.http.Achievement;
import com.limelight.nvstream.http.AppStats;
import com.limelight.pcs.HostCardBinder;
import com.limelight.ui.GamepadHintsView;
import com.limelight.ui.SegmentedControlView;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.RejectedExecutionException;

/**
 * How much is played on a PC, a week, a month or a year at a time, then the library at large:
 * CouchPilot's statistics page, served by Vibepollo's {@code /appstats}, laid out chart first
 * after the approved Stats mock (C). The main screen's Stats section.
 *
 * <p>The chart is the page and the way around it. Left and right pick a bar, which costs no
 * request: the tooltip over it says what it stands for, and the strip under it always shows the
 * page's own most played. On a year, A on a month opens that month (one request, with a spinner
 * in the tooltip until it lands); B climbs from a month back to its year with that month picked.
 * A month is the deepest level, since the host has no day range. The bumpers step the period at
 * either level from anywhere, and X and Y cycle week, month and year. Each game, in the strip and
 * in the lists below, opens its own page ({@link GamePageFragment}), pushed over this one on the
 * section's stack.
 *
 * <pre>
 *   YEAR 2026 ──A on October──▶ (spinner) ──lands──▶ MONTH October ──B──▶ YEAR 2026, October picked
 *                                         ──fails──▶ YEAR 2026, a toast
 * </pre>
 *
 * <h3>The host</h3>
 *
 * It follows the main screen's selected host: that host's {@link HostLibrary} session, shared
 * with the Library, and the first page the library's probe already fetched, so the section opens
 * on this week without a spinner. A host switch starts the state over. In a compact window the
 * host is a chip that opens the host sheet, as in the Library.
 *
 * <h3>State</h3>
 *
 * The period asked for, the page on screen, its picked bar and a month on its way all live in
 * {@link StatsViewModel}, with the thread the pages are asked for on, so they outlive the views.
 * Answers come back through a main {@link Handler} and are dropped when the view has gone or a
 * later request was made. Shown again after another section, the page is redrawn at once and
 * asked for again quietly, without the spinner. Whatever describes the page (the crumb, the
 * summary, the bars) is drawn from the page shown; only the remote's range and buttons follow
 * the period asked for, so they answer a press at once.
 *
 * <h3>Layout</h3>
 *
 * Everything is on the main screen in this step, on every device: the remote (Week / Month /
 * Year and the period row), the summary, the chart, the strip, then the lower half (the latest
 * unlocks, the games to pick up again, the library's totals). The window's width, never the
 * device, decides the rest: the host chip or text, the crumb, and the strip's row of five cards
 * or five-column grid of covers.
 *
 * <h3>Focus</h3>
 *
 * Entered, focus goes to the chart, on the picked bar. A new period rebuilds the rows, and focus
 * lands on the same place in the new ones ({@link FocusMemo}); a month opened from the year puts
 * it on the chart again. The focus gate returns to the view that last held focus in the section.
 * Focus is only ever moved while it is in this section or nowhere: an answer landing while the
 * d-pad is on the rail leaves it there.
 *
 * Main thread only; every callback checks that the view still exists.
 */
public class StatsFragment extends Fragment
        implements SectionKeys, SectionFocus, SectionBack, StackedSectionFragment.SectionHiddenListener {

    private static final String[] RANGES = {
            AppStats.RANGE_WEEK, AppStats.RANGE_MONTH, AppStats.RANGE_YEAR};

    // The mock's strip: five games at most, in a row or in a five-column grid.
    private static final int STRIP_MAX = 5;

    private final Handler main = new Handler(Looper.getMainLooper());

    private MainHost host;

    private StatsViewModel model;

    // The connection the pages and covers come through: the selected host's library's, for the
    // view's life.
    @Nullable
    private HostSession session;
    @Nullable
    private HostLibrary boundLibrary;
    @Nullable
    private CoverLoader covers;
    @Nullable
    private StatsText text;
    // The page the views show now, so showing the section again does not rebuild the same rows.
    @Nullable
    private AppStats drawn;
    // The window class the views are arranged for; null before the first arrangement.
    @Nullable
    private Boolean compactApplied;

    // The view that last held focus in the section, for the focus gate.
    @Nullable
    private View lastFocused;
    @Nullable
    private ViewTreeObserver.OnGlobalFocusChangeListener focusWatch;

    private View root;
    private View header;
    private TextView hostNameView;
    private View hostChip;
    private View hostChipDot;
    private TextView hostChipName;
    private View crumb;
    private View crumbTag;
    private TextView crumbText;
    private View contentView;
    private SegmentedControlView ranges;
    private View climbView;
    private TextView climbLabel;
    private TextView periodTitle;
    private ImageView previousView;
    private ImageView nextView;
    private View statusView;
    private View statusProgress;
    private View statusTitle;
    private View statusHint;
    private View bodyView;
    private TextView untrackedView;
    private View trackedView;
    private TextView playedLabel;
    private LinearLayout summaryRow;
    private View summaryDetail;
    private TextView totalView;
    private TextView deltaView;
    private TextView summaryLine;
    private StatsBarsView chart;
    private View stripSection;
    private LinearLayout stripRow;
    private View stripLabel;
    private TextView stripTitle;
    private TextView stripSubtitle;
    private LinearLayout stripList;
    private TextView nothingPlayed;
    private GamepadHintsView hints;
    private LinearLayout lowerView;
    private LinearLayout listsView;
    private View totalsView;
    private View achievementsSection;
    private TextView achievementsCount;
    private LinearLayout achievementsList;
    private TextView libraryHours;
    private TextView libraryGames;
    private TextView libraryGamesLabel;
    private TextView libraryNeverPlayed;
    private TextView libraryThisYear;
    private View resumeSection;
    private LinearLayout resumeList;

    public StatsFragment() {
    }

    @Override
    public void onAttach(@NonNull Context context) {
        super.onAttach(context);
        if (!(context instanceof MainHost)) {
            throw new IllegalStateException("StatsFragment needs a MainHost activity");
        }
        host = (MainHost) context;
    }

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        model = new ViewModelProvider(this).get(StatsViewModel.class);
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_stats, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        text = new StatsText(requireContext());
        root = view;
        header = view.findViewById(R.id.statsHeader);
        hostNameView = view.findViewById(R.id.statsHostName);
        hostChip = view.findViewById(R.id.statsHostChip);
        hostChipDot = view.findViewById(R.id.statsHostChipDot);
        hostChipName = view.findViewById(R.id.statsHostChipName);
        hostChip.setOnClickListener(v -> HostSheet.show(requireActivity(), host, v));
        crumb = view.findViewById(R.id.statsCrumb);
        crumbTag = view.findViewById(R.id.statsCrumbTag);
        crumbText = view.findViewById(R.id.statsCrumbText);
        contentView = view.findViewById(R.id.statsContent);

        ranges = view.findViewById(R.id.statsRanges);
        ranges.setSegments(getString(R.string.stats_range_week),
                getString(R.string.stats_range_month), getString(R.string.stats_range_year));
        ranges.setOnSegmentChosenListener(index -> setRange(RANGES[index]));
        climbView = view.findViewById(R.id.statsClimb);
        climbLabel = view.findViewById(R.id.statsClimbLabel);
        climbView.setOnClickListener(v -> climb());
        periodTitle = view.findViewById(R.id.statsPeriodTitle);
        previousView = view.findViewById(R.id.statsPrevious);
        nextView = view.findViewById(R.id.statsNext);
        previousView.setOnClickListener(v -> stepPeriod(-1));
        nextView.setOnClickListener(v -> stepPeriod(1));

        statusView = view.findViewById(R.id.statsStatus);
        statusProgress = view.findViewById(R.id.statsStatusProgress);
        statusTitle = view.findViewById(R.id.statsStatusTitle);
        statusHint = view.findViewById(R.id.statsStatusHint);
        statusView.setOnClickListener(v -> load());
        bodyView = view.findViewById(R.id.statsBody);

        untrackedView = view.findViewById(R.id.statsUntracked);
        trackedView = view.findViewById(R.id.statsTracked);
        playedLabel = view.findViewById(R.id.statsPlayedLabel);
        summaryRow = view.findViewById(R.id.statsSummaryRow);
        summaryDetail = view.findViewById(R.id.statsSummaryDetail);
        totalView = view.findViewById(R.id.statsTotal);
        deltaView = view.findViewById(R.id.statsDelta);
        summaryLine = view.findViewById(R.id.statsSummaryLine);
        chart = view.findViewById(R.id.statsChart);
        chart.setOnSelectionChangedListener(this::onBarPicked);
        chart.setOnBarActivatedListener(this::onBarActivated);

        stripSection = view.findViewById(R.id.statsStrip);
        stripRow = view.findViewById(R.id.statsStripRow);
        stripLabel = view.findViewById(R.id.statsStripLabel);
        stripTitle = view.findViewById(R.id.statsStripTitle);
        stripSubtitle = view.findViewById(R.id.statsStripSubtitle);
        stripList = view.findViewById(R.id.statsStripList);
        stripList.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, orr, ob) -> {
            if (r - l != orr - ol) {
                // Posted: a cover's height set inside a layout pass would wait for the next one.
                v.post(this::fitStripCovers);
            }
        });
        nothingPlayed = view.findViewById(R.id.statsNothingPlayed);
        hints = view.findViewById(R.id.statsHints);

        lowerView = view.findViewById(R.id.statsLower);
        listsView = view.findViewById(R.id.statsLists);
        totalsView = view.findViewById(R.id.statsTotals);
        achievementsSection = view.findViewById(R.id.statsAchievementsSection);
        achievementsCount = view.findViewById(R.id.statsAchievementsCount);
        achievementsList = view.findViewById(R.id.statsAchievementsList);
        libraryHours = view.findViewById(R.id.statsLibraryHours);
        libraryGames = view.findViewById(R.id.statsLibraryGames);
        libraryGamesLabel = view.findViewById(R.id.statsLibraryGamesLabel);
        libraryNeverPlayed = view.findViewById(R.id.statsLibraryNeverPlayed);
        libraryThisYear = view.findViewById(R.id.statsLibraryThisYear);
        resumeSection = view.findViewById(R.id.statsResumeSection);
        resumeList = view.findViewById(R.id.statsResumeList);

        installLayout(view);
        applyWindow();
        focusWatch = (oldFocus, newFocus) -> {
            if (newFocus != null && isInside(newFocus)) {
                lastFocused = newFocus;
            }
            // "A Open October" holds only while the chart has focus.
            if (oldFocus == chart || newFocus == chart) {
                updateHints();
            }
        };
        view.getViewTreeObserver().addOnGlobalFocusChangeListener(focusWatch);
        refreshRemote();

        HostRepository repository = host.hostRepository();
        repository.getLibrary().observe(getViewLifecycleOwner(), this::syncHost);
    }

    @Override
    public void onDestroyView() {
        if (focusWatch != null && root != null && root.getViewTreeObserver().isAlive()) {
            root.getViewTreeObserver().removeOnGlobalFocusChangeListener(focusWatch);
        }
        focusWatch = null;
        lastFocused = null;
        if (covers != null) {
            covers.shutdown();
            covers = null;
        }
        // The library's session is the library's; the next view takes it afresh.
        session = null;
        boundLibrary = null;
        drawn = null;
        compactApplied = null;
        root = null;
        super.onDestroyView();
    }

    @Override
    public void onConfigurationChanged(@NonNull Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        if (getView() != null) {
            applyWindow();
        }
    }

    @Override
    public void onHiddenChanged(boolean hidden) {
        super.onHiddenChanged(hidden);
        onShownChanged(hidden);
    }

    @Override
    public void onSectionHiddenChanged(boolean hidden) {
        onShownChanged(hidden);
    }

    // Hidden or shown, on the section's stack or with the whole section.
    private void onShownChanged(boolean hidden) {
        if (!hidden && isAdded() && getView() != null
                && !StackedSectionFragment.isHiddenInSection(this)) {
            // Back from another section: what was there is shown again at once, and asked for
            // anew, since a stream in between has likely changed it.
            present();
        }
    }

    // --- The host ---

    /**
     * Follows the main screen's selected host: a new host starts the state over, and a session
     * that becomes available (the host was given an address) is taken up. Called on every new
     * library snapshot, which is often, so it does nothing unless one of those changed.
     */
    private void syncHost(@Nullable HostLibrary.Snapshot s) {
        if (!isAdded() || getView() == null) {
            return;
        }
        HostRepository repository = host.hostRepository();
        HostLibrary library = repository.currentLibrary();
        // The snapshot of a host whose library is not open yet comes without one.
        if (library != null && (s == null || !library.getHostUuid().equals(s.hostUuid))) {
            library = null;
        }
        String uuid = s != null ? s.hostUuid : null;
        bindHost(uuid != null ? repository.getHost(uuid) : null, uuid != null);

        boolean changed = model.forHost(uuid);
        if (changed) {
            // Back to this week: the remote says so too, and B has no month to climb from.
            targetChanged();
        }
        if (changed || library != boundLibrary) {
            changed = true;
            boundLibrary = library;
            session = null;
            drawn = null;
            if (covers != null) {
                covers.shutdown();
                covers = null;
            }
        }
        if (session == null && library != null) {
            session = library.session();
            if (session != null) {
                covers = new CoverLoader(requireContext(), session, uuid);
                changed = true;
            }
        }
        // The section's own hide counts too: this page is the root of the Stats stack.
        if (changed && !StackedSectionFragment.isHiddenInSection(this)) {
            present();
        }
    }

    // The host's name: muted text in a wide window, the chip with its status dot in a compact one.
    private void bindHost(@Nullable HostEntry entry, boolean selected) {
        String name = entry != null ? entry.details.name : null;
        hostNameView.setText(name);
        String chipName;
        if (!selected) {
            chipName = getString(R.string.library_choose_pc);
        } else if (name != null && !name.isEmpty()) {
            chipName = name;
        } else {
            chipName = getString(R.string.library_this_pc);
        }
        hostChipName.setText(chipName);
        int dot = entry != null ? HostCardBinder.statusColor(entry.details) : R.color.content_faint;
        ViewCompat.setBackgroundTintList(hostChipDot,
                ColorStateList.valueOf(ContextCompat.getColor(requireContext(), dot)));
        hostChipDot.setVisibility(selected ? View.VISIBLE : View.GONE);
        hostChip.setContentDescription(entry != null
                ? getString(R.string.library_host_chip_a11y, chipName,
                        getString(HostCardBinder.statusLabel(entry.details)))
                : chipName);
    }

    /**
     * Shows what there is for the host now, then asks for the current page. With nothing yet, the
     * page the library's probe fetched stands in when it is the one asked for (this week).
     */
    private void present() {
        AppStats shown = model.shown();
        boolean first = false;
        if (shown == null && boundLibrary != null && model.offset() == 0
                && AppStats.RANGE_WEEK.equals(model.range())) {
            shown = boundLibrary.getFirstStatsPage();
            if (shown != null) {
                model.setShown(shown);
                first = true;
            }
        }
        if (shown != null && shown != drawn) {
            bind(shown, first, false);
        }
        load();
    }

    // --- Layout ---

    /**
     * One centred column, no wider than reads comfortably, measured from this section's width
     * rather than the screen's, since the rail takes its share of a wide window. The header
     * takes the same inset so the title sits over the column.
     */
    private void installLayout(View view) {
        final int maxWidth = getResources().getDimensionPixelSize(R.dimen.stats_max_width);
        final int gutter = getResources().getDimensionPixelSize(R.dimen.stats_gutter);
        view.addOnLayoutChangeListener((v, left, top, right, bottom,
                                        oldLeft, oldTop, oldRight, oldBottom) -> {
            // Padding changes only when the width does, so this settles after one extra pass.
            int side = Math.max(gutter, ((right - left) - maxWidth) / 2);
            applyInset(contentView, side);
            applyInset(header, side);
        });
    }

    private static void applyInset(@Nullable View view, int inset) {
        if (view != null && (view.getPaddingLeft() != inset || view.getPaddingRight() != inset)) {
            view.setPadding(inset, view.getPaddingTop(), inset, view.getPaddingBottom());
        }
    }

    private boolean isCompact() {
        return WindowClass.from(getResources().getConfiguration().screenWidthDp)
                == WindowClass.COMPACT;
    }

    /**
     * Arranges the views for the window's class: in a compact window the host is a chip and the
     * crumb goes (the period row says the same), the summary stacks, and the strip's label sits
     * over a five-column grid of covers; in a wide one the host is text beside the title, the
     * crumb is shown, and the label stands beside a row of five cards. Does nothing when the
     * class has not changed; redraws the page when it has, since the strip's cards differ.
     */
    private void applyWindow() {
        boolean compact = isCompact();
        if (compactApplied != null && compactApplied == compact) {
            return;
        }
        compactApplied = compact;
        // Invisible rather than gone, so it still pushes the chip to the header's end.
        hostNameView.setVisibility(compact ? View.INVISIBLE : View.VISIBLE);
        hostChip.setVisibility(compact ? View.VISIBLE : View.GONE);
        crumb.setVisibility(compact ? View.GONE : View.VISIBLE);

        summaryRow.setOrientation(compact ? LinearLayout.VERTICAL : LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams detail = (LinearLayout.LayoutParams) summaryDetail.getLayoutParams();
        detail.width = compact ? ViewGroup.LayoutParams.MATCH_PARENT : 0;
        detail.weight = compact ? 0 : 1;
        detail.setMarginStart(compact ? 0 : dp(16));
        detail.topMargin = compact ? dp(4) : 0;
        summaryDetail.setLayoutParams(detail);

        stripRow.setOrientation(compact ? LinearLayout.VERTICAL : LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams label = (LinearLayout.LayoutParams) stripLabel.getLayoutParams();
        label.width = compact ? ViewGroup.LayoutParams.MATCH_PARENT
                : getResources().getDimensionPixelSize(R.dimen.stats_strip_label_width);
        label.setMarginEnd(compact ? 0 : dp(12));
        label.bottomMargin = compact ? dp(8) : 0;
        stripLabel.setLayoutParams(label);
        LinearLayout.LayoutParams list = (LinearLayout.LayoutParams) stripList.getLayoutParams();
        list.width = compact ? ViewGroup.LayoutParams.MATCH_PARENT : 0;
        list.weight = compact ? 0 : 1;
        stripList.setLayoutParams(list);

        if (drawn != null) {
            bind(drawn, false, false);
        }
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    // --- Keys and back ---

    @Override
    public boolean onSectionKey(KeyEvent event) {
        if (!isAdded() || getView() == null || event.getAction() != KeyEvent.ACTION_DOWN) {
            return false;
        }
        int code = event.getKeyCode();
        boolean first = event.getRepeatCount() == 0;
        if (code == KeyEvent.KEYCODE_BUTTON_L1 && first) {
            stepPeriod(-1);
            return true;
        }
        if (code == KeyEvent.KEYCODE_BUTTON_R1 && first) {
            stepPeriod(1);
            return true;
        }
        if (code == KeyEvent.KEYCODE_BUTTON_X && first) {
            setRange(StatsFormat.cycleRange(model.range(), -1));
            return true;
        }
        if (code == KeyEvent.KEYCODE_BUTTON_Y && first) {
            setRange(StatsFormat.cycleRange(model.range(), 1));
            return true;
        }
        // A reaches the chart itself (its bar activation), and B the main screen's back.
        return false;
    }

    /** On a month, back (B) climbs to its year; anywhere else it is the section's. */
    @Override
    public boolean hasBackWork() {
        return isAdded() && model != null && AppStats.RANGE_MONTH.equals(model.range());
    }

    @Override
    public void onBack() {
        climb();
    }

    // --- The period ---

    private void setRange(String newRange) {
        if (newRange.equals(model.range())) {
            return;
        }
        model.setRange(newRange);
        targetChanged();
        load();
    }

    private void stepPeriod(int step) {
        int next = StatsFormat.stepOffset(model.offset(), step, AppStats.MIN_OFFSET);
        if (next == model.offset()) {
            return;
        }
        model.setOffset(next);
        refreshRemote();
        load();
    }

    /**
     * From a month up to its year, with that month picked once the year lands. The month asked
     * for is the one climbed from, counted from the PC's today as any page carries it.
     */
    private void climb() {
        if (!AppStats.RANGE_MONTH.equals(model.range())) {
            return;
        }
        AppStats shown = model.shown();
        int[] year = StatsDrill.yearOf(model.offset(), shown != null ? shown.getToday() : null);
        model.climbTo(year[0], year[1]);
        targetChanged();
        load();
    }

    // The range asked for changed: the remote follows, and whether B climbs may have.
    private void targetChanged() {
        refreshRemote();
        host.backWorkChanged();
    }

    /**
     * The remote's state from the period asked for: the range segment, the climb chip (a month
     * only, labelled with its year), and previous and next, which stop at the furthest the host
     * answers and at the period under way.
     */
    private void refreshRemote() {
        if (ranges == null) {
            return;
        }
        String range = model.range();
        for (int i = 0; i < RANGES.length; i++) {
            if (RANGES[i].equals(range)) {
                ranges.setSelected(i);
            }
        }
        boolean previous = model.offset() > AppStats.MIN_OFFSET;
        boolean next = model.offset() < 0;
        // Off the button before it goes: a focused view made unfocusable hands focus to the
        // window's first stop, the rail, rather than to its neighbour.
        if (!next && nextView.isFocused() && previous) {
            previousView.requestFocus();
        } else if (!previous && previousView.isFocused() && next) {
            nextView.requestFocus();
        }
        setEnabled(previousView, previous);
        setEnabled(nextView, next);

        AppStats shown = model.shown();
        int year = -1;
        if (AppStats.RANGE_MONTH.equals(range) && shown != null) {
            year = StatsDrill.calendarYear(
                    StatsDrill.yearOf(model.offset(), shown.getToday())[0], shown.getToday());
        }
        if (year < 0) {
            if (climbView.hasFocus()) {
                // The chip just climbed: the d-pad stays in the period row, or on the range,
                // moved there before the chip goes so it never passes through the rail.
                View segment = ranges.getSegmentView(Math.max(0, ranges.getSelectedIndex()));
                View stop = previousView.isFocusable() || segment == null ? previousView : segment;
                stop.requestFocus();
            }
            climbView.setVisibility(View.GONE);
        } else {
            String label = String.valueOf(year);
            climbLabel.setText(label);
            climbView.setContentDescription(getString(R.string.stats_climb_a11y, label));
            climbView.setVisibility(View.VISIBLE);
        }
        updateHints();
    }

    // Disabled and out of the d-pad's way (a disabled view can still take focus on old APIs):
    // the chip's own disabled ground, and its chevron at a quarter, as the mock dims it.
    private static void setEnabled(ImageView view, boolean enabled) {
        view.setEnabled(enabled);
        view.setFocusable(enabled);
        view.setImageAlpha(enabled ? 255 : 64);
    }

    // --- The chart ---

    /** Left and right, or a tap, picked a bar: a caption, no request. */
    private void onBarPicked(int index) {
        model.setSelectedBar(index);
        updateTooltip();
        updateHints();
    }

    /**
     * A, or a second tap, on the picked bar: on a year, opens that month (one request; the year
     * stays until it lands). On a week's or a month's day, nothing: the tooltip says it all.
     */
    private void onBarActivated(int index) {
        AppStats s = model.shown();
        HostSession connection = session;
        if (s == null || connection == null || !AppStats.RANGE_YEAR.equals(s.getRange())
                || model.pendingDrill() != null || index < 0 || index >= s.getBuckets().size()) {
            return;
        }
        int month = StatsDrill.monthIndex(s.getBuckets().get(index).getDate());
        if (month < 0 || StatsDrill.isFuture(s.getOffset(), month, s.getToday())) {
            return;
        }
        // The year on screen is the one drilled from, even with another asked for meanwhile:
        // that request is superseded, and a failed drill leaves this year as the period.
        if (!AppStats.RANGE_YEAR.equals(model.range()) || model.offset() != s.getOffset()) {
            model.setRange(AppStats.RANGE_YEAR);
            model.setOffset(s.getOffset());
            targetChanged();
        }
        int monthOffset = StatsDrill.monthOffset(s.getOffset(), month, s.getToday());
        int mine = model.startDrill(monthOffset, index);
        updateTooltip();
        updateHints();
        request(connection, mine, AppStats.RANGE_MONTH, monthOffset);
    }

    /**
     * The tooltip over the picked bar: what it stands for, and on a year's month the A to open
     * it, or the spinner while it is on its way.
     */
    private void updateTooltip() {
        AppStats s = model.shown();
        int index = chart.getFocusIndex();
        if (s == null || !s.isTracked() || index < 0 || index >= s.getBuckets().size()) {
            chart.setTooltip(null, false, false);
            return;
        }
        AppStats.Bucket b = s.getBuckets().get(index);
        boolean year = AppStats.RANGE_YEAR.equals(s.getRange());
        StatsViewModel.PendingDrill drill = model.pendingDrill();
        chart.setTooltip(text.barTooltip(s.getRange(), b.getDate(), b.getSeconds()), year,
                year && drill != null && drill.bar == index);
    }

    /**
     * The pad's shortcuts, as the state has them: A opens the picked month while the chart has
     * focus on a year, B climbs from a month, the bumpers step the range's periods, X and Y
     * cycle the ranges. Shown only on a screen driven by the pad.
     */
    private void updateHints() {
        if (hints == null || text == null) {
            return;
        }
        List<GamepadHintsView.Hint> list = new ArrayList<>();
        AppStats s = model.shown();
        int index = chart.getFocusIndex();
        if (s != null && chart.isFocused() && AppStats.RANGE_YEAR.equals(s.getRange())
                && model.pendingDrill() == null && index >= 0 && index < s.getBuckets().size()) {
            list.add(new GamepadHintsView.Hint(getString(R.string.stats_hint_open,
                    text.monthName(s.getBuckets().get(index).getDate())), "A"));
        }
        if (AppStats.RANGE_MONTH.equals(model.range())) {
            list.add(new GamepadHintsView.Hint(getString(R.string.stats_hint_up), "B"));
        }
        list.add(new GamepadHintsView.Hint(rangeWord(model.range()), "LB", "RB"));
        list.add(new GamepadHintsView.Hint(getString(R.string.stats_hint_range), "X", "Y"));
        hints.setHints(list.toArray(new GamepadHintsView.Hint[0]));
    }

    private String rangeWord(String range) {
        switch (range) {
            case AppStats.RANGE_YEAR:
                return getString(R.string.stats_range_year);
            case AppStats.RANGE_MONTH:
                return getString(R.string.stats_range_month);
            default:
                return getString(R.string.stats_range_week);
        }
    }

    // --- Loading ---

    // Only the latest request's answer is shown; see StatsViewModel.
    private void load() {
        if (!isAdded() || getView() == null) {
            return;
        }
        final HostSession s = session;
        if (s == null) {
            // No way to the host (none selected, or no address yet): the retry row it is.
            if (model.shown() == null) {
                showStatus(false);
            }
            return;
        }
        int mine = model.nextRequest();
        if (model.shown() == null) {
            showStatus(true);
        }
        // A drill on its way is superseded: its spinner goes.
        updateTooltip();
        request(s, mine, model.range(), model.offset());
    }

    private void request(HostSession s, int mine, String range, int offset) {
        try {
            model.loader().execute(() -> {
                AppStats result = null;
                try {
                    result = s.http().getAppStats(range, offset);
                } catch (Exception e) {
                    LimeLog.warning("Stats: " + e.getMessage());
                }
                final AppStats loaded = result;
                main.post(() -> onLoaded(mine, loaded));
            });
        } catch (RejectedExecutionException e) {
            // The model was cleared with the fragment; nothing is left to show it.
        }
    }

    private void onLoaded(int request, @Nullable AppStats loaded) {
        if (!isAdded() || getView() == null || !model.isLatest(request)) {
            return;
        }
        StatsViewModel.PendingDrill drill = model.takeDrill(request, loaded != null);
        if (loaded == null) {
            if (drill != null) {
                // The year stays; only its spinner goes.
                updateTooltip();
                updateHints();
                if (isOnScreen()) {
                    Toast.makeText(requireContext(), R.string.stats_failed_title, Toast.LENGTH_SHORT).show();
                }
            } else if (model.shown() == null) {
                showStatus(false);
            } else if (isOnScreen()) {
                Toast.makeText(requireContext(), R.string.stats_failed_title, Toast.LENGTH_SHORT).show();
            }
            return;
        }
        boolean first = model.shown() == null;
        model.setShown(loaded);
        if (drill != null) {
            // The month is the period now: the remote shows it, and B climbs back.
            targetChanged();
        } else {
            refreshRemote();
        }
        bind(loaded, first, drill != null);
    }

    // --- Drawing ---

    private void showStatus(boolean loading) {
        boolean hadFocus = bodyView.hasFocus();
        drawn = null;
        statusView.setVisibility(View.VISIBLE);
        bodyView.setVisibility(View.GONE);
        statusProgress.setVisibility(loading ? View.VISIBLE : View.GONE);
        statusTitle.setVisibility(loading ? View.GONE : View.VISIBLE);
        statusHint.setVisibility(loading ? View.GONE : View.VISIBLE);
        if (hadFocus || (isOnScreen() && requireActivity().getCurrentFocus() == null)) {
            statusView.requestFocus();
        }
    }

    /**
     * Draws a page.
     *
     * @param first      whether it is the host's first page on screen, which hands focus to the
     *                   chart
     * @param focusChart whether focus goes to the chart (a month just opened from its year)
     */
    private void bind(AppStats s, boolean first, boolean focusChart) {
        View before = requireActivity().getCurrentFocus();
        boolean focusOurs = before == null || isInside(before);
        FocusMemo memo = FocusMemo.capture(before, stripList, achievementsList, resumeList);
        drawn = s;

        statusView.setVisibility(View.GONE);
        bodyView.setVisibility(View.VISIBLE);

        bindPeriod(s);

        // Without GameActivity, or before its first session, there is nothing to show by day;
        // say so instead of zeros, and let the totals and the lists come first.
        boolean tracked = s.isTracked();
        untrackedView.setVisibility(tracked ? View.GONE : View.VISIBLE);
        trackedView.setVisibility(tracked ? View.VISIBLE : View.GONE);
        if (!tracked) {
            untrackedView.setText(text.untracked(s));
        } else {
            bindSummary(s);
            bindChart(s);
            bindStrip(s);
        }
        bindLower(s, tracked);
        updateTooltip();
        updateHints();

        // A page that lands while the d-pad is on the navigation leaves it there; and a hidden
        // section takes no focus at all.
        if (!focusOurs || !isOnScreen()) {
            return;
        }
        View stop = firstStop();
        if (first || focusChart) {
            stop.requestFocus();
        } else {
            memo.restore(stop);
        }
        // The chart goes away for a period GameActivity has nothing of; don't leave the pad nowhere.
        View focused = requireActivity().getCurrentFocus();
        if (focused == null || !focused.isShown()) {
            stop.requestFocus();
        }
    }

    // The period's title in the remote, and the crumb: "Year 2026 › pick a month", "‹ 2026 / October".
    private void bindPeriod(AppStats s) {
        periodTitle.setText(text.periodShortTitle(s));

        SpannableStringBuilder line = new SpannableStringBuilder();
        boolean year = AppStats.RANGE_YEAR.equals(s.getRange());
        crumbTag.setVisibility(year ? View.VISIBLE : View.GONE);
        if (year) {
            appendStrong(line, text.periodTitle(s));
            line.append("  ›  ").append(getString(R.string.stats_crumb_pick));
        } else if (AppStats.RANGE_MONTH.equals(s.getRange())) {
            int calendarYear = StatsDrill.calendarYear(
                    StatsDrill.yearOf(s.getOffset(), s.getToday())[0], s.getToday());
            if (calendarYear >= 0) {
                line.append("‹ ").append(String.valueOf(calendarYear)).append(" / ");
            }
            appendStrong(line, text.periodShortTitle(s));
        } else {
            appendStrong(line, text.periodTitle(s));
        }
        crumbText.setText(line);
    }

    private void appendStrong(SpannableStringBuilder line, String part) {
        int start = line.length();
        line.append(part);
        line.setSpan(new ForegroundColorSpan(ContextCompat.getColor(requireContext(),
                R.color.content_primary)), start, line.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        line.setSpan(new StyleSpan(Typeface.BOLD), start, line.length(),
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
    }

    // "Played in 2026 · 431h 50m", the delta against the period before, and the counts.
    private void bindSummary(AppStats s) {
        String phrase = text.periodPhrase(s);
        playedLabel.setText(getString(R.string.stats_played_period, phrase));
        totalView.setText(text.duration(s.getTotalSeconds()));

        String delta = text.delta(s);
        deltaView.setVisibility(delta == null ? View.GONE : View.VISIBLE);
        if (delta != null) {
            SpannableString span = new SpannableString(delta);
            String amount = text.deltaAmount(s);
            int at = amount != null ? delta.indexOf(amount) : -1;
            if (at >= 0) {
                int end = at + amount.length();
                span.setSpan(new TypefaceSpan("monospace"), at, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                // Only more play is good news; less is said in the line's own muted ink.
                if (s.getTotalSeconds() > s.getPreviousTotalSeconds()) {
                    span.setSpan(new ForegroundColorSpan(ContextCompat.getColor(requireContext(),
                            R.color.status_online)), at, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                }
            }
            deltaView.setText(span);
        }

        List<String> parts = new ArrayList<>();
        parts.add(getResources().getQuantityString(R.plurals.stats_sessions, s.getSessions(),
                s.getSessions()));
        if (s.getSessions() > 0) {
            parts.add(getString(R.string.stats_average,
                    text.duration(s.getTotalSeconds() / s.getSessions())));
        }
        // Only when SuccessStory is there: no data is not the same as nothing unlocked.
        if (s.getAchievements() != null) {
            parts.add(getString(R.string.stats_unlocked_count,
                    text.number(s.getAchievements().getUnlocked())));
        }
        summaryLine.setText(TextUtils.join(getString(R.string.stats_separator), parts));
    }

    private void bindChart(AppStats s) {
        List<AppStats.Bucket> buckets = s.getBuckets();
        int n = buckets.size();
        long[] values = new long[n];
        String[] labels = new String[n];
        boolean month = AppStats.RANGE_MONTH.equals(s.getRange());
        String[] valueLabels = month ? null : new String[n];
        // A narrow window labels a month's days a week apart: every fifth would crowd.
        boolean weekly = month && isCompact();
        for (int i = 0; i < n; i++) {
            AppStats.Bucket b = buckets.get(i);
            values[i] = b.getSeconds();
            labels[i] = text.barLabel(s.getRange(), b.getDate(), i, weekly);
            if (valueLabels != null) {
                valueLabels[i] = text.barValue(s.getRange(), b.getSeconds());
            }
        }
        int futureFrom = StatsDrill.firstFutureBar(s);
        int picked = model.selectedBar();
        if (picked < 0 || picked >= futureFrom) {
            picked = StatsDrill.defaultBar(s);
        }
        // setData reports the picked bar back (onBarPicked), which keeps the model's in step.
        chart.setData(values, labels, valueLabels, picked);
        chart.setFutureFrom(futureFrom);
    }

    /**
     * The page's own most played, at most five: a row of cards with their share of the period
     * in a wide window, a grid of covers in a compact one. A tracked period without play says
     * so in its place.
     */
    private void bindStrip(AppStats s) {
        stripList.removeAllViews();
        List<AppStats.TopGame> top = s.hasActivity() ? s.getTop()
                : Collections.<AppStats.TopGame>emptyList();
        String phrase = text.periodPhrase(s);
        if (top.isEmpty()) {
            stripSection.setVisibility(View.GONE);
            nothingPlayed.setVisibility(View.VISIBLE);
            nothingPlayed.setText(getString(R.string.stats_nothing_played, phrase));
            return;
        }
        stripSection.setVisibility(View.VISIBLE);
        nothingPlayed.setVisibility(View.GONE);
        int count = Math.min(STRIP_MAX, top.size());
        stripTitle.setText(getString(R.string.stats_most_played, phrase));
        stripSubtitle.setText(text.stripSubtitle(s, count));

        boolean compact = isCompact();
        LayoutInflater inflater = LayoutInflater.from(requireContext());
        int gap = dp(compact ? 6 : 8);
        for (int i = 0; i < count; i++) {
            AppStats.TopGame g = top.get(i);
            View card = inflater.inflate(compact ? R.layout.item_stats_strip_cover
                    : R.layout.item_stats_strip_card, stripList, false);
            String hours = text.duration(g.getSeconds());
            ((TextView) card.findViewById(R.id.statsStripHours)).setText(hours);
            TextView share = card.findViewById(R.id.statsStripShare);
            if (share != null) {
                share.setText(getString(R.string.stats_share_percent,
                        StatsFormat.sharePercent(g.getSeconds(), s.getTotalSeconds())));
            }
            card.setContentDescription(getString(R.string.stats_bar_caption, g.getName(), hours));
            loadCover(card.findViewById(R.id.statsStripCover), g.getName(), g.getUuid());
            card.setOnClickListener(v -> openGame(g.getUuid(), g.getName()));
            LinearLayout.LayoutParams params = (LinearLayout.LayoutParams) card.getLayoutParams();
            params.setMarginStart(i == 0 ? 0 : gap);
            stripList.addView(card, params);
        }
        if (compact) {
            stripList.post(this::fitStripCovers);
        }
    }

    // A compact strip's covers at 3:4 of their column's width, as the mock's grid draws them.
    private void fitStripCovers() {
        if (getView() == null || !isCompact()) {
            return;
        }
        for (int i = 0; i < stripList.getChildCount(); i++) {
            View cover = stripList.getChildAt(i).findViewById(R.id.statsStripCover);
            int width = cover != null ? cover.getWidth() : 0;
            if (width > 0 && cover.getLayoutParams().height != width * 4 / 3) {
                cover.getLayoutParams().height = width * 4 / 3;
                cover.requestLayout();
            }
        }
    }

    /**
     * The lower half: the latest unlocks, the games to pick up again, and the library's totals,
     * in that order. Untracked, the totals come first and, in a wide window, the two lists sit
     * side by side, as the mock's untracked frame has them.
     */
    private void bindLower(AppStats s, boolean tracked) {
        bindAchievements(s);
        bindResume(s);
        bindLibrary(s);

        lowerView.removeView(totalsView);
        lowerView.addView(totalsView, tracked ? lowerView.getChildCount() : 0);
        LinearLayout.LayoutParams totals = (LinearLayout.LayoutParams) totalsView.getLayoutParams();
        totals.topMargin = tracked ? dp(16) : dp(12);
        totalsView.setLayoutParams(totals);

        boolean sideBySide = !tracked && !isCompact();
        listsView.setOrientation(sideBySide ? LinearLayout.HORIZONTAL : LinearLayout.VERTICAL);
        for (View section : new View[]{achievementsSection, resumeSection}) {
            LinearLayout.LayoutParams params = (LinearLayout.LayoutParams) section.getLayoutParams();
            params.width = sideBySide ? 0 : ViewGroup.LayoutParams.MATCH_PARENT;
            params.weight = sideBySide ? 1 : 0;
            params.setMarginEnd(sideBySide && section == achievementsSection ? dp(16) : 0);
            section.setLayoutParams(params);
        }
    }

    private void bindAchievements(AppStats s) {
        AppStats.Achievements a = s.getAchievements();
        achievementsList.removeAllViews();
        // No SuccessStory, or nothing unlocked in the period: no list to show.
        if (a == null || a.getRecent().isEmpty()) {
            achievementsSection.setVisibility(View.GONE);
            return;
        }
        achievementsSection.setVisibility(View.VISIBLE);
        achievementsCount.setText(getString(R.string.stats_unlocks_in,
                text.number(a.getUnlocked()), text.periodPhrase(s)));
        LayoutInflater inflater = LayoutInflater.from(requireContext());
        for (Achievement item : a.getRecent()) {
            View row = inflater.inflate(R.layout.stats_achievement_row, achievementsList, false);
            ((TextView) row.findViewById(R.id.statsAchievementName)).setText(item.getName());
            ((TextView) row.findViewById(R.id.statsAchievementDetail)).setText(getString(
                    R.string.stats_achievement_detail, item.getGame(), text.unlockedLabel(item.getUnlockedAt(), false)));
            ImageView icon = row.findViewById(R.id.statsAchievementIcon);
            icon.setClipToOutline(true);
            if (session != null) {
                AchievementIcons.load(icon, item, session);
            }
            row.setOnClickListener(v -> openGame(item.getAppUuid(), item.getGame()));
            achievementsList.addView(row);
        }
    }

    private void bindLibrary(AppStats s) {
        AppStats.Library lib = s.getLibrary();
        libraryHours.setText(text.hoursTotal(lib.getPlaytimeSeconds()));
        libraryGames.setText(text.number(lib.getGames()));
        libraryGamesLabel.setText(getString(R.string.stats_games, text.number(lib.getInstalled())));
        libraryNeverPlayed.setText(text.number(lib.getInstalledNeverPlayed()));
        libraryThisYear.setText(text.number(lib.getPlayedThisYear()));
    }

    private void bindResume(AppStats s) {
        resumeList.removeAllViews();
        List<AppStats.ResumeGame> resume = s.getResume();
        resumeSection.setVisibility(resume.isEmpty() ? View.GONE : View.VISIBLE);
        LayoutInflater inflater = LayoutInflater.from(requireContext());
        for (AppStats.ResumeGame g : resume) {
            View item = inflater.inflate(R.layout.stats_resume_item, resumeList, false);
            ((TextView) item.findViewById(R.id.statsResumeName)).setText(g.getName());
            ((TextView) item.findViewById(R.id.statsResumeDetail)).setText(getString(R.string.stats_resume_detail,
                    text.hoursTotal(g.getPlaytimeSeconds()), text.monthYear(g.getLastActivity())));
            loadCover(item.findViewById(R.id.statsResumeCover), g.getName(), g.getUuid());
            item.setOnClickListener(v -> openGame(g.getUuid(), g.getName()));
            resumeList.addView(item);
        }
    }

    private void loadCover(ImageView view, String name, String uuid) {
        if (covers != null) {
            covers.load(view, name, uuid);
        }
    }

    /**
     * Opens the game's page over this one, on the section's stack, so back returns to the row.
     * The game's app id comes from the library when it lists the game (0 otherwise: the page
     * then has no buttons, but its figures and achievements still load by UUID).
     */
    private void openGame(String uuid, String name) {
        if (uuid == null || uuid.isEmpty() || !isAdded()) {
            return;
        }
        HostLibrary.Snapshot snapshot = host.hostRepository().getLibrary().getValue();
        if (snapshot == null) {
            return;
        }
        int appId = 0;
        for (LibraryApp app : snapshot.apps) {
            if (uuid.equalsIgnoreCase(app.app.getAppUUID())) {
                appId = app.appId();
                break;
            }
        }
        AppStats shown = model.shown();
        String origin = shown != null
                ? getString(R.string.game_page_origin_stats, text.periodTitle(shown))
                : getString(R.string.nav_stats);
        host.openGamePage(new GamePageArgs(snapshot.hostUuid, uuid, appId, name, origin));
    }

    // --- Focus ---

    @Nullable
    @Override
    public View restoreFocusTarget() {
        if (!isAdded() || getView() == null) {
            return null;
        }
        if (lastFocused != null && lastFocused.isShown() && isInside(lastFocused)
                && lastFocused.isFocusable()) {
            return lastFocused;
        }
        return firstStop();
    }

    @Override
    public void requestInitialFocus() {
        if (!isAdded() || getView() == null) {
            return;
        }
        firstStop().requestFocus();
    }

    /**
     * The chart, on its picked bar, when there is a page with one ("the chart is the page and the
     * way around it"); the chosen range when the period is untracked; the status row (loading,
     * or the retry) when there is no page.
     */
    @NonNull
    private View firstStop() {
        if (bodyView.getVisibility() != View.VISIBLE) {
            return statusView;
        }
        if (trackedView.getVisibility() == View.VISIBLE) {
            return chart;
        }
        View segment = ranges.getSegmentView(Math.max(0, ranges.getSelectedIndex()));
        return segment != null ? segment : ranges;
    }

    private boolean isInside(View view) {
        View self = getView();
        for (Object p = view; p instanceof View; p = ((View) p).getParent()) {
            if (p == self) {
                return true;
            }
        }
        return false;
    }

    // Shown to the user: added, with a view, and the section not hidden. A hidden section's
    // fragment is still resumed, so the lifecycle cannot tell.
    private boolean isOnScreen() {
        View view = getView();
        return isAdded() && view != null && view.isShown();
    }

    /**
     * Where the focus was among rows that a new period rebuilds, so it can land on the same
     * place in the new rows rather than fall back to wherever the framework puts it.
     */
    private static final class FocusMemo {
        private final ViewGroup container;
        private final int index;

        private FocusMemo(ViewGroup container, int index) {
            this.container = container;
            this.index = index;
        }

        static FocusMemo capture(View focused, ViewGroup... containers) {
            if (focused != null) {
                for (ViewGroup container : containers) {
                    int index = container.indexOfChild(focused);
                    if (index >= 0) {
                        return new FocusMemo(container, index);
                    }
                }
            }
            return new FocusMemo(null, -1);
        }

        void restore(View fallback) {
            if (container == null) {
                return;
            }
            int count = container.getChildCount();
            if (count > 0 && container.isShown()) {
                container.getChildAt(Math.min(index, count - 1)).requestFocus();
            } else {
                fallback.requestFocus();
            }
        }
    }
}
