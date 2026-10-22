package com.limelight.ui;

import android.animation.ObjectAnimator;
import android.animation.ValueAnimator;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.PorterDuff;
import android.graphics.RenderEffect;
import android.graphics.Shader;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;
import android.text.TextUtils;
import android.text.format.DateUtils;
import android.util.TypedValue;
import android.view.DisplayCutout;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.DecelerateInterpolator;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.RelativeLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.lifecycle.LifecycleOwner;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.imageview.ShapeableImageView;
import com.limelight.LimeLog;
import com.limelight.R;
import com.limelight.companion.CompanionState;
import com.limelight.computers.HostLibrary;
import com.limelight.main.WindowClass;
import com.limelight.nvstream.http.AppMetadata;
import com.limelight.stats.StatsFormat;
import com.limelight.stats.StatsText;
import com.limelight.utils.CacheHelper;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Locale;
import java.util.concurrent.Callable;

/**
 * The connection scene, after the approved proposal C: the game's own art full-bleed, the cover
 * and the game's name at the foot with the phase the wait is in and how long it has run, and a
 * Cancel that really cancels, by touch, by the pad (A on it, B, Back or Select from anywhere) and
 * by the keyboard's Escape.
 * <p>
 * It replaces the system progress spinner. The native library reports a dozen fine-grained
 * stages by stable English name; this folds them into a short, honest story and eases a
 * single progress line forward — never backward — as they arrive.
 *
 * <h3>The art</h3>
 *
 * The same art the library's hero and the game page draw: the game's background art when the
 * host's metadata says it has one, else its cover, cropped and darkened, else the bare ground.
 * Both are loaded off the main thread and faded in as they land, the cover first since it is
 * usually on disk, the background over it when the host sends it.
 *
 * <h3>The companion panel</h3>
 *
 * The scene's lower half is the panel's "This session" ({@link CompanionState.Launch}), pushed
 * from here as the wait moves on: the six phases grouped into four steps ({@link #stepOf}), the
 * stream's settings once Game has them, the game's history from the host's cached metadata.
 * It ends with the scene: cancelled or failed, the panel goes back to what it showed; connected,
 * Game hands it the stream.
 *
 * <h3>Keys</h3>
 *
 * Game asks {@link #keyAction} what a key means while the scene is up, before anything else sees
 * it: nothing sent to the pad's handler arrives anywhere before the stream is live, so B, Back and
 * Select used to be swallowed there and "press back" was not true with a pad.
 */
public class ConnectionOverlay {

    /**
     * The phases the wait is told as, most general first. The weight is where the progress
     * line rests once the phase begins (out of 1000); the label is what the player reads.
     */
    enum Phase {
        PREPARING(40, R.string.conn_phase_preparing),
        LAUNCHING(120, R.string.conn_phase_launching),
        REACHING(300, R.string.conn_phase_reaching),
        NEGOTIATING(520, R.string.conn_phase_negotiating),
        MEDIA(700, R.string.conn_phase_media),
        OPENING(880, R.string.conn_phase_opening),
        READY(1000, R.string.conn_phase_ready);

        final int weight;
        final int labelRes;

        Phase(int weight, int labelRes) {
            this.weight = weight;
            this.labelRes = labelRes;
        }
    }

    /** What a key does while the scene is up; see {@link #keyAction}. */
    public enum KeyAction {
        /** Give the launch up, as Cancel does. */
        CANCEL,
        /** Press what has focus, which is Cancel: the scene has no other control. */
        ACTIVATE,
        /** Not the scene's: it goes where it always went. */
        PASS
    }

    /** The same ceiling the library's disk cache uses for one cover. */
    private static final long MAX_COVER_BYTES = 5 * 1024 * 1024;

    // How dark the cover is laid when it stands in for the background art: it is a small picture
    // blown up, so it is the colour of the game rather than a picture of it. 150 of 255 black.
    private static final int COVER_BACKDROP_SHADE = 0x96000000;
    private static final float COVER_BACKDROP_BLUR_DP = 24;

    private static final long PULSE_MS = 900;
    private static final long BACKDROP_FADE_MS = 420;

