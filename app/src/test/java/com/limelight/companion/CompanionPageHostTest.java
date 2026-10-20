package com.limelight.companion;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;

import androidx.annotation.NonNull;
import androidx.test.core.app.ApplicationProvider;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.util.Objects;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * The page container binds a value once: the presentation's redraws for other state (hosts,
 * backdrop) must not bind it again, or a list scrolled on the panel jumps back to the stop.
 */
@Config(sdk = {33})
@RunWith(RobolectricTestRunner.class)
public class CompanionPageHostTest {

    private static int binds;

    private static final CompanionPageRenderer RENDERER = new CompanionPageRenderer() {
        @NonNull
        @Override
        public View create(@NonNull LayoutInflater inflater, @NonNull ViewGroup parent) {
            return new View(parent.getContext());
        }

        @Override
        public void bind(@NonNull View view, @NonNull CompanionPage page,
                         @NonNull PageTaps taps) {
            binds++;
        }
    };

    private static final class TestPage extends CompanionPage {
        final String title;

        TestPage(String title, int focusedStop) {
            super(focusedStop);
            this.title = title;
        }

        @NonNull
        @Override
        public CompanionPageRenderer renderer() {
            return RENDERER;
        }

        @NonNull
        @Override
        public CompanionPage withFocus(int stop) {
            return new TestPage(title, stop);
        }

        @Override
        protected boolean sameContent(@NonNull CompanionPage other) {
            return title.equals(((TestPage) other).title);
        }

        @Override
        protected int contentHash() {
            return Objects.hashCode(title);
        }
    }

    private CompanionPageHost host;
    private FrameLayout container;

    @Before
    public void setUp() {
        binds = 0;
        Context context = ApplicationProvider.getApplicationContext();
        container = new FrameLayout(context);
        host = new CompanionPageHost(container, LayoutInflater.from(context));
    }

    @Test
    public void anEqualValueIsNotBoundAgainANewOneIs() {
        assertTrue(host.show(new TestPage("Hades", CompanionPage.NO_STOP)));
        assertTrue(host.show(new TestPage("Hades", CompanionPage.NO_STOP)));
        assertEquals(1, binds);
        assertEquals(View.VISIBLE, container.getVisibility());

        // A focus move is a new value.
        assertTrue(host.show(new TestPage("Hades", 3)));
        assertEquals(2, binds);

        // Shown again after being hidden: bound again.
        host.hide();
        assertTrue(host.show(new TestPage("Hades", 3)));
        assertEquals(3, binds);
        assertEquals(View.VISIBLE, container.getVisibility());
    }
}
