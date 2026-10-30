package app.binnacle;

import android.graphics.SurfaceTexture;
import android.opengl.EGL14;
import android.opengl.EGLConfig;
import android.opengl.EGLContext;
import android.opengl.EGLDisplay;
import android.opengl.EGLSurface;
import android.opengl.GLES11Ext;
import android.opengl.GLES20;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.Log;
import android.util.Size;
import android.view.Choreographer;
import android.view.Surface;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Shows camera frames on an even cadence. The unit's panel refreshes at 56 Hz and the camera
 * sends a steady 30 fps, so drawn straight through, most frames stay up for two refreshes and
 * every seventh or so for only one: the judder seen when moving. This puts the camera into its
 * own texture and redraws the output only on every second vsync (28 fps), so every frame shown
 * stays up exactly two refreshes; the frames that don't fit (about one in fifteen) are skipped.
 *
 * The output is the TextureView's own SurfaceTexture, filled edge to edge like the TextureView
 * does itself, so its setTransform (rotation and crop) still applies unchanged. All GL work runs
 * on one "dash-camera-gl" thread; create() and release() block briefly until it has finished.
 */
final class FramePacer {

    private static final String TAG = "binnacle";
    /** Draw on every Nth vsync: 56 Hz / 2 = 28 fps, the closest even cadence below the camera's 30. */
    private static final int VSYNCS_PER_FRAME = 2;

    private static final String VS =
            "attribute vec4 aPos; attribute vec4 aTex; uniform mat4 uTex; varying vec2 vTex;\n"
            + "void main() { gl_Position = aPos; vTex = (uTex * aTex).xy; }";
    private static final String FS =
            "#extension GL_OES_EGL_image_external : require\n"
            + "precision mediump float; varying vec2 vTex; uniform samplerExternalOES sTex;\n"
            + "void main() { gl_FragColor = texture2D(sTex, vTex); }";
    private static final float[] QUAD = {   // x, y, s, t
            -1, -1, 0, 0,   1, -1, 1, 0,   -1, 1, 0, 1,   1, 1, 1, 1 };

    private final HandlerThread thread = new HandlerThread("dash-camera-gl");
    private final Handler gl;
    private EGLDisplay display = EGL14.EGL_NO_DISPLAY;
    private EGLContext context = EGL14.EGL_NO_CONTEXT;
    private EGLSurface out = EGL14.EGL_NO_SURFACE;
    private SurfaceTexture camTexture;
    private Surface input;
    private int program, tex, aPos, aTex, uTex;
    private final float[] texMatrix = new float[16];
    private final FloatBuffer quad;
    private boolean newFrame, running;
    private long vsyncs;

    private final Choreographer.FrameCallback tick = new Choreographer.FrameCallback() {
        @Override public void doFrame(long frameTimeNanos) {
            if (!running) return;
            Choreographer.getInstance().postFrameCallback(this);
            if (++vsyncs % VSYNCS_PER_FRAME != 0 || !newFrame) return;
            newFrame = false;
            try {
                camTexture.updateTexImage();     // latches the newest frame; older ones are dropped
                camTexture.getTransformMatrix(texMatrix);
                draw();
            } catch (Throwable t) {
                Log.w(TAG, "frame pacer draw failed", t);
            }
        }
    };

    private FramePacer() {
        quad = ByteBuffer.allocateDirect(QUAD.length * 4).order(ByteOrder.nativeOrder()).asFloatBuffer();
        quad.put(QUAD).position(0);
        thread.start();
        gl = new Handler(thread.getLooper());
    }

