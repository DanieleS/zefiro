package com.limelight.stats;

import android.content.Context;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
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
import androidx.fragment.app.Fragment;
import androidx.lifecycle.ViewModelProvider;

import com.limelight.LimeLog;
import com.limelight.R;
import com.limelight.computers.HostEntry;
import com.limelight.computers.HostLibrary;
import com.limelight.computers.HostRepository;
import com.limelight.main.MainHost;
import com.limelight.main.SectionFocus;
import com.limelight.main.SectionKeys;
import com.limelight.main.WindowClass;
import com.limelight.nvstream.http.Achievement;
import com.limelight.nvstream.http.AppStats;

import java.util.Collections;
import java.util.List;
import java.util.concurrent.RejectedExecutionException;

/**
 * How much is played on a PC, a week, a month or a year at a time, then the library at large:
 * CouchPilot's statistics page, served by Vibepollo's {@code /appstats}. The main screen's Stats
 * section.
 *
 * <p>Built for a gamepad first. The bumpers step the period from anywhere in the section, X and
 * Y cycle week, month and year, and the d-pad walks one column top to bottom: the range buttons,
 * the period (left and right step it there too), the chart (left and right pick a bar), the most
 * played, the latest unlocks and the games worth picking up again. Each game opens its own
 * statistics and achievements sheet.
 *
 * <h3>The host</h3>
 *
 * It was {@code StatsActivity}, a screen of its own handed a {@link HostLink} by the library. Now
 * it follows the main screen's selected host: that host's {@link HostLibrary} session, shared with
 * the Library, and the first page the library's probe already fetched, so the section opens on
 * this week without a spinner. A host switch starts the state over.
 *
 * <h3>State</h3>
 *
 * The range, the period and the page on screen live in {@link StatsViewModel}, with the thread
 * the pages are asked for on, so they outlive the views. Answers come back through a main
 * {@link Handler} and are dropped when the view has gone or a later request was made. Shown
 * again after another section, the page is redrawn at once and asked for again quietly, without
 * the spinner.
 *
 * <h3>Focus</h3>
 *
 * Entered, focus goes to the period, where left and right already do something. A new period
 * rebuilds the rows, and focus lands on the same place in the new ones ({@link FocusMemo}). The
 * focus gate returns to the view that last held focus in the section. Focus is only ever moved
 * while it is in this section or nowhere: an answer landing while the d-pad is on the rail
 * leaves it there.
 *
 * Main thread only; every callback checks that the view still exists.
 */
public class StatsFragment extends Fragment implements SectionKeys, SectionFocus {

    private static final String[] RANGES = {
            AppStats.RANGE_WEEK, AppStats.RANGE_MONTH, AppStats.RANGE_YEAR};

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

    // The view that last held focus in the section, for the focus gate.
    @Nullable
    private View lastFocused;
    @Nullable
    private ViewTreeObserver.OnGlobalFocusChangeListener focusWatch;

    private View root;
    private View header;
    private TextView hostNameView;
    private View contentView;
    private View hintView;
    private TextView[] rangeButtons;
    private View statusView;
    private View statusProgress;
    private View statusTitle;
    private View statusHint;
    private View bodyView;
    private View periodNav;
    private ImageView previousView;
    private ImageView nextView;
    private TextView periodTitle;
    private TextView untrackedView;
    private View trackedView;
    private TextView totalView;
    private TextView deltaView;
    private TextView sessionsView;
    private TextView averageView;
    private TextView captionView;
    private StatsBarsView chart;
    private View topSection;
    private LinearLayout topList;
    private View achievementsSection;
    private TextView achievementsCount;
    private TextView achievementsCountLabel;
    private LinearLayout achievementsList;
    private View achievementsEmpty;
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
        contentView = view.findViewById(R.id.statsContent);
        hintView = view.findViewById(R.id.statsHint);

        rangeButtons = new TextView[]{
                view.findViewById(R.id.statsRangeWeek),
                view.findViewById(R.id.statsRangeMonth),
                view.findViewById(R.id.statsRangeYear)};
        for (int i = 0; i < rangeButtons.length; i++) {
            final String r = RANGES[i];
            rangeButtons[i].setOnClickListener(v -> setRange(r));
        }

