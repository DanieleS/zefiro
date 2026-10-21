package com.limelight.main;

import android.content.Context;
import android.os.Looper;

import androidx.lifecycle.MutableLiveData;
import androidx.test.core.app.ApplicationProvider;

import com.limelight.TestLogSuppressor;
import com.limelight.companion.CompanionState;
import com.limelight.companion.CompanionState.Browsing;
import com.limelight.computers.HostEntry;
import com.limelight.computers.HostLibrary;
import com.limelight.computers.HostRepository;
import com.limelight.computers.SnapshotFixtures;
import com.limelight.library.LaunchController;
import com.limelight.library.LibraryApp;
import com.limelight.nvstream.http.ComputerDetails;
import com.limelight.nvstream.http.NvApp;
import com.limelight.nvstream.http.PairingManager.PairState;

import org.junit.After;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;

import java.time.Duration;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * What the companion panel shows while browsing: the game the d-pad is on, the game an actions
 * sheet is open for while it is open, and a host without the hero's Resume and Quit game.
 */
@Config(sdk = {33})
@RunWith(RobolectricTestRunner.class)
public class CompanionFeedTest {

    private static final String HOST = "host-1";

    private final LibraryApp alan = new LibraryApp(new NvApp("Alan Wake 2", "u1", 1, false), false, false);
    private final LibraryApp hades = new LibraryApp(new NvApp("Hades II", "u2", 2, false), false, false);
    private final LibraryApp stars = new LibraryApp(new NvApp("Sea of Stars", "u3", 3, false), true, false);

    private CompanionFeed feed;

    @BeforeClass
    public static void suppressInvalidIdLogs() {
        TestLogSuppressor.install();
    }

    @Before
    public void setUp() {
        ComputerDetails details = new ComputerDetails();
        details.uuid = HOST;
        details.name = "Taverna";
        details.state = ComputerDetails.State.ONLINE;
        details.pairState = PairState.PAIRED;
        details.runningGameId = 3;
        HostEntry entry = new HostEntry(details, "Sea of Stars", null);
        List<LibraryApp> apps = Arrays.asList(alan, hades, stars);
        HostLibrary.Snapshot snapshot = SnapshotFixtures.ready(HOST, apps, stars.app);

        HostRepository repository = mock(HostRepository.class);
        when(repository.getLibrary()).thenReturn(new MutableLiveData<>(snapshot));
        when(repository.getHosts()).thenReturn(
                new MutableLiveData<List<HostEntry>>(Collections.singletonList(entry)));
        when(repository.getSelectedHostUuid()).thenReturn(new MutableLiveData<>(HOST));
        when(repository.getHost(any())).thenReturn(entry);

        Context context = ApplicationProvider.getApplicationContext();
        feed = new CompanionFeed(context, repository, mock(LaunchController.class), () -> { });
        feed.start();
    }

    @After
    public void tearDown() {
        feed.stop();
    }

    private static void settle() {
        Shadows.shadowOf(Looper.getMainLooper())
                .idleFor(Duration.ofMillis(CompanionFeed.FOCUS_SETTLE_MS + 10));
    }

    private static Browsing shown() {
        return CompanionState.getInstance().getBrowsing();
    }

    @Test
    public void aSheetsGame_isShownAtOnce_andFocusUnderItWaitsForItToClose() {
        feed.focusGame(alan);
        settle();
        assertEquals(1, shown().appId);

        // A long-press by touch on another game: the d-pad stays, the panel follows the sheet.
        feed.holdGame(hades);
        assertEquals(2, shown().appId);
        feed.focusGame(stars);
        settle();
        assertEquals("the sheet's game stays while it is open", 2, shown().appId);

        feed.holdGame(null);
        settle();
        assertEquals("the focus reported under the sheet is drawn", 3, shown().appId);
    }

    @Test
    public void aSheetClosed_goesBackToTheFocusedGame() {
        feed.focusGame(alan);
        settle();
        feed.holdGame(hades);
        feed.holdGame(null);
        settle();
        assertEquals(1, shown().appId);
    }

    @Test
    public void aHost_isNotOfferedTheHerosResumeAndQuit() {
        feed.focusHost(null);
        settle();
        assertEquals(Browsing.Kind.HOST, shown().kind);
        assertFalse(shown().hasGame());
        assertFalse(shown().running);
    }
}
