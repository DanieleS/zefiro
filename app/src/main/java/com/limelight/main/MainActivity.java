package com.limelight.main;

import android.content.Intent;
import android.content.res.Configuration;
import android.graphics.Color;
import android.os.Build;
import android.os.Bundle;
import android.util.SparseLongArray;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;

import androidx.activity.OnBackPressedCallback;
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
import androidx.lifecycle.ViewModelProvider;
import androidx.preference.PreferenceManager;

import com.google.android.material.bottomnavigation.BottomNavigationView;
import com.google.android.material.navigationrail.NavigationRailView;
import com.limelight.R;
import com.limelight.preferences.StreamSettings;
import com.limelight.utils.UiHelper;

/**
 * The main screen: Library, PCs, Stats and Search as sections of one activity, with a navigation
 * rail in wide windows and a bottom bar in compact ones.
 *
 * <p>It replaces the PcView/AppView pair, where the PC list sat under the library and each bound
 * the computer manager on its own. Here one {@link MainViewModel} owns the host state for every
 * section, and switching sections is a fragment show/hide, not an activity start. Until the
 * switch-over it is reached only from the debug "Zefiro (new UI)" icon, and the sections are
 * placeholders.
 *
 * <h3>Window</h3>
 *
 * The activity handles rotation and resizing itself (see the manifest), so nothing is recreated
 * and resource qualifiers are never re-applied. {@link WindowClass} is computed again on every
 * configuration change, and when it changes the shell swaps rail for bar and changes how it
 * treats the system bars:
 *
 * <pre>
 *   WIDE     rail column shown, bottom bar gone, system bars hidden (immersive, as AppView):
 *            only a display cutout pads the content
 *   COMPACT  bottom bar shown, rail column gone, system bars kept: the status bar inset pads
 *            the sections, the navigation bar inset pads the bottom bar
 * </pre>
 *
 * Insets pass through {@code android.R.id.content} untouched and are applied, then consumed, by
 * the root, which knows which of its parts sits against which edge.
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
 */
public class MainActivity extends AppCompatActivity
        implements NavChrome.Callbacks, MainRootLayout.Gate {

    private static final String STATE_SECTION = "section";

    private MainViewModel viewModel;
    private MainRootLayout root;
    private View railColumn;
    private View sectionContainer;
    private BottomNavigationView navBottom;
    private NavChrome navChrome;
    private SectionNavigator navigator;
    private RendererProbe rendererProbe;
    private int railPaddingTop;

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
        }
        navigator = new SectionNavigator(getSupportFragmentManager(), R.id.sectionContainer,
                sectionContainer, PlaceholderSectionFragment::newInstance, initial);
        root.setGate(this);
        getOnBackPressedDispatcher().addCallback(this, backCallback);

        rendererProbe = new RendererProbe(this, findViewById(android.R.id.content));
        rendererProbe.start();

        goTo(initial);
    }

    @Override
    protected void onStart() {
        super.onStart();
        viewModel.onStart();
        // A switch asked for while the state was saved is applied now, with its initial focus.
        navigator.resume();
        updateBackEnabled();
    }

    @Override
    protected void onStop() {
        viewModel.onStop();
        super.onStop();
    }

    @Override
    protected void onSaveInstanceState(@NonNull Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putString(STATE_SECTION, navigator.current().name());
    }

    @Override
    protected void onDestroy() {
        rendererProbe.release();
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

    // --- Navigation ---

    /** Switches to a section from code (back, a restore): the widgets are checked here too. */
    private void goTo(Section section) {
        navChrome.setChecked(section);
        onSectionChosen(section);
    }

    @Override
    public void onSectionChosen(@NonNull Section section) {
        viewModel.setSection(section);
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