        statusView = view.findViewById(R.id.statsStatus);
        statusProgress = view.findViewById(R.id.statsStatusProgress);
        statusTitle = view.findViewById(R.id.statsStatusTitle);
        statusHint = view.findViewById(R.id.statsStatusHint);
        statusView.setOnClickListener(v -> load());
        bodyView = view.findViewById(R.id.statsBody);

        periodNav = view.findViewById(R.id.statsPeriodNav);
        previousView = view.findViewById(R.id.statsPrevious);
        nextView = view.findViewById(R.id.statsNext);
        periodTitle = view.findViewById(R.id.statsPeriodTitle);
        previousView.setOnClickListener(v -> stepPeriod(-1));
        nextView.setOnClickListener(v -> stepPeriod(1));

        untrackedView = view.findViewById(R.id.statsUntracked);
        trackedView = view.findViewById(R.id.statsTracked);
        totalView = view.findViewById(R.id.statsTotal);
        deltaView = view.findViewById(R.id.statsDelta);
        sessionsView = view.findViewById(R.id.statsSessions);
        averageView = view.findViewById(R.id.statsAverage);
        captionView = view.findViewById(R.id.statsBarCaption);
        chart = view.findViewById(R.id.statsChart);
        chart.setOnSelectionChangedListener(this::updateCaption);

        topSection = view.findViewById(R.id.statsTopSection);
        topList = view.findViewById(R.id.statsTopList);
        achievementsSection = view.findViewById(R.id.statsAchievementsSection);
        achievementsCount = view.findViewById(R.id.statsAchievementsCount);
        achievementsCountLabel = view.findViewById(R.id.statsAchievementsCountLabel);
        achievementsList = view.findViewById(R.id.statsAchievementsList);
        achievementsEmpty = view.findViewById(R.id.statsAchievementsEmpty);
        libraryHours = view.findViewById(R.id.statsLibraryHours);
        libraryGames = view.findViewById(R.id.statsLibraryGames);
        libraryGamesLabel = view.findViewById(R.id.statsLibraryGamesLabel);
        libraryNeverPlayed = view.findViewById(R.id.statsLibraryNeverPlayed);
        libraryThisYear = view.findViewById(R.id.statsLibraryThisYear);
        resumeSection = view.findViewById(R.id.statsResumeSection);
        resumeList = view.findViewById(R.id.statsResumeList);

