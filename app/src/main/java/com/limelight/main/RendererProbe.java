package com.limelight.main;

import android.content.Context;
import android.opengl.GLSurfaceView;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.view.ViewGroup;

import androidx.annotation.NonNull;

import com.limelight.LimeLog;
import com.limelight.library.LaunchController;
import com.limelight.preferences.GlPreferences;

import java.util.ArrayList;
import java.util.List;

import javax.microedition.khronos.egl.EGLConfig;
import javax.microedition.khronos.opengles.GL10;

/**
 * Learns the device's GL renderer once per system build, for the decoder choices the stream
 * makes from it ({@link GlPreferences}).
 *
 * <p>The renderer string is only available from inside a GL context. PcView got one by making a
 * {@link GLSurfaceView} its whole content and building its real screen only after the surface
 * came up, which blanked the screen on every first launch. This instead slips a 1×1 surface
 * into the root next to the real content, saves the string from {@code onSurfaceCreated} and
 * takes the surface away again; the screen around it is usable the whole time.
 *
 * <p>Whatever needs the renderer known (starting a stream) waits for it through
 * {@link #whenReady(Runnable)}, which is also the {@code LaunchController}'s gate. When the saved
 * value still matches this build there is nothing to probe and it runs at once.
 *
 * <p>Main thread only, except the renderer callback, which runs on the GL thread and only writes
 * the preferences before posting back.
 */
final class RendererProbe implements LaunchController.StartGate {

    private final Context context;
    private final ViewGroup root;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final List<Runnable> waiting = new ArrayList<>();

    private GLSurfaceView surface;
    private boolean ready;

    RendererProbe(Context context, ViewGroup root) {
        this.context = context;
        this.root = root;
    }

    /** Probes when the saved renderer is missing or from another build; otherwise ready now. */
    void start() {
        final GlPreferences prefs = GlPreferences.readPreferences(context);
        if (prefs.savedFingerprint.equals(Build.FINGERPRINT) && !prefs.glRenderer.isEmpty()) {
            LimeLog.info("Cached GL Renderer: " + prefs.glRenderer);
            markReady();
            return;
        }

        surface = new GLSurfaceView(context);
        surface.setRenderer(new GLSurfaceView.Renderer() {
            @Override
            public void onSurfaceCreated(GL10 gl10, EGLConfig eglConfig) {
                // GL thread.
                prefs.glRenderer = gl10.glGetString(GL10.GL_RENDERER);
                prefs.savedFingerprint = Build.FINGERPRINT;
                prefs.writePreferences();
                LimeLog.info("Fetched GL Renderer: " + prefs.glRenderer);
                main.post(RendererProbe.this::finish);
            }

            @Override
            public void onSurfaceChanged(GL10 gl10, int width, int height) {
            }

            @Override
            public void onDrawFrame(GL10 gl10) {
            }
        });
        root.addView(surface, new ViewGroup.LayoutParams(1, 1));
    }

    /** Runs the task once the renderer is known: now if it is, else when the probe finishes. */
    @Override
    public void whenReady(@NonNull Runnable task) {
        if (ready) {
            task.run();
        } else {
            waiting.add(task);
        }
    }

    /** Drops the probe surface and anything still waiting; the activity is going away. */
    void release() {
        waiting.clear();
        removeSurface();
    }

    private void finish() {
        removeSurface();
        markReady();
    }

    private void removeSurface() {
        if (surface != null) {
            // Detaching the view ends its GL thread.
            root.removeView(surface);
            surface = null;
        }
    }

    private void markReady() {
        ready = true;
        List<Runnable> tasks = new ArrayList<>(waiting);
        waiting.clear();
        for (Runnable task : tasks) {
            task.run();
        }
    }
}