    private final View root;
    private final View content;
    private final View foot;
    private final View pcChip;
    private final ImageView backdrop;
    private final View coverFrame;
    private final ShapeableImageView cover;
    private final TextView coverTitle;
    private final TextView title;
    private final TextView pcName;
    private final TextView phaseText;
    private final TextView elapsed;
    private final View pulse;
    private final View column;
    private final MaterialButton cancel;
    private final ProgressBar progress;
    @Nullable
    private final View focusAfter;

    private final Handler ui = new Handler(Looper.getMainLooper());
    private final long startUptimeMs = SystemClock.uptimeMillis();
    private ObjectAnimator progressAnimator;
    private ObjectAnimator pulseAnimator;
    private Phase phase = Phase.PREPARING;
    private boolean dismissed = false;
    // Set once the stream is live or the scene is gone: Cancel no longer cancels anything.
    private boolean released = false;
    // Whether the backdrop is the game's background art, which the cover never replaces.
    private boolean backdropIsBackground;
    // What the companion panel draws; null until the game is known.
    @Nullable
    private CompanionState.Launch launch;

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            if (dismissed) {
                return;
            }
            long ms = SystemClock.uptimeMillis() - startUptimeMs;
            elapsed.setText(formatElapsed(ms));
            ui.postDelayed(this, msToNextSecond(ms));
        }
    };

    /**
     * @param owner      the stream activity, for Cancel's pad glyph ({@link ButtonGlyphs})
     * @param focusAfter what takes focus back from Cancel once the scene is going: the stream
     * @param onCancel   what giving the launch up does; run for Cancel, and by Game for the
     *                   keys that cancel
     */
    public ConnectionOverlay(@NonNull View overlayRoot, @NonNull LifecycleOwner owner,
                             @Nullable View focusAfter, @NonNull Runnable onCancel) {
        this.root = overlayRoot;
        this.focusAfter = focusAfter;
        this.content = overlayRoot.findViewById(R.id.connectionContent);
        this.foot = overlayRoot.findViewById(R.id.connectionFoot);
        this.pcChip = overlayRoot.findViewById(R.id.connectionPcChip);
        this.backdrop = overlayRoot.findViewById(R.id.connectionBackdrop);
        this.coverFrame = overlayRoot.findViewById(R.id.connectionCoverFrame);
        this.cover = overlayRoot.findViewById(R.id.connectionCover);
        this.coverTitle = overlayRoot.findViewById(R.id.connectionCoverTitle);
        this.title = overlayRoot.findViewById(R.id.connectionTitle);
        this.pcName = overlayRoot.findViewById(R.id.connectionPcName);
        this.phaseText = overlayRoot.findViewById(R.id.connectionPhase);
        this.elapsed = overlayRoot.findViewById(R.id.connectionElapsed);
        this.pulse = overlayRoot.findViewById(R.id.connectionPulse);
        this.column = overlayRoot.findViewById(R.id.connectionColumn);
        this.cancel = overlayRoot.findViewById(R.id.connectionCancel);
        this.progress = overlayRoot.findViewById(R.id.connectionProgress);

        cancel.setOnClickListener(v -> {
            if (!released) {
                onCancel.run();
            }
        });
        // "B Cancel" while a pad is connected: B gives the launch up wherever focus is.
        ButtonGlyphs.bind(owner, cancel, "B");
        keepClearOfCutout();
        applyWindow(overlayRoot.getResources().getConfiguration());
        applyPhase(Phase.PREPARING);
        startPulse();
        tick.run();
        // Focused, so a pad's A presses it. Posted: the stream view asks for focus as the layout
        // is inflated, and this must come after it. Refused in touch mode, where A still reaches
        // Cancel through keyAction.
        cancel.post(() -> {
            if (!released) {
                cancel.requestFocus();
            }
        });
    }

    /**
     * Dress the scene for a game: its name, its host, and its cover.
     *
     * <p>The cover is the library's cached copy when there is one. A launch that did not come
     * through the library, from a frontend's launcher file for instance, can arrive before the
     * library ever fetched that game's art, and the scene used to stay on the bare title. So when
     * {@code fetch} is given and nothing is cached, the art is asked of the host and written into
     * the same cache, which also spares the library the download later. Until it arrives (or if
     * the host has none) the game's name stands in its place. The cover is also the backdrop until
     * the game's background art arrives, if it ever does.
     */
    public void bind(final File boxArt, final Callable<InputStream> fetch, String appName, String hostName) {
        title.setText(appName != null ? appName : "");
        if (TextUtils.isEmpty(hostName)) {
            pcChip.setVisibility(View.GONE);
        } else {
            pcName.setText(hostName);
            pcChip.setVisibility(View.VISIBLE);
        }
        showCoverTitle(appName);

        launch = new CompanionState.Launch(appName, hostName, startUptimeMs)
                .withStep(stepOf(phase), label(phase));
        CompanionState.getInstance().enterLaunching(launch);

        if (boxArt == null || (!boxArt.exists() && fetch == null)) {
            return;
        }

        final String path = boxArt.getAbsolutePath();
        new Thread(() -> {
            if (!boxArt.exists() && !download(fetch, boxArt)) {
                return;
            }
            final Bitmap bmp = BitmapFactory.decodeFile(path);
            if (bmp == null) {
                return;
            }
            ui.post(() -> {
                if (dismissed) {
                    return;
                }
                cover.setImageBitmap(bmp);
                coverTitle.setVisibility(View.GONE);
                showBackdrop(bmp, false);
            });
        }, "ConnectionCoverDecode").start();
    }

    /**
     * What the host's metadata adds, read from the library's copy on disk rather than asked for:
     * the game's background art, which is fetched only when the metadata says there is one, and
     * when it was last played and for how long, for the companion panel. A stock host has no
     * metadata, and the scene keeps the cover. Off the main thread, all of it.
     *
     * @param fetchBackground asks the host for the game's background art; null when there is no
     *                        connection to ask on
     */
    public void loadHostArt(final File cacheDir, final String hostUuid, final String appUuid,
                            @Nullable final Callable<InputStream> fetchBackground) {
        if (hostUuid == null || appUuid == null) {
            return;
        }
        final Resources res = root.getResources();
        new Thread(() -> {
            AppMetadata metadata = HostLibrary.cachedMetadata(cacheDir, hostUuid, appUuid);
            if (metadata == null) {
                return;
            }
            final String lastPlayed = lastPlayedText(metadata.getLastPlayedEpochMillis());
            final String playtime = metadata.getPlaytimeMinutes() > 0
                    ? StatsFormat.durationMinutes(metadata.getPlaytimeMinutes(), StatsText.words(res))
                    : null;
            ui.post(() -> {
                if (!dismissed && launch != null) {
                    pushLaunch(launch.withHistory(lastPlayed, playtime));
                }
            });

            if (!metadata.hasBackground() || fetchBackground == null) {
                return;
            }
            Bitmap background = null;
            try {
                background = HostLibrary.decodeBackground(fetchBackground.call());
            } catch (Exception e) {
                // The host may already hold the session and refuse; the cover stays.
                LimeLog.info("Connection background fetch failed: " + e.getMessage());
            }
            if (background == null) {
                return;
            }
            final Bitmap loaded = background;
            ui.post(() -> {
                if (!dismissed) {
                    showBackdrop(loaded, true);
                }
            });
        }, "ConnectionBackgroundLoad").start();
    }

    /**
     * What the stream asked for, once Game has settled it, for the companion panel: the
     * resolution and frame rate, the codec with HDR and the bitrate, and which display the host
     * draws the game on. What the host grants is only known once the stream is live.
     */
    public void setStreamFacts(String resolution, String video, String display) {
        if (!dismissed && launch != null) {
            pushLaunch(launch.withStream(resolution, video, display));
        }
    }

    /**
     * Fetch a cover into {@code target}. Written next to it and renamed into place only once it is
     * whole, so a download cut off half-way never leaves a file the library would take for art.
     */
    private static boolean download(Callable<InputStream> fetch, File target) {
        File parent = target.getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            return false;
        }
        File partial = new File(target.getPath() + ".part");
        try (InputStream in = fetch.call()) {
            if (in == null) {
                return false;
            }
            try (OutputStream out = new FileOutputStream(partial)) {
                CacheHelper.writeInputStreamToOutputStream(in, out, MAX_COVER_BYTES);
            }
            if (!partial.renameTo(target)) {
                partial.delete();
                return false;
            }
            return true;
        } catch (Exception e) {
            // No cover is a cosmetic loss; the scene still has the game's name.
            LimeLog.info("Connection cover fetch failed: " + e.getMessage());
            partial.delete();
            return false;
        }
    }

    /** Advance the story from a native stage name (or the app-launch phase). */
    public void onStage(String stage) {
        applyPhase(phaseFor(stage));
    }

    /**
     * The stream is live: run the line home, then fade the scene away. Cancel lets go of focus at
     * once, so a confirm key pressed during the fade reaches the game rather than leave it.
     */
    public void onConnected() {
        applyPhase(Phase.READY);
        releaseCancel();
        root.animate()
                .alpha(0f)
                .setStartDelay(160)
                .setDuration(260)
                .withEndAction(this::dismiss)
                .start();
    }

    public boolean isShowing() {
        return !dismissed && root.getVisibility() == View.VISIBLE;
    }

    /**
     * Take the scene down at once, without a fade (cancel, failure, teardown). The companion
     * panel's "This session" goes with it, unless the stream already took the panel over.
     */
    public void dismiss() {
        if (dismissed) {
            return;
        }
        releaseCancel();
        dismissed = true;
        ui.removeCallbacks(tick);
        if (progressAnimator != null) {
            progressAnimator.cancel();
        }
        if (pulseAnimator != null) {
            pulseAnimator.cancel();
        }
        root.setVisibility(View.GONE);
        CompanionState.getInstance().leaveLaunching(startUptimeMs);
    }

    /** Press Cancel, for a confirm key from a pad or keyboard, whether or not it holds focus. */
    public void activate() {
        if (!released) {
            cancel.performClick();
        }
    }

    /**
     * A new window shape (a rotation, the stream moved to the other screen). The activity handles
     * its own resizes, so a resource qualifier would never be applied again: the sizes are picked
     * here, from the window, as the library's hero picks its cover.
     *
     * <pre>
     *   wide and tall (the Thor's top screen)   large cover and title, Cancel beside the title
     *   wide and short (a phone in landscape)   compact sizes,         Cancel beside the title
     *   narrow (a phone in portrait)            compact, smaller title, Cancel under the cover
     * </pre>
     */
    public void applyWindow(Configuration config) {
        Resources res = root.getResources();
        boolean narrow = WindowClass.from(config.screenWidthDp) == WindowClass.COMPACT;
        boolean large = !narrow
                && !WindowClass.isShortWide(config.screenWidthDp, config.screenHeightDp);

        RelativeLayout.LayoutParams frameLp =
                (RelativeLayout.LayoutParams) coverFrame.getLayoutParams();
        frameLp.width = res.getDimensionPixelSize(large
                ? R.dimen.connection_cover_width_large : R.dimen.connection_cover_width);
        frameLp.height = res.getDimensionPixelSize(large
                ? R.dimen.connection_cover_height_large : R.dimen.connection_cover_height);
        title.setTextSize(TypedValue.COMPLEX_UNIT_PX, res.getDimension(large
                ? R.dimen.connection_title_size_large
                : narrow ? R.dimen.connection_title_size_narrow : R.dimen.connection_title_size));
        coverTitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, large ? 18 : 14);

        int gutter = res.getDimensionPixelSize(large
                ? R.dimen.connection_gutter_large : R.dimen.connection_gutter);
        foot.setPaddingRelative(gutter, 0, gutter, gutter);
        ViewGroup.MarginLayoutParams chipLp = (ViewGroup.MarginLayoutParams) pcChip.getLayoutParams();
        chipLp.setMarginEnd(gutter);
        chipLp.topMargin = large ? gutter : res.getDimensionPixelSize(R.dimen.connection_gutter) - 4;
        pcChip.setLayoutParams(chipLp);

        // Cancel beside the title, or, in a window too narrow for both, under the cover row.
        RelativeLayout.LayoutParams columnLp = (RelativeLayout.LayoutParams) column.getLayoutParams();
        if (narrow) {
            frameLp.removeRule(RelativeLayout.ALIGN_PARENT_BOTTOM);
            frameLp.addRule(RelativeLayout.ABOVE, R.id.connectionCancel);
            frameLp.bottomMargin = res.getDimensionPixelSize(R.dimen.connection_gap);
            columnLp.removeRule(RelativeLayout.START_OF);
            columnLp.removeRule(RelativeLayout.LEFT_OF);
            columnLp.addRule(RelativeLayout.ALIGN_PARENT_END);
        } else {
            frameLp.removeRule(RelativeLayout.ABOVE);
            frameLp.addRule(RelativeLayout.ALIGN_PARENT_BOTTOM);
            frameLp.bottomMargin = 0;
            columnLp.removeRule(RelativeLayout.ALIGN_PARENT_END);
            columnLp.removeRule(RelativeLayout.ALIGN_PARENT_RIGHT);
            columnLp.addRule(RelativeLayout.START_OF, R.id.connectionCancel);
        }
        coverFrame.setLayoutParams(frameLp);
        column.setLayoutParams(columnLp);
    }

    // --- Pure rules, tested off the device ---

    /**
     * What a key means while the scene is up. B, Back, Select and Escape give the launch up,
     * wherever focus is; A and the confirm keys press Cancel, the scene's only control. The face
     * buttons are taken as Android names them, as the in-stream controls take them
     * (ControlsInputRouter): the flip-face-buttons setting swaps what the host is sent, not what
     * the app's own screens read. Every other key goes where it always went: to the pad's
     * handler, which holds it for a stream not there yet, or to the system (the volume keys).
     */
    @NonNull
    public static KeyAction keyAction(int keyCode) {
        switch (keyCode) {
            case KeyEvent.KEYCODE_BUTTON_B:
            case KeyEvent.KEYCODE_BACK:
            case KeyEvent.KEYCODE_BUTTON_SELECT:
            case KeyEvent.KEYCODE_ESCAPE:
                return KeyAction.CANCEL;
            case KeyEvent.KEYCODE_BUTTON_A:
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_NUMPAD_ENTER:
                return KeyAction.ACTIVATE;
            default:
                return KeyAction.PASS;
        }
    }

    /** The scene's clock: "0:07", "12:40", and "1:02:03" past the hour. Negative counts as 0. */
    @NonNull
    public static String formatElapsed(long ms) {
        long seconds = Math.max(0, ms) / 1000;
        long hours = seconds / 3600;
        long minutes = (seconds / 60) % 60;
        long secs = seconds % 60;
        if (hours > 0) {
            return String.format(Locale.ROOT, "%d:%02d:%02d", hours, minutes, secs);
        }
        return String.format(Locale.ROOT, "%d:%02d", minutes, secs);
    }

    /** How long until the clock next turns over, so a tick lands just after each second. */
    public static long msToNextSecond(long elapsedMs) {
        return 1000 - (Math.max(0, elapsedMs) % 1000);
    }

    /**
     * The step of the companion panel's four a phase belongs to: getting ready and waking the
     * game start it, reaching the PC and negotiating connect, the media streams are video and
     * controls, and opening them opens the stream. Ready is past the last step.
     */
    static int stepOf(@NonNull Phase phase) {
        switch (phase) {
            case PREPARING:
            case LAUNCHING:
                return 0;
            case REACHING:
            case NEGOTIATING:
                return 1;
            case MEDIA:
                return 2;
            case OPENING:
                return 3;
            case READY:
            default:
                return CompanionState.Launch.STEP_COUNT;
        }
    }

    /**
     * Fold the native library's fine-grained stages into a phase. The names are stable,
     * locale-independent constants from LiGetStageName(); anything unrecognised (notably
     * the app-launch step, which arrives as the game's own name) is the launch phase.
     */
    static Phase phaseFor(String stage) {
        if (stage == null) {
            return Phase.LAUNCHING;
        }
        switch (stage) {
            case "platform initialization":
            case "name resolution":
                return Phase.REACHING;
            case "audio stream initialization":
            case "RTSP handshake":
            case "control stream initialization":
                return Phase.NEGOTIATING;
            case "video stream initialization":
            case "input stream initialization":
                return Phase.MEDIA;
            case "control stream establishment":
            case "video stream establishment":
            case "audio stream establishment":
            case "input stream establishment":
                return Phase.OPENING;
            default:
                return Phase.LAUNCHING;
        }
    }

    // --- Drawing ---

    private void applyPhase(Phase next) {
        // The line only ever moves forward, so a fast phase can't yank it back.
        if (next.weight >= phase.weight) {
            phase = next;
        }
        phaseText.setText(label(next));
        animateProgressTo(phase.weight);
        if (launch != null) {
            // The panel's steps follow the line: forward only, and named by the phase it rests on.
            pushLaunch(launch.withStep(stepOf(phase), label(phase)));
        }
    }

    private String label(Phase p) {
        return root.getResources().getString(p.labelRes);
    }

    private void pushLaunch(CompanionState.Launch next) {
        launch = next;
        CompanionState.getInstance().updateLaunch(next);
    }

    private void animateProgressTo(int target) {
        if (progressAnimator != null) {
            progressAnimator.cancel();
        }
        progressAnimator = ObjectAnimator.ofInt(progress, "progress", progress.getProgress(), target);
        progressAnimator.setDuration(520);
        progressAnimator.setInterpolator(new DecelerateInterpolator());
        progressAnimator.start();
    }

    /**
     * The live dot before the phase breathes while the host works. Still when the system's
     * animations are off: an endless pulse is exactly what "remove animations" asks to stop.
     */
    private void startPulse() {
        if (!animationsEnabled()) {
            return;
        }
        pulseAnimator = ObjectAnimator.ofFloat(pulse, View.ALPHA, 1f, 0.25f);
        pulseAnimator.setDuration(PULSE_MS);
        pulseAnimator.setRepeatMode(ValueAnimator.REVERSE);
        pulseAnimator.setRepeatCount(ValueAnimator.INFINITE);
        pulseAnimator.start();
    }

    private boolean animationsEnabled() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            return ValueAnimator.areAnimatorsEnabled();
        }
        return Settings.Global.getFloat(root.getContext().getContentResolver(),
                Settings.Global.ANIMATOR_DURATION_SCALE, 1f) != 0f;
    }

    /**
     * Lay art behind the scene and fade it in. The background art is the game's own picture and
     * is drawn as it is; the cover only stands in for it, darkened (and blurred where the system
     * can), and never replaces it.
     */
    private void showBackdrop(Bitmap art, boolean isBackground) {
        if (backdropIsBackground && !isBackground) {
            return;
        }
        backdropIsBackground = isBackground;
        backdrop.setImageBitmap(art);
        if (isBackground) {
            backdrop.clearColorFilter();
        } else {
            backdrop.setColorFilter(COVER_BACKDROP_SHADE, PorterDuff.Mode.SRC_ATOP);
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            float radius = COVER_BACKDROP_BLUR_DP * root.getResources().getDisplayMetrics().density;
            backdrop.setRenderEffect(isBackground ? null
                    : RenderEffect.createBlurEffect(radius, radius, Shader.TileMode.CLAMP));
        }
        backdrop.setVisibility(View.VISIBLE);
        backdrop.animate().cancel();
        backdrop.setAlpha(0f);
        backdrop.animate().alpha(1f).setDuration(BACKDROP_FADE_MS).start();
    }

    private void showCoverTitle(String appName) {
        if (cover.getDrawable() == null) {
            coverTitle.setText(appName != null ? appName : "");
            coverTitle.setVisibility(View.VISIBLE);
        }
    }

    /**
     * Cancel stops taking focus and presses, and the stream gets the focus back. Clickable off
     * rather than disabled, so it does not dim during the fade.
     */
    private void releaseCancel() {
        if (released) {
            return;
        }
        released = true;
        boolean hadFocus = cancel.hasFocus();
        cancel.setClickable(false);
        cancel.setFocusable(false);
        if (hadFocus && focusAfter != null) {
            focusAfter.requestFocus();
        }
    }

    /**
     * The window runs under the display cutout, as the stream does; the scene's words and
     * Cancel are moved in by its safe insets, as the HUD is, while the art and the progress line
     * run edge to edge.
     */
    private void keepClearOfCutout() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) {
            return;
        }
        content.setOnApplyWindowInsetsListener((v, insets) -> {
            DisplayCutout cutout = insets.getDisplayCutout();
            if (cutout == null) {
                v.setPadding(0, 0, 0, 0);
            } else {
                v.setPadding(cutout.getSafeInsetLeft(), cutout.getSafeInsetTop(),
                        cutout.getSafeInsetRight(), cutout.getSafeInsetBottom());
            }
            return insets;
        });
    }

    /** "2 days ago", from the host's stamp; null when it has none. */
    @Nullable
    private static String lastPlayedText(long when) {
        if (when <= 0) {
            return null;
        }
        return DateUtils.getRelativeTimeSpanString(when, System.currentTimeMillis(),
                DateUtils.MINUTE_IN_MILLIS).toString();
    }
}
