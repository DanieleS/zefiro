package com.limelight.ui;

import android.app.Dialog;
import android.content.Context;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.appcompat.view.ContextThemeWrapper;
import androidx.core.content.ContextCompat;
import androidx.test.core.app.ApplicationProvider;

import com.limelight.R;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

/**
 * The menu's optional header line and its danger row, which are additive: a menu that uses
 * neither looks as it always did.
 */
@Config(sdk = {33})
@RunWith(RobolectricTestRunner.class)
public class MenuSheetTest {

    // A themed context rather than an activity: an activity would wake the companion display,
    // whose preferences load the native streaming library.
    private Context context;

    @Before
    public void setUp() {
        context = new ContextThemeWrapper(ApplicationProvider.getApplicationContext(), R.style.AppTheme);
    }

    private static ViewGroup rows(Dialog dialog) {
        return dialog.findViewById(R.id.menuSheetItems);
    }

    @Test
    public void theSubtitleShowsUnderTheTitleOnlyWhenSet() {
        Dialog plain = new MenuSheet(context).setTitle("Taverna")
                .add("Test Network Connection", () -> { })
                .showCentered(context);
        assertEquals(View.GONE, plain.findViewById(R.id.menuSheetSubtitle).getVisibility());
        plain.dismiss();

        Dialog withFacts = new MenuSheet(context).setTitle("Taverna")
                .setSubtitle("192.168.1.20 · 16 games")
                .add("Test Network Connection", () -> { })
                .showCentered(context);
        TextView subtitle = withFacts.findViewById(R.id.menuSheetSubtitle);
        assertEquals(View.VISIBLE, subtitle.getVisibility());
        assertEquals("192.168.1.20 · 16 games", subtitle.getText().toString());
        withFacts.dismiss();
    }

    @Test
    public void aDangerRowIsDrawnInTheDangerToneAndStillActs() {
        final boolean[] deleted = {false};
        Dialog dialog = new MenuSheet(context)
                .add("View Details", R.drawable.ic_info, () -> { })
                .addDanger("Delete PC", R.drawable.ic_delete, () -> deleted[0] = true)
                .showCentered(context);

        int danger = ContextCompat.getColor(context, R.color.danger);
        TextView ordinary = rows(dialog).getChildAt(0).findViewById(R.id.menuRowLabel);
        View deleteRow = rows(dialog).getChildAt(1);
        TextView deleteLabel = deleteRow.findViewById(R.id.menuRowLabel);
        assertNotEquals(danger, ordinary.getCurrentTextColor());
        assertEquals(danger, deleteLabel.getCurrentTextColor());
        assertEquals(View.VISIBLE, deleteRow.findViewById(R.id.menuRowIcon).getVisibility());
        assertTrue(deleteRow.isFocusable());

        deleteRow.performClick();
        assertTrue(deleted[0]);
    }
}
