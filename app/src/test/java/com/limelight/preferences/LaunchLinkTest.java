package com.limelight.preferences;

import android.content.Intent;
import android.net.Uri;

import com.limelight.Game;
import com.limelight.ShortcutTrampoline;
import com.limelight.library.LibraryIntents;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * An {@code art://launch} link hands the game over to the trampoline without clearing the task:
 * cleared, it finished a library already open, so ending the stream left the app (both Thor
 * screens on the launcher) instead of landing back on the library.
 */
@Config(sdk = {33}, shadows = {com.limelight.shadows.ShadowMoonBridge.class})
@RunWith(RobolectricTestRunner.class)
public class LaunchLinkTest {

    @Test
    public void aLaunchLinkKeepsTheTaskUnderTheStream() {
        Intent link = new Intent(Intent.ACTION_VIEW, Uri.parse(
                "art://launch?host_uuid=uuid-1&host_name=PC&app_uuid=app-uuid&app_name=Game&app_id=7"));
        AddComputerManually activity = Robolectric.buildActivity(AddComputerManually.class, link)
                .create().get();

        Intent next = Shadows.shadowOf(activity).getNextStartedActivity();
        assertNotNull(next);
        assertEquals(ShortcutTrampoline.class.getName(), next.getComponent().getClassName());
        assertEquals(0, next.getFlags() & Intent.FLAG_ACTIVITY_CLEAR_TASK);
        assertTrue((next.getFlags() & Intent.FLAG_ACTIVITY_NEW_TASK) != 0);
        assertEquals("uuid-1", next.getStringExtra(LibraryIntents.EXTRA_UUID));
        assertEquals("7", next.getStringExtra(Game.EXTRA_APP_ID));
        assertTrue(activity.isFinishing());
    }
}
