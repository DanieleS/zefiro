package com.limelight.main;

import android.app.ActivityManager;
import android.content.Context;
import android.content.Intent;
import android.content.res.Configuration;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.SystemClock;
import android.util.SparseLongArray;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;

import androidx.activity.OnBackPressedCallback;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.core.view.WindowInsetsControllerCompat;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentManager;
import androidx.lifecycle.Lifecycle;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.ViewModelProvider;
import androidx.preference.PreferenceManager;

import com.google.android.material.bottomnavigation.BottomNavigationView;
import com.google.android.material.navigationrail.NavigationRailView;
import com.limelight.LimeLog;
import com.limelight.R;
import com.limelight.computers.HostEntry;
import com.limelight.computers.HostLibrary;
import com.limelight.computers.HostRepository;
import com.limelight.computers.PairingRequest;
import com.limelight.library.LaunchController;
import com.limelight.library.LibraryFragment;
import com.limelight.library.LibraryIntents;
import com.limelight.library.SearchFragment;
import com.limelight.nvstream.http.ComputerDetails;
import com.limelight.nvstream.http.NvHTTP;
import com.limelight.pcs.HostActions;
import com.limelight.pcs.PcsFragment;
import com.limelight.preferences.StreamSettings;
import com.limelight.stats.AchievementToast;
import com.limelight.stats.AchievementWatcher;
import com.limelight.stats.HostLink;
import com.limelight.stats.HostSession;
import com.limelight.stats.StatsFragment;
import com.limelight.utils.Dialog;
import com.limelight.utils.UiHelper;

/**
 * The main screen: Library, PCs, Stats and Search as sections of one activity, with a navigation
 * rail in wide windows and a bottom bar in compact ones.
 *
 * <p>It replaced a pair of activities, a PC list with the library on top of it, each binding the
 * computer manager on its own. Here one {@link MainViewModel} owns the host state for every
 * section, and switching sections is a fragment show/hide, not an activity start.
 *
 * <h3>Entry</h3>
 *
 * The launcher reaches it through the {@code .PcView} alias in the manifest, the name the old PC
 * list had, so home icons pinned before the switch still resolve. Where it opens:
 *
 * <pre>
 *   restored after a process death   the section it was on
 *   an art:// pairing link           PCs, where the pairing runs (see below)
 *   a host shortcut, TV channel      that host's Library ({@link LibraryIntents#EXTRA_UUID}),
 *                                    sent by ShortcutTrampoline
 *   the launcher, with a last host   that host's Library
 *   the launcher, never a host       PCs: an empty Library has nothing to offer but the way there
 * </pre>
 *
 * <h3>Window</h3>
 *
 * The activity handles rotation and resizing itself (see the manifest), so nothing is recreated
 * and resource qualifiers are never re-applied. {@link WindowClass} is computed again on every
 * configuration change, and when it changes the shell swaps rail for bar and changes how it
 * treats the system bars:
 *
 * <pre>
 *   WIDE     rail column shown, bottom bar gone, system bars hidden (immersive, as the old
 *            library was):
 *            only a display cutout pads the content
 *   COMPACT  bottom bar shown, rail column gone, system bars kept: the status bar inset pads
 *            the sections, the navigation bar inset pads the bottom bar
 * </pre>
 *
 * Insets pass through {@code android.R.id.content} untouched and are applied, then consumed, by
 * the root, which knows which of its parts sits against which edge.
 *
 * <h3>Stats</h3>
 *
 * Stats is offered only while the selected host is known to serve statistics (its library's
 * {@code statsAvailable}), in the rail and the bar alike. A host switch, or a host that stops
 * answering for them, can take the item away while it is the one selected; the screen then falls
 * back to the Library rather than leave a section on screen that the navigation no longer names.
 *
 * <h3>Back</h3>
 *
 * Back has work to do only away from the Library's home: from another section it returns to the
 * Library, and inside the Library it pops the Library's own stack (See all). At the Library's
 * home the callback is disabled and the system's default applies, which leaves the app; with one
 * activity there is nothing underneath to return to.
 *
 * <h3>Keys</h3>
 *
 * Key-downs go to the visible section first ({@link SectionKeys}), then to the focused view.
 * Directional focus between navigation and section is decided by {@link MainRootLayout}.
 *
 * <h3>Controllers</h3>
 *
 * What starts activities, shows dialogs or holds activity-result launchers lives here, not in a
 * section: {@link HostActions} (pairing, wake, delete, export), {@link LaunchController}
 * (starting, resuming and quitting games) and {@link ArtExport} (a game's launcher file) all
 * outlive any section's views. Sections reach them, and the host state, through
 * {@link MainHost}.
 *
 * <h3>The companion panel</h3>
 *
 * On a device with a second screen (the Thor's bottom one), {@link CompanionFeed} keeps it on
 * what the d-pad is on up here, and runs its Play and host taps. That panel never takes key
 * focus, but a touch on its display can still move the system's focused display there, leaving
 * this window without keys until it is touched. So after every tap on the panel the d-pad is
 * handed back: focus is asked again for the view that last held it, and only if the window has
 * actually lost focus is the task moved to the front, which costs a window transition.
 *
 * <h3>Back from a stream</h3>
 *
 * A stream's achievements may only be written by the host once the game closes, after the
 * stream's last look. So on resume, a few seconds later, the screen asks the host that was
 * streamed (not necessarily the selected one) once more, and toasts what is new, as the old
 * library did.
 *
 * <h3>Pairing from a link</h3>
 *
 * An {@code art://host:port?pin=&passphrase=} link reaches this screen through
 * {@code AddComputerManually}, with the host's address and the PIN as extras. The PIN cannot be
 * used until the new host has been polled, so the address is parked in the repository; when a
 * poll finds the host there, the repository offers a {@link PairingRequest}, and this screen
 * shows PCs and runs the pairing.
 */
