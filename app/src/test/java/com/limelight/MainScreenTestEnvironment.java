package com.limelight;

import android.app.Application;
import android.app.GameManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;

import androidx.lifecycle.LiveData;
import androidx.lifecycle.Observer;
import androidx.lifecycle.ViewModelProvider;
import androidx.preference.PreferenceManager;
import androidx.test.core.app.ApplicationProvider;

import com.limelight.companion.CompanionDisplayManager;
import com.limelight.computers.ComputerManagerService;
import com.limelight.library.LibraryIntents;

import org.robolectric.Shadows;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.mockito.Mockito.mock;

/**
 * What the main screen needs from the device before it can start under Robolectric, shared by
 * the tests that start it (the launcher's entry, now {@code MainActivity} behind the
 * {@code .PcView} alias).
 */
public final class MainScreenTestEnvironment {

    private MainScreenTestEnvironment() {
    }

    /**
     * A saved GL renderer for this build, so the renderer probe has nothing to do; a game
     * service; and a stub computer manager binder for the host repository to bind.
     */
    public static void prepare() {
        forgetCachedViewModelFactory();
        forgetPanelObservers();
        Application app = ApplicationProvider.getApplicationContext();

        SharedPreferences glPrefs = app.getSharedPreferences("GlPreferences", 0);
        glPrefs.edit()
                .putString("Renderer", "TestRenderer")
                .putString("Fingerprint", Build.FINGERPRINT)
                .commit();

        Shadows.shadowOf(app).setSystemService(Context.GAME_SERVICE, mock(GameManager.class));

        ComponentName cms = new ComponentName(app, ComputerManagerService.class);
        Shadows.shadowOf(app).setComponentNameAndServiceForBindService(cms,
                mock(ComputerManagerService.ComputerManagerBinder.class));
    }

    /**
     * The activity's default view model factory is a process-wide singleton that keeps the first
     * Application it was given. Robolectric makes a new Application for every test, so without
     * this the main screen's view model (and its host repository's preferences) would belong to
     * the first test's Application, and a host one test selected would leak into the next.
     */
    private static void forgetCachedViewModelFactory() {
        try {
            Field instance = ViewModelProvider.AndroidViewModelFactory.class
                    .getDeclaredField("_instance");
            instance.setAccessible(true);
            instance.set(null, null);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("lifecycle's factory cache moved; update this reset", e);
        }
    }

    /**
     * The companion panel's availability ({@link CompanionDisplayManager#panelShowing()}) is a
     * process-wide LiveData, observed by every screen with a page for the panel, PCs among them,
     * which every first run opens on. An observer leaves with its view's lifecycle, and
     * Robolectric never destroys a test's activity, so without this every main screen any test
     * started would stay reachable from it, views and all, until the test JVM runs out of memory.
     * Its value is left alone: a test that wants the panel sets it itself (and resets it in
     * {@code @After}).
     */
    @SuppressWarnings("unchecked")
    private static void forgetPanelObservers() {
        LiveData<Boolean> panel = CompanionDisplayManager.panelShowing();
        List<Observer<? super Boolean>> observers = new ArrayList<>();
        try {
            Field field = LiveData.class.getDeclaredField("mObservers");
            field.setAccessible(true);
            for (Object entry : (Iterable<Object>) field.get(panel)) {
                observers.add(((Map.Entry<Observer<? super Boolean>, Object>) entry).getKey());
            }
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("LiveData's observer map moved; update this reset", e);
        }
        for (Observer<? super Boolean> observer : observers) {
            panel.removeObserver(observer);
        }
    }

    /**
     * Remembers a host as the one whose library was opened last, as a device that has used the
     * app before has; without one the main screen opens on PCs.
     */
    public static void rememberLastHost(String uuid) {
        PreferenceManager.getDefaultSharedPreferences(ApplicationProvider.getApplicationContext())
                .edit()
                .putString(LibraryIntents.PREF_LAST_HOST, uuid)
                .commit();
    }
}