        installLayout(view);
        focusWatch = (oldFocus, newFocus) -> {
            if (newFocus != null && isInside(newFocus)) {
                lastFocused = newFocus;
            }
        };
        view.getViewTreeObserver().addOnGlobalFocusChangeListener(focusWatch);
        refreshRangeButtons();

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
        root = null;
        super.onDestroyView();
    }

    @Override
    public void onHiddenChanged(boolean hidden) {
        super.onHiddenChanged(hidden);
        if (!hidden && isAdded() && getView() != null) {
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
        HostEntry entry = repository.getHost(uuid);
        hostNameView.setText(entry != null ? entry.details.name : null);

        boolean changed = model.forHost(uuid);
        if (changed) {
            // Back to this week: the range buttons say so too.
            refreshRangeButtons();
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
        if (changed && !isHidden()) {
            present();
        }
    }

    /**
     * Shows what there is for the host now, then asks for the current page. With nothing yet, the
     * page the library's probe fetched stands in when it is the one asked for (this week).
     */
    private void present() {
        AppStats shown = model.stats();
        boolean first = false;
        if (shown == null && boundLibrary != null && model.offset() == 0
                && AppStats.RANGE_WEEK.equals(model.range())) {
            shown = boundLibrary.getFirstStatsPage();
            if (shown != null) {
                model.setStats(shown);
                first = true;
            }
        }
        if (shown != null && shown != drawn) {
            bind(shown, first);
        }
        load();
    }

    // --- Layout ---

    /**
     * One centred column, no wider than reads comfortably, measured from this section's width
     * rather than the screen's, since the rail takes its share of a wide window. The header
     * takes the same inset so the title sits over the column. The gamepad hint goes in a compact
     * window, where it would cover the column's foot by the bottom bar.
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
            boolean compact = WindowClass.from(v.getResources().getConfiguration().screenWidthDp)
                    == WindowClass.COMPACT;
            int hint = compact ? View.GONE : View.VISIBLE;
            if (hintView != null && hintView.getVisibility() != hint) {
                hintView.setVisibility(hint);
            }
        });
    }

    private static void applyInset(@Nullable View view, int inset) {
        if (view != null && (view.getPaddingLeft() != inset || view.getPaddingRight() != inset)) {
            view.setPadding(inset, view.getPaddingTop(), inset, view.getPaddingBottom());
        }
    }

    // --- Keys ---

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
        // On the period itself, left and right step it, like a spinner.
        if (periodNav.hasFocus()) {
            if (code == KeyEvent.KEYCODE_DPAD_LEFT) {
                stepPeriod(-1);
                return true;
            }
            if (code == KeyEvent.KEYCODE_DPAD_RIGHT) {
                stepPeriod(1);
                return true;
            }
        }
        return false;
    }

    private void setRange(String newRange) {
        if (newRange.equals(model.range())) {
            return;
        }
        model.setRange(newRange);
        refreshRangeButtons();
        load();
    }

    private void stepPeriod(int step) {
        int next = StatsFormat.stepOffset(model.offset(), step, AppStats.MIN_OFFSET);
        if (next == model.offset()) {
            return;
        }
        model.setOffset(next);
        load();
    }

    private void refreshRangeButtons() {
        for (int i = 0; i < rangeButtons.length; i++) {
            rangeButtons[i].setSelected(RANGES[i].equals(model.range()));
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
            if (model.stats() == null) {
                showStatus(false);
            }
            return;
        }
        final int mine = model.nextRequest();
        final String r = model.range();
        final int o = model.offset();
        if (model.stats() == null) {
            showStatus(true);
        }
        try {
            model.loader().execute(() -> {
                AppStats result = null;
                try {
                    result = s.http().getAppStats(r, o);
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
        if (loaded == null) {
            if (model.stats() == null) {
                showStatus(false);
            } else if (isOnScreen()) {
                Toast.makeText(requireContext(), R.string.stats_failed_title, Toast.LENGTH_SHORT).show();
            }
            return;
        }
        boolean first = model.stats() == null;
        model.setStats(loaded);
        bind(loaded, first);
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
     * @param first whether it is the host's first page on screen, which hands focus to the period
     */
    private void bind(AppStats s, boolean first) {
        View before = requireActivity().getCurrentFocus();
        boolean focusOurs = before == null || isInside(before);
        FocusMemo memo = FocusMemo.capture(before, topList, achievementsList, resumeList);
        drawn = s;

        statusView.setVisibility(View.GONE);
        bodyView.setVisibility(View.VISIBLE);

        periodTitle.setText(text.periodTitle(s));
        previousView.setAlpha(model.offset() <= AppStats.MIN_OFFSET ? 0.25f : 1f);
        nextView.setAlpha(model.offset() >= 0 ? 0.25f : 1f);

        // Without GameActivity, or before its first session, there is nothing to show by day;
        // say so instead of zeros.
        boolean tracked = s.isTracked();
        untrackedView.setVisibility(tracked ? View.GONE : View.VISIBLE);
        trackedView.setVisibility(tracked ? View.VISIBLE : View.GONE);
        if (!tracked) {
            untrackedView.setText(text.untracked(s));
        } else {
            totalView.setText(text.duration(s.getTotalSeconds()));
            String delta = text.delta(s);
            deltaView.setText(delta);
            deltaView.setVisibility(delta == null ? View.GONE : View.VISIBLE);
            sessionsView.setText(getResources().getQuantityString(R.plurals.stats_sessions,
                    s.getSessions(), s.getSessions()));
            if (s.getSessions() > 0) {
                averageView.setVisibility(View.VISIBLE);
                averageView.setText(getString(R.string.stats_average,
                        text.duration(s.getTotalSeconds() / s.getSessions())));
            } else {
                averageView.setVisibility(View.GONE);
            }
            bindChart(s);
        }

        bindTop(s);
        bindAchievements(s);
        bindLibrary(s);
        bindResume(s);

        // A page that lands while the d-pad is on the navigation leaves it there; and a hidden
        // section takes no focus at all.
        if (!focusOurs || !isOnScreen()) {
            return;
        }
        if (first) {
            // Land on the period, where left and right already do something.
            periodNav.requestFocus();
        } else {
            memo.restore(periodNav);
        }
        // The chart goes away for a period GameActivity has nothing of; don't leave the pad nowhere.
        View focused = requireActivity().getCurrentFocus();
        if (focused == null || !focused.isShown()) {
            periodNav.requestFocus();
        }
    }

    private void bindChart(AppStats s) {
        List<AppStats.Bucket> buckets = s.getBuckets();
        int n = buckets.size();
        long[] values = new long[n];
        String[] labels = new String[n];
        String[] valueLabels = AppStats.RANGE_WEEK.equals(s.getRange()) ? new String[n] : null;
        int todayIndex = -1;
        String today = s.getToday();
        for (int i = 0; i < n; i++) {
            AppStats.Bucket b = buckets.get(i);
            values[i] = b.getSeconds();
            labels[i] = text.barLabel(s.getRange(), b.getDate(), i);
            if (valueLabels != null) {
                valueLabels[i] = StatsFormat.shortDuration(b.getSeconds());
            }
            boolean isToday = AppStats.RANGE_YEAR.equals(s.getRange())
                    ? b.getDate().length() >= 7 && today.length() >= 7
                            && b.getDate().substring(0, 7).equals(today.substring(0, 7))
                    : b.getDate().equals(today);
            if (isToday) {
                todayIndex = i;
            }
        }
        chart.setData(values, labels, valueLabels, todayIndex);
    }

    private void updateCaption(int index) {
        AppStats stats = model.stats();
        if (stats == null || index < 0 || index >= stats.getBuckets().size()) {
            captionView.setText(R.string.stats_bars_hint);
            return;
        }
        AppStats.Bucket b = stats.getBuckets().get(index);
        captionView.setText(text.barCaption(stats.getRange(), b.getDate(), b.getSeconds()));
    }

    private void bindTop(AppStats s) {
        topList.removeAllViews();
        // Without GameActivity the host sends none, but a list of zeros would be no better.
        List<AppStats.TopGame> top = s.hasActivity() ? s.getTop() : Collections.<AppStats.TopGame>emptyList();
        topSection.setVisibility(top.isEmpty() ? View.GONE : View.VISIBLE);
        long max = 1;
        for (AppStats.TopGame g : top) {
            max = Math.max(max, g.getSeconds());
        }
        LayoutInflater inflater = LayoutInflater.from(requireContext());
        for (AppStats.TopGame g : top) {
            View row = inflater.inflate(R.layout.stats_top_row, topList, false);
            ((TextView) row.findViewById(R.id.statsRowName)).setText(g.getName());
            ((TextView) row.findViewById(R.id.statsRowValue)).setText(text.duration(g.getSeconds()));
            View meter = row.findViewById(R.id.statsRowMeter);
            meter.setPivotX(0);
            meter.setScaleX(StatsFormat.share(g.getSeconds(), max));
            loadCover(row.findViewById(R.id.statsRowCover), g.getName(), g.getUuid());
            row.setOnClickListener(v -> openGame(g.getUuid(), g.getName()));
            topList.addView(row);
        }
    }

    private void bindAchievements(AppStats s) {
        AppStats.Achievements a = s.getAchievements();
        achievementsList.removeAllViews();
        if (a == null) {
            achievementsSection.setVisibility(View.GONE);
            return;
        }
        achievementsSection.setVisibility(View.VISIBLE);
        achievementsCount.setText(text.number(a.getUnlocked()));
        achievementsCountLabel.setText(getResources().getQuantityString(R.plurals.stats_unlocked, a.getUnlocked()));
        achievementsEmpty.setVisibility(a.getRecent().isEmpty() ? View.VISIBLE : View.GONE);
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

    private void openGame(String uuid, String name) {
        if (uuid == null || uuid.isEmpty() || session == null || !isAdded()) {
            return;
        }
        GameStatsSheet.show(requireActivity(), session, uuid, name);
    }

    // --- Focus ---

    @Nullable
    @Override
    public View restoreFocusTarget() {
        if (!isAdded() || getView() == null) {
            return null;
        }
        if (lastFocused != null && lastFocused.isShown() && isInside(lastFocused)) {
            return lastFocused;
        }
        return firstStop();
    }

    @Override
    public void requestInitialFocus() {
        if (!isAdded() || getView() == null) {
            return;
        }
        View target = firstStop();
        if (target != null) {
            target.requestFocus();
        }
    }

    // The period when there is a page, the status row (loading, or the retry) otherwise.
    @Nullable
    private View firstStop() {
        if (bodyView != null && bodyView.getVisibility() == View.VISIBLE) {
            return periodNav;
        }
        return statusView;
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
