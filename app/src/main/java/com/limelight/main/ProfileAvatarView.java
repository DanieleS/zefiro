package com.limelight.main;

import android.content.Context;
import android.content.Intent;
import android.graphics.Typeface;
import android.util.AttributeSet;
import android.util.TypedValue;
import android.view.Gravity;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.core.content.ContextCompat;

import com.limelight.ProfilesActivity;
import com.limelight.R;
import com.limelight.profiles.ProfilesManager;

import java.util.Locale;

/**
 * The settings profile at the foot of the rail: a disc with the active profile's initial, or a
 * person icon when no profile is active. A click opens the profiles screen.
 *
 * <p>"Profile" is a settings profile, not an account: the old screens showed it as a labelled
 * button in their top bars, and on the narrow rail the initial stands in for the name. A compact
 * window has no rail, and a short one (a phone in landscape) leaves the avatar out, as its mock
 * does (see NavChrome); both reach Profiles from Settings instead.
 *
 * <h3>Listening</h3>
 *
 * The view listens to {@link ProfilesManager} only while it is attached, so a detached avatar is
 * never called and never leaks its activity through the manager's listener list. The manager may
 * notify from any thread; the update is posted to this view's own thread.
 */
public class ProfileAvatarView extends FrameLayout {

    private final TextView initial;
    private final ImageView icon;

    private final ProfilesManager.ProfileChangeListener listener = () -> post(this::refresh);

    public ProfileAvatarView(Context context) {
        this(context, null);
    }

    public ProfileAvatarView(Context context, AttributeSet attrs) {
        super(context, attrs);
        setBackgroundResource(R.drawable.nav_avatar_bg);
        setFocusable(true);
        setClickable(true);

        initial = new TextView(context);
        initial.setGravity(Gravity.CENTER);
        initial.setTextColor(ContextCompat.getColor(context, R.color.signal));
        initial.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        initial.setTypeface(Typeface.DEFAULT_BOLD);
        initial.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        addView(initial, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));

        int iconSize = getResources().getDimensionPixelSize(R.dimen.nav_avatar_icon_size);
        icon = new ImageView(context);
        icon.setImageResource(R.drawable.ic_profiles);
        icon.setColorFilter(ContextCompat.getColor(context, R.color.signal));
        icon.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        addView(icon, new LayoutParams(iconSize, iconSize, Gravity.CENTER));

        setOnClickListener(v -> getContext().startActivity(
                new Intent(getContext(), ProfilesActivity.class)));

        refresh();
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        ProfilesManager.getInstance().addListener(listener);
        // The profile may have changed while the view was away (in the profiles screen, most
        // likely), with nobody listening.
        refresh();
    }

    @Override
    protected void onDetachedFromWindow() {
        ProfilesManager.getInstance().removeListener(listener);
        super.onDetachedFromWindow();
    }

    /** Main thread only. */
    private void refresh() {
        String name = ProfilesManager.getInstance().getActiveName().trim();
        if (name.isEmpty()) {
            initial.setVisibility(GONE);
            icon.setVisibility(VISIBLE);
            setContentDescription(getResources().getString(R.string.nav_profile_a11y_none));
        } else {
            int first = name.codePointAt(0);
            initial.setText(new String(Character.toChars(first)).toUpperCase(Locale.getDefault()));
            initial.setVisibility(VISIBLE);
            icon.setVisibility(GONE);
            setContentDescription(getResources().getString(R.string.nav_profile_a11y, name));
        }
    }
}