    /** Sets up GL drawing into `output`; returns null (and cleans up) if anything fails. */
    static FramePacer create(final SurfaceTexture output, final Size cameraSize) {
        final FramePacer p = new FramePacer();
        final boolean[] ok = new boolean[1];
        final CountDownLatch done = new CountDownLatch(1);
        p.gl.post(new Runnable() {
            @Override public void run() {
                try {
                    p.setUp(output, cameraSize);
                    ok[0] = true;
                } catch (Throwable t) {
                    Log.w(TAG, "frame pacer unavailable, showing frames directly", t);
                    p.tearDown();
                } finally {
                    done.countDown();
                }
            }
        });
        try {
            if (!done.await(1500, TimeUnit.MILLISECONDS)) ok[0] = false;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        if (!ok[0]) {
            // Timed out: the setup may still finish later and connect to `output`. Tear down after
            // it (same thread, so in order) and wait, so the direct fallback gets a free surface.
            p.release();
            return null;
        }
        return p;
    }

    /** The surface to give the camera. */
    Surface input() { return input; }

    /**
     * Stops drawing and frees everything, before the output SurfaceTexture is released. Runs
     * after anything already queued on the GL thread, including a setup that is still going.
     */
    void release() {
        final CountDownLatch done = new CountDownLatch(1);
        gl.post(new Runnable() {
            @Override public void run() {
                tearDown();
                done.countDown();
            }
        });
        try {
            if (!done.await(3000, TimeUnit.MILLISECONDS)) Log.w(TAG, "frame pacer teardown timed out");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        thread.quitSafely();
    }

    // ---- GL thread ----

    private void setUp(SurfaceTexture output, Size size) {
        display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY);
        int[] v = new int[2];
        if (!EGL14.eglInitialize(display, v, 0, v, 1)) throw new IllegalStateException("eglInitialize");
        EGLConfig[] cfg = new EGLConfig[1];
        int[] n = new int[1];
        int[] attrs = { EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8, EGL14.EGL_BLUE_SIZE, 8,
                EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT, EGL14.EGL_NONE };
        if (!EGL14.eglChooseConfig(display, attrs, 0, cfg, 0, 1, n, 0) || n[0] == 0) throw new IllegalStateException("eglChooseConfig");
        context = EGL14.eglCreateContext(display, cfg[0], EGL14.EGL_NO_CONTEXT,
                new int[]{ EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE }, 0);
        if (context == EGL14.EGL_NO_CONTEXT) throw new IllegalStateException("eglCreateContext");
        out = EGL14.eglCreateWindowSurface(display, cfg[0], output, new int[]{ EGL14.EGL_NONE }, 0);
        if (out == EGL14.EGL_NO_SURFACE) throw new IllegalStateException("eglCreateWindowSurface");
        if (!EGL14.eglMakeCurrent(display, out, out, context)) throw new IllegalStateException("eglMakeCurrent");

        program = link(compile(GLES20.GL_VERTEX_SHADER, VS), compile(GLES20.GL_FRAGMENT_SHADER, FS));
        aPos = GLES20.glGetAttribLocation(program, "aPos");
        aTex = GLES20.glGetAttribLocation(program, "aTex");
        uTex = GLES20.glGetUniformLocation(program, "uTex");
        int[] t = new int[1];
        GLES20.glGenTextures(1, t, 0);
        tex = t[0];
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, tex);
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);

        camTexture = new SurfaceTexture(tex);
        camTexture.setDefaultBufferSize(size.getWidth(), size.getHeight());
        camTexture.setOnFrameAvailableListener(new SurfaceTexture.OnFrameAvailableListener() {
            @Override public void onFrameAvailable(SurfaceTexture st) { newFrame = true; }
        }, gl);
        input = new Surface(camTexture);
        running = true;
        Choreographer.getInstance().postFrameCallback(tick);
    }

    private void draw() {
        int[] wh = new int[2];
        EGL14.eglQuerySurface(display, out, EGL14.EGL_WIDTH, wh, 0);
        EGL14.eglQuerySurface(display, out, EGL14.EGL_HEIGHT, wh, 1);
        GLES20.glViewport(0, 0, wh[0], wh[1]);
        GLES20.glUseProgram(program);
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, tex);
        GLES20.glUniformMatrix4fv(uTex, 1, false, texMatrix, 0);
        quad.position(0);
        GLES20.glVertexAttribPointer(aPos, 2, GLES20.GL_FLOAT, false, 16, quad);
        GLES20.glEnableVertexAttribArray(aPos);
        quad.position(2);
        GLES20.glVertexAttribPointer(aTex, 2, GLES20.GL_FLOAT, false, 16, quad);
        GLES20.glEnableVertexAttribArray(aTex);
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);
        EGL14.eglSwapBuffers(display, out);
    }

    private void tearDown() {
        running = false;
        Choreographer.getInstance().removeFrameCallback(tick);
        if (input != null) { input.release(); input = null; }
        if (camTexture != null) { camTexture.release(); camTexture = null; }
        if (display != EGL14.EGL_NO_DISPLAY) {
            EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT);
            if (out != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(display, out);
            if (context != EGL14.EGL_NO_CONTEXT) EGL14.eglDestroyContext(display, context);
            EGL14.eglTerminate(display);
        }
        out = EGL14.EGL_NO_SURFACE;
        context = EGL14.EGL_NO_CONTEXT;
        display = EGL14.EGL_NO_DISPLAY;
    }

    private static int compile(int type, String src) {
        int s = GLES20.glCreateShader(type);
        GLES20.glShaderSource(s, src);
        GLES20.glCompileShader(s);
        int[] ok = new int[1];
        GLES20.glGetShaderiv(s, GLES20.GL_COMPILE_STATUS, ok, 0);
        if (ok[0] == 0) throw new IllegalStateException("shader: " + GLES20.glGetShaderInfoLog(s));
        return s;
    }

    private static int link(int vs, int fs) {
        int p = GLES20.glCreateProgram();
        GLES20.glAttachShader(p, vs);
        GLES20.glAttachShader(p, fs);
        GLES20.glLinkProgram(p);
        int[] ok = new int[1];
        GLES20.glGetProgramiv(p, GLES20.GL_LINK_STATUS, ok, 0);
        if (ok[0] == 0) throw new IllegalStateException("link: " + GLES20.glGetProgramInfoLog(p));
        return p;
    }
}
