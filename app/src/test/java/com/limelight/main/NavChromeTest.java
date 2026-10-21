package com.limelight.main;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.appcompat.view.ContextThemeWrapper;
import androidx.test.core.app.ApplicationProvider;

import com.google.android.material.bottomnavigation.BottomNavigationView;
import com.google.android.material.navigationrail.NavigationRailView;
import com.limelight.R;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

/**
 * The navigation widgets on their own: Settings as a section, the rail's foot button standing in
 * for its hidden item.
 */
@Config(sdk = {33})
@RunWith(RobolectricTestRunner.class)
public class NavChromeTest {

    private final List<Section> chosen = new ArrayList<>();
    private final List<Section> reselected = new ArrayList<>();
    private NavigationRailView rail;
    private BottomNavigationView bottom;
    private View foot;
    private NavChrome chrome;

    @Before
    public void inflate() {
        Context context = new ContextThemeWrapper(ApplicationProvider.getApplicationContext(),
                R.style.AppTheme);
        View root = LayoutInflater.from(context).inflate(R.layout.activity_main, null);
        rail = root.findViewById(R.id.navRail);
        bottom = root.findViewById(R.id.navBottom);
        foot = root.findViewById(R.id.navRailSettings);
        chrome = new NavChrome(root.findViewById(R.id.navRailColumn), rail, foot,
                root.findViewById(R.id.navAvatar), bottom, new NavChrome.Callbacks() {
                    @Override
                    public void onSectionChosen(@NonNull Section section) {
                        chosen.add(section);
                    }

                    @Override
                    public void onSectionReselected(@NonNull Section section) {
                        reselected.add(section);
                    }
                });
        chrome.apply(WindowClass.WIDE, false);
        chrome.setChecked(Section.LIBRARY);
    }

    @Test
    public void theFootButton_isActivatedIffSettingsIsChecked() {
        assertFalse(foot.isActivated());

        chrome.setChecked(Section.SETTINGS);
        assertTrue(foot.isActivated());
        assertEquals(R.id.nav_settings, rail.getSelectedItemId());
        assertEquals(R.id.nav_settings, bottom.getSelectedItemId());

        chrome.setChecked(Section.PCS);
        assertFalse(foot.isActivated());
        assertEquals(R.id.nav_pcs, rail.getSelectedItemId());
    }

    @Test
    public void theFootButton_choosesSettings_thenReselectsIt() {
        foot.performClick();

        assertEquals(1, chosen.size());
        assertEquals(Section.SETTINGS, chosen.get(0));
        assertTrue(foot.isActivated());
        assertEquals(R.id.nav_settings, bottom.getSelectedItemId());
        // Focus comes back from the section to the foot, the hidden item's stand-in.
        assertSame(foot, chrome.checkedItemView());

        foot.performClick();
        assertEquals(1, chosen.size());
        assertEquals(1, reselected.size());
        assertEquals(Section.SETTINGS, reselected.get(0));
    }

    @Test
    public void theBottomBarsSettings_isASection() {
        chrome.apply(WindowClass.COMPACT, false);

        bottom.findViewById(R.id.nav_settings).performClick();

        assertEquals(Section.SETTINGS, chosen.get(0));
        assertTrue(foot.isActivated());
        assertEquals(R.id.nav_settings, rail.getSelectedItemId());

        rail.findViewById(R.id.nav_library).performClick();
        assertFalse(foot.isActivated());
    }
}
