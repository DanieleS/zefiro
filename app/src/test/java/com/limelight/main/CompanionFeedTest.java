package com.limelight.main;

import android.content.Context;
import android.os.Looper;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.lifecycle.MutableLiveData;
import androidx.test.core.app.ApplicationProvider;

import com.limelight.TestLogSuppressor;
import com.limelight.companion.CompanionPage;
import com.limelight.companion.CompanionPageRenderer;
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
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * What the companion panel shows while browsing: the game the d-pad is on, the game an actions
 * sheet is open for while it is open, a host without the hero's Resume and Quit game, and a
 * screen's page across a stream.
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

    @Test
    public void aPageClearedBehindAStream_comesBack_andItsOwnerIsAskedAgain() {
        final CompanionPage after = new TestPage("after");
        final boolean[] asked = {false};
        CompanionPages.PageOwner owner = new CompanionPages.PageOwner() {
            @Override
            public void onPageTap(int stopId) {
            }

            @Override
            public boolean republishPage() {
                asked[0] = true;
                feed.publish(this, after);
                return true;
            }
        };
        CompanionPage before = new TestPage("before");
        feed.publish(owner, before);

        // A stream: the screen behind it reads as off screen and takes its page off.
        feed.stop();
        feed.clear(owner);
        feed.start();
        assertEquals("the page left is drawn again, not the idle hosts", before,
                CompanionState.getInstance().getPage());

        feed.resume();
        assertTrue(asked[0]);
        assertEquals(after, CompanionState.getInstance().getPage());
    }

    @Test
    public void aPageClearedBehindAStream_byAnOwnerThatDoesNotRepublish_staysOff() {
        CompanionPages.PageOwner owner = stopId -> { };
        feed.publish(owner, new TestPage("page"));

        feed.stop();
        feed.clear(owner);
        feed.start();
        feed.resume();
        assertNull(CompanionState.getInstance().getPage());
    }

    /** A page that is never drawn here, told apart by its text. */
    private static final class TestPage extends CompanionPage {
        private static final CompanionPageRenderer RENDERER = new CompanionPageRenderer() {
            @NonNull
            @Override
            public View create(@NonNull LayoutInflater inflater, @NonNull ViewGroup parent) {
                throw new UnsupportedOperationException("not drawn here");
            }

            @Override
            public void bind(@NonNull View view, @NonNull CompanionPage page,
                             @NonNull PageTaps taps) {
                throw new UnsupportedOperationException("not drawn here");
            }
        };

        private final String text;

        TestPage(String text) {
            super(NO_STOP);
            this.text = text;
        }

        @NonNull
        @Override
        public CompanionPageRenderer renderer() {
            return RENDERER;
        }

        @NonNull
        @Override
        public CompanionPage withFocus(int stop) {
            return this;
        }

        @Override
        protected boolean sameContent(@NonNull CompanionPage other) {
            return text.equals(((TestPage) other).text);
        }

        @Override
        protected int contentHash() {
            return text.hashCode();
        }
    }
}