public class MainActivity extends AppCompatActivity
        implements NavChrome.Callbacks, MainRootLayout.Gate, MainHost, HostActions.Shell {

    private static final String STATE_SECTION = "section";

    // SuccessStory refreshes a game's achievements when it closes; give it a moment.
    private static final long AFTER_STREAM_CHECK_DELAY_MS = 4000;

    // How long after a tap on the companion panel a loss of window focus is taken to be that
    // tap's doing, and undone. The focused display moves a frame or two after the touch, so the
    // check right after the tap can come too early to see it.
    private static final long COMPANION_RECLAIM_WINDOW_MS = 600;

    private MainViewModel viewModel;
    private MainRootLayout root;
    private View railColumn;
    private View sectionContainer;
    private BottomNavigationView navBottom;
    private NavChrome navChrome;
    private SectionNavigator navigator;
    private RendererProbe rendererProbe;
    private HostActions hostActions;
    private ArtExport artExport;
    private LaunchController launchController;
    private CompanionFeed companionFeed;
    private int railPaddingTop;
    // Until when a lost window focus is put down to a tap on the companion panel; see above.
    private long companionReclaimUntil;
    @Nullable
    private Runnable achievementCheck;

    // Key code to the down time of the press a section handled, so that press's key-up is
    // swallowed too. Keyed by the down time, not the code alone: when the handled press opens
    // another window (a launch, an actions sheet) its key-up goes there, and an entry by code
    // would linger and eat the key-up of the next, unrelated press of that key, losing a click.
    // A key-up carries its press's down time, so a stale entry can never match a later press.
    private final SparseLongArray sectionHeldKeys = new SparseLongArray();

    private final OnBackPressedCallback backCallback = new OnBackPressedCallback(false) {
        @Override
        public void handleOnBackPressed() {
            if (navigator.current() != Section.LIBRARY) {
                goTo(Section.LIBRARY);
                return;
            }
            Fragment library = navigator.fragment(Section.LIBRARY);
            if (library != null && library.isAdded()
                    && library.getChildFragmentManager().getBackStackEntryCount() > 0) {
                library.getChildFragmentManager().popBackStack();
            }
        }
    };

    // The Library's child stack (See all) decides whether back has work at the Library. Its
    // fragment can be created by a restore before this activity's onCreate gets past super, so
    // the watch is set up on the fragment's creation, not on our first show of it.
    private final FragmentManager.FragmentLifecycleCallbacks libraryStackWatcher =
            new FragmentManager.FragmentLifecycleCallbacks() {
                @Override
                public void onFragmentCreated(@NonNull FragmentManager fm, @NonNull Fragment f,
                                              @Nullable Bundle savedInstanceState) {
                    if (Section.LIBRARY.tag().equals(f.getTag())) {
                        f.getChildFragmentManager().addOnBackStackChangedListener(
                                MainActivity.this::updateBackEnabled);
                    }
                }
            };

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        getSupportFragmentManager().registerFragmentLifecycleCallbacks(libraryStackWatcher, false);
        super.onCreate(savedInstanceState);

        // Defaults first: the repository the view model makes reads the preferences at once.
        PreferenceManager.setDefaultValues(this, R.xml.preferences, false);
        viewModel = new ViewModelProvider(this).get(MainViewModel.class);

        // Registered before the activity starts, as the result API requires; their results go
        // to the actions, which are made right below and only launch them later.
        ActivityResultLauncher<Uri> pickRomsFolder = registerForActivityResult(
                new ActivityResultContracts.OpenDocumentTree(),
                uri -> hostActions.onRomsFolderPicked(uri));
        ActivityResultLauncher<Uri> pickEsdeFolder = registerForActivityResult(
                new ActivityResultContracts.OpenDocumentTree(),
                uri -> hostActions.onEsdeFolderPicked(uri));
        hostActions = new HostActions(this, viewModel.repository(), this,
                pickRomsFolder, pickEsdeFolder);
        artExport = new ArtExport(this);

        setContentView(R.layout.activity_main);
        UiHelper.notifyNewRootView(this);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            // Let an expanded picture-in-picture stream float over the browser.
            setShouldDockBigOverlays(false);
        }

        root = findViewById(R.id.mainRoot);
        railColumn = findViewById(R.id.navRailColumn);
        sectionContainer = findViewById(R.id.sectionContainer);
        navBottom = findViewById(R.id.navBottom);
        NavigationRailView navRail = findViewById(R.id.navRail);
        railPaddingTop = railColumn.getPaddingTop();

        navChrome = new NavChrome(railColumn, navRail, findViewById(R.id.navRailSettings),
                navBottom, this);
        navChrome.apply(WindowClass.from(getResources().getConfiguration().screenWidthDp));
        installInsets();
        applySystemBars();

        Section initial = viewModel.section();
        if (savedInstanceState != null) {
            initial = Section.fromName(savedInstanceState.getString(STATE_SECTION), initial);
        } else if (parkPairing(getIntent())) {
            // Sent here to pair a PC just added: the pairing happens in PCs, so start there.
            initial = Section.PCS;
        } else if (selectHostFrom(getIntent())) {
            initial = Section.LIBRARY;
        } else if (viewModel.repository().getSelectedHostUuid().getValue() == null) {
            // Never a host opened (a first install): the Library would only point at PCs.
            initial = Section.PCS;
        }
        navigator = new SectionNavigator(getSupportFragmentManager(), R.id.sectionContainer,
                sectionContainer, MainActivity::createSection, initial);
        root.setGate(this);
        getOnBackPressedDispatcher().addCallback(this, backCallback);

        rendererProbe = new RendererProbe(this, findViewById(android.R.id.content));
        rendererProbe.start();
        launchController = new LaunchController(this, viewModel.repository(), rendererProbe, artExport);
        companionFeed = new CompanionFeed(this, viewModel.repository(), launchController,
                this::reclaimFocusFromCompanion);

        goTo(initial);

        // Delivered only while started, so the PIN dialog never comes up behind another screen.
        viewModel.repository().getPairingRequests().observe(this, this::onPairingRequest);
        viewModel.repository().getLibrary().observe(this, this::onLibrary);
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        if (parkPairing(intent)) {
            goTo(Section.PCS);
        } else if (selectHostFrom(intent)) {
            goTo(Section.LIBRARY);
        }
    }

    @Override
    protected void onStart() {
        super.onStart();
        viewModel.onStart();
        companionFeed.start();
        // A switch asked for while the state was saved is applied now, with its initial focus.
        navigator.resume();
        updateBackEnabled();
    }

    @Override
    protected void onResume() {
        super.onResume();
        checkAchievementsAfterStream();
    }

    @Override
    protected void onStop() {
        companionFeed.stop();
        companionReclaimUntil = 0;
        viewModel.onStop();
        // As the old PC list did: an informational dialog (a PIN, a host's details) is not left
        // up behind a stream or another app. A pairing in progress carries on without its PIN
        // dialog.
        Dialog.closeDialogs();
        super.onStop();
    }

    @Override
    protected void onSaveInstanceState(@NonNull Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putString(STATE_SECTION, navigator.current().name());
    }

    @Override
    protected void onDestroy() {
        if (achievementCheck != null) {
            root.removeCallbacks(achievementCheck);
            achievementCheck = null;
        }
        rendererProbe.release();
        hostActions.release();
        launchController.release();
        getSupportFragmentManager().unregisterFragmentLifecycleCallbacks(libraryStackWatcher);
        super.onDestroy();
    }

    @Override
    public void onConfigurationChanged(@NonNull Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        WindowClass next = WindowClass.from(newConfig.screenWidthDp);
        if (next == navChrome.windowClass()) {
            return;
        }
        View focused = getCurrentFocus();
        boolean focusInChrome = focused != null && !isInSection(focused);
        navChrome.apply(next);
        applySystemBars();
        ViewCompat.requestApplyInsets(root);
        // Focus on the widget that was just hidden would be stranded; the d-pad starts again
        // from where the user was in the section.
        if (focusInChrome && !focused.isShown()) {
            View target = sectionRestoreTarget();
            if (target != null) {
                target.requestFocus();
            }
        }
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) {
            // The system bars come back on a swipe or when returning from another screen; a wide
            // window puts them away again.
            applySystemBars();
        } else if (SystemClock.uptimeMillis() < companionReclaimUntil) {
            // Most likely lost to the tap on the companion panel just now. A dialog the tap
            // raised takes window focus too, but moving a task already in front leaves that
            // dialog on top of it, and a game it launched has paused this activity by now.
            companionReclaimUntil = 0;
            moveToFrontIfStillHere();
        }
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        int code = event.getKeyCode();
        if (event.getAction() == KeyEvent.ACTION_DOWN) {
            Fragment section = navigator.currentFragment();
            if (section instanceof SectionKeys && section.isAdded() && section.getView() != null
                    && ((SectionKeys) section).onSectionKey(event)) {
                sectionHeldKeys.put(code, event.getDownTime());
                return true;
            }
        } else if (event.getAction() == KeyEvent.ACTION_UP) {
            int i = sectionHeldKeys.indexOfKey(code);
            if (i >= 0 && sectionHeldKeys.valueAt(i) == event.getDownTime()) {
                sectionHeldKeys.removeAt(i);
                return true;
            }
        }
        return super.dispatchKeyEvent(event);
    }

    // --- Sections and controllers ---

    private static Fragment createSection(@NonNull Section section) {
        switch (section) {
            case LIBRARY:
                return new LibraryFragment();
            case PCS:
                return new PcsFragment();
            case STATS:
                return new StatsFragment();
            case SEARCH:
            default:
                return new SearchFragment();
        }
    }

    @NonNull
    @Override
    public HostRepository hostRepository() {
        return viewModel.repository();
    }

    @NonNull
    @Override
    public HostActions hostActions() {
        return hostActions;
    }

    @NonNull
    @Override
    public LaunchController launchController() {
        return launchController;
    }

    @NonNull
    @Override
    public GameFocusListener gameFocus() {
        return companionFeed;
    }

    @Override
    public void showSection(@NonNull Section section) {
        goTo(section);
    }

    @NonNull
    @Override
    public LiveData<Boolean> showHiddenGames() {
        return viewModel.showHiddenGames();
    }

    @Override
    public void setShowHiddenGames(boolean show) {
        viewModel.setShowHiddenGames(show);
    }

    @Override
    public void showLibrary(@NonNull String hostUuid, boolean showHidden) {
        viewModel.repository().select(hostUuid);
        // "View all apps" lands on the grid with the hidden games in it; a plain open leaves the
        // user's own choice as it was.
        if (showHidden) {
            viewModel.setShowHiddenGames(true);
        }
        goTo(Section.LIBRARY);
    }

    @Override
    public void whenRendererReady(@NonNull Runnable start) {
        rendererProbe.whenReady(start);
    }

    // --- Stats ---

    /**
     * Offers Stats for a host that serves statistics, and takes it away from one that does not,
     * falling back to the Library when it was the section on screen. Package-private so a test
     * can hand in a snapshot without a host to poll.
     */
    void onLibrary(@Nullable HostLibrary.Snapshot snapshot) {
        boolean available = snapshot != null && snapshot.statsAvailable;
        if (!available && navigator.current() == Section.STATS) {
            // First, so the item is not hidden while it is still the checked one.
            goTo(Section.LIBRARY);
        }
        navChrome.setStatsVisible(available);
    }

    // --- Back from a stream ---

    /**
     * Looks once more for the achievements of the stream that just ended, a few seconds from
     * now, and toasts what the stream did not announce. When the streamed host cannot be reached
     * yet (not polled since the process started), the check stays pending for the next resume.
     */
    private void checkAchievementsAfterStream() {
        String streamed = AchievementWatcher.pendingCheckHost();
        if (streamed == null || achievementCheck != null) {
            return;
        }
        final HostSession session = sessionFor(streamed);
        if (session == null) {
            return;
        }
        final long since = AchievementWatcher.takePendingCheck(streamed);
        if (since < 0) {
            return;
        }
        achievementCheck = () -> {
            achievementCheck = null;
            if (isFinishing() || isDestroyed()) {
                return;
            }
            AchievementWatcher.checkOnce(session, since, fresh -> {
                ViewGroup content = findViewById(android.R.id.content);
                if (!isFinishing() && !isDestroyed() && content != null) {
                    AchievementToast.show(content, fresh, session);
                }
            });
        };
        root.postDelayed(achievementCheck, AFTER_STREAM_CHECK_DELAY_MS);
    }

    /** A connection to a host: the open library's when it is that host, else a new one. */
    @Nullable
    private HostSession sessionFor(@NonNull String hostUuid) {
        HostRepository repository = viewModel.repository();
        HostLibrary library = repository.currentLibrary();
        if (library != null && hostUuid.equalsIgnoreCase(library.getHostUuid())) {
            return library.session();
        }
        HostEntry entry = repository.getHost(hostUuid);
        HostLink link = entry != null ? HostLink.of(entry.details, repository.uniqueId()) : null;
        return link != null ? new HostSession(this, link) : null;
    }

    // --- Opening a host from outside ---

    /**
     * Selects the host an intent names by {@link LibraryIntents#EXTRA_UUID}, as a host shortcut,
     * a TV channel or a game shortcut whose host has a stream running hands it over through
     * ShortcutTrampoline. The trampoline has already found the host, so the UUID is trusted.
     *
     * @return whether the intent named one
     */
    private boolean selectHostFrom(Intent intent) {
        String uuid = intent.getStringExtra(LibraryIntents.EXTRA_UUID);
        if (uuid == null || uuid.isEmpty()) {
            return false;
        }
        viewModel.repository().select(uuid);
        return true;
    }

    // --- Pairing from a link ---

    /**
     * Parks the pairing an intent asks for (see the class comment), if it asks for one.
     *
     * @return whether it did
     */
    private boolean parkPairing(Intent intent) {
        String hostname = intent.getStringExtra(LibraryIntents.EXTRA_PAIR_HOSTNAME);
        String pin = intent.getStringExtra(LibraryIntents.EXTRA_PAIR_PIN);
        String passphrase = intent.getStringExtra(LibraryIntents.EXTRA_PAIR_PASSPHRASE);
        if (hostname == null || pin == null || passphrase == null) {
            return false;
        }
        int port = intent.getIntExtra(LibraryIntents.EXTRA_PAIR_PORT, NvHTTP.DEFAULT_HTTP_PORT);
        viewModel.repository().setPendingPairing(
                new ComputerDetails.AddressTuple(hostname, port), pin, passphrase);
        return true;
    }

    private void onPairingRequest(@Nullable PairingRequest request) {
        if (request == null) {
            return;
        }
        // Taken first, so a re-delivery (a restart of this observer) never pairs twice.
        viewModel.repository().pairingHandled();
        goTo(Section.PCS);
        hostActions.pair(request.host, request.pin, request.passphrase);
    }

    // --- Navigation ---

    /** Switches to a section from code (back, a restore): the widgets are checked here too. */
    private void goTo(Section section) {
        navChrome.setChecked(section);
        onSectionChosen(section);
    }

    @Override
    public void onSectionChosen(@NonNull Section section) {
        viewModel.setSection(section);
        // First, so the section's own focus report, posted by the switch, lands after it.
        companionFeed.onSection(section);
        navigator.show(section);
        updateBackEnabled();
    }

    @Override
    public void onSettingsChosen() {
        startActivity(new Intent(this, StreamSettings.class));
    }

    private void updateBackEnabled() {
        if (navigator == null) {
            return;
        }
        boolean work = navigator.current() != Section.LIBRARY;
        if (!work) {
            Fragment library = navigator.fragment(Section.LIBRARY);
            work = library != null && library.isAdded()
                    && library.getChildFragmentManager().getBackStackEntryCount() > 0;
        }
        backCallback.setEnabled(work);
    }

    // --- The companion panel ---

    /**
     * Hands the d-pad back after a tap on the companion panel (see the class comment). Runs after
     * the tap's action, so a game it launched or a dialog it raised is left alone.
     */
    private void reclaimFocusFromCompanion() {
        companionReclaimUntil = SystemClock.uptimeMillis() + COMPANION_RECLAIM_WINDOW_MS;
        root.post(() -> {
            if (!getLifecycle().getCurrentState().isAtLeast(Lifecycle.State.RESUMED)) {
                // A launch took over; the stream has the keys now.
                return;
            }
            View last = getCurrentFocus();
            if (last == null && !root.isInTouchMode()) {
                last = sectionRestoreTarget();
            }
            if (last != null) {
                last.requestFocus();
            }
            if (!hasWindowFocus()) {
                companionReclaimUntil = 0;
                moveToFrontIfStillHere();
            }
        });
    }

    /**
     * Gives this task the focused display back, as Game does for the panel's menu. Only while
     * this activity is resumed: from a stopped one it would pull the library over the stream.
     */
    private void moveToFrontIfStillHere() {
        if (isFinishing() || !getLifecycle().getCurrentState().isAtLeast(Lifecycle.State.RESUMED)) {
            return;
        }
        try {
            ActivityManager am = (ActivityManager) getSystemService(Context.ACTIVITY_SERVICE);
            if (am != null) {
                am.moveTaskToFront(getTaskId(), 0);
            }
        } catch (Exception e) {
            LimeLog.warning("Unable to reclaim focus from the companion panel: " + e);
        }
    }

    // --- Focus gate ---

    @Nullable
    @Override
    public View sectionRestoreTarget() {
        Fragment section = navigator.currentFragment();
        if (section instanceof SectionFocus && section.isAdded() && section.getView() != null) {
            return ((SectionFocus) section).restoreFocusTarget();
        }
        return null;
    }

    @Nullable
    @Override
    public View checkedNavItem() {
        return navChrome.checkedItemView();
    }

    private boolean isInSection(View view) {
        for (Object p = view.getParent(); p instanceof View; p = ((View) p).getParent()) {
            if (p == sectionContainer) {
                return true;
            }
        }
        return false;
    }

    // --- Window ---

    /** Immersive in a wide window, system bars kept in a compact one. */
    private void applySystemBars() {
        // Edge to edge either way, the bar regions painted transparent, so nothing grey shows
        // where a bar is or used to be.
        WindowCompat.setDecorFitsSystemWindows(getWindow(), false);
        getWindow().setStatusBarColor(Color.TRANSPARENT);
        getWindow().setNavigationBarColor(Color.TRANSPARENT);

        WindowInsetsControllerCompat controller =
                WindowCompat.getInsetsController(getWindow(), getWindow().getDecorView());
        if (navChrome.windowClass() == WindowClass.WIDE) {
            controller.setSystemBarsBehavior(
                    WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
            controller.hide(WindowInsetsCompat.Type.systemBars());
        } else {
            controller.show(WindowInsetsCompat.Type.systemBars());
        }
    }

    private void installInsets() {
        // notifyNewRootView pads the content by the tappable insets; the root does its own
        // padding, part by part, so the content passes the insets through untouched.
        ViewGroup content = findViewById(android.R.id.content);
        ViewCompat.setOnApplyWindowInsetsListener(content, (v, insets) -> insets);
        // On a TV, notifyNewRootView also pads the content directly, for overscan. That margin
        // is kept, but moved onto the root: there it adds to the root's own inset padding
        // instead of stacking under it, and it is painted the root's black rather than the
        // window background. The window is not attached yet, so no inset listener has padded
        // the content: whatever padding it has now is that overscan margin, or nothing.
        final Insets overscan = Insets.of(content.getPaddingLeft(), content.getPaddingTop(),
                content.getPaddingRight(), content.getPaddingBottom());
        content.setPadding(0, 0, 0, 0);
        // The bottom bar pads itself for the navigation bar, hard-wired, with no attribute to turn
        // it off. The root consumes the insets before they reach it today; replacing its listener
        // keeps it from padding a second time should they ever get through.
        ViewCompat.setOnApplyWindowInsetsListener(navBottom, (v, insets) -> insets);

        ViewCompat.setOnApplyWindowInsetsListener(root, (v, insets) -> {
            boolean compact = navChrome.windowClass() == WindowClass.COMPACT;
            // A wide window hides its bars, and a bar swiped back in is transient and overlays
            // the content, so only the cutout is padded for. The bars are not asked about at
            // all: before Android 11 the window keeps SYSTEM_UI_FLAG_LAYOUT_STABLE (set by
            // setDecorFitsSystemWindows, and by notifyNewRootView on Q), and with it hidden bars
            // still report their full size, which would leave black bands where they were.
            int types = compact
                    ? WindowInsetsCompat.Type.systemBars() | WindowInsetsCompat.Type.displayCutout()
                    : WindowInsetsCompat.Type.displayCutout();
            Insets bars = insets.getInsets(types);
            v.setPadding(overscan.left + bars.left, overscan.top,
                    overscan.right + bars.right, overscan.bottom);
            railColumn.setPadding(0, railPaddingTop + bars.top, 0, bars.bottom);
            sectionContainer.setPadding(0, bars.top, 0, compact ? 0 : bars.bottom);
            navBottom.setPadding(0, 0, 0, compact ? bars.bottom : 0);
            // Applied here, part by part; the widgets' own inset handling would pad again.
            return WindowInsetsCompat.CONSUMED;
        });
        ViewCompat.requestApplyInsets(root);
    }
}
