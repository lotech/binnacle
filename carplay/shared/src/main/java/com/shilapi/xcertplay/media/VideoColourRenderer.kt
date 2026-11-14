package com.shilapi.xcertplay.media

import android.graphics.SurfaceTexture
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLExt
import android.opengl.EGLSurface
import android.opengl.GLES11Ext
import android.opengl.GLES30
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.view.Surface
import java.io.Closeable
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Draws decoded video onto the output Surface with this app's own YUV to RGB conversion.
 *
 * Some GPUs ignore the colour description of a decoded buffer and convert every YUV picture as
 * BT.601 TV range. On the Unisoc UMS512 dash (Mali-G52, Nov 2026) the hardware decoder never
 * marks its buffers, so the iPhone's full-range BT.709 CarPlay picture was stretched as if it
 * were TV range: every grey above about 95 % turned pure white and pastels clipped.
 *
 * GL_EXT_YUV_target lets a shader read the raw Y, Cb and Cr samples of a decoded buffer, so the
 * conversion here follows the stream's own range and matrix ([setColour]). The decoder renders
 * into [inputSurface]; each picture is converted 1:1 into an RGB texture, then scaled onto
 * whatever Surface [setOutput] gave. With no output, pictures are consumed and dropped.
 *
 * [create] returns null when the GPU lacks the extension or any GL step fails; the caller then
 * renders straight to its Surface as before.
 */
internal class VideoColourRenderer private constructor(
    private val thread: HandlerThread,
    private val handler: Handler,
) : Closeable {
    private var display: EGLDisplay = EGL14.EGL_NO_DISPLAY
    private var context: EGLContext = EGL14.EGL_NO_CONTEXT
    private var config: EGLConfig? = null
    private var idleSurface: EGLSurface = EGL14.EGL_NO_SURFACE
    private var windowSurface: EGLSurface = EGL14.EGL_NO_SURFACE
    private var windowTarget: Surface? = null
    private var yuvProgram = 0
    private var rgbProgram = 0
    private var yuvTexture = 0
    private var rgbTexture = 0
    private var framebuffer = 0
    private var frameWidth = 0
    private var frameHeight = 0
    private var hasFrame = false
    private var drawFailureLogged = false
    private val texMatrix = FloatArray(16)
    private var colourMatrix = FloatArray(9)
    private var colourOffset = FloatArray(3)
    private lateinit var surfaceTexture: SurfaceTexture

    /** Where the decoder renders. Valid until [close]. */
    lateinit var inputSurface: Surface
        private set

    /** The stream's size and colour description, from its SPS. Applies to the next picture drawn. */
    fun setColour(width: Int, height: Int, standard: Int, range: Int) {
        val (matrix, offset) = yuvToRgb(standard, range, height)
        handler.post {
            colourMatrix = matrix
            colourOffset = offset
            if (width > 0 && height > 0 && (width != frameWidth || height != frameHeight)) {
                frameWidth = width
                frameHeight = height
                surfaceTexture.setDefaultBufferSize(width, height)
                GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, rgbTexture)
                GLES30.glTexImage2D(
                    GLES30.GL_TEXTURE_2D, 0, GLES30.GL_RGBA8, width, height, 0,
                    GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, null,
                )
                hasFrame = false
            }
            Log.i(TAG, "video colour conversion ${width}x$height standard=$standard range=$range")
        }
    }

    /**
     * Draws to [surface] from now on, or to nothing when null. Returns once the old Surface is let
     * go, so a SurfaceView being destroyed is never drawn to. With [redrawLast], the last picture
     * is drawn at once, since a static screen sends no new one. False when [surface] can't be used.
     */
    fun setOutput(surface: Surface?, redrawLast: Boolean): Boolean {
        if (Thread.currentThread() === thread) return attach(surface, redrawLast)
        var ok = false
        val done = CountDownLatch(1)
        if (!handler.post { ok = attach(surface, redrawLast); done.countDown() }) return surface == null
        if (!done.await(CALL_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
            Log.w(TAG, "video colour renderer did not switch output within ${CALL_TIMEOUT_MS}ms")
            return false
        }
        return ok
    }

    /**
     * Safe on an interrupted thread (the video worker is interrupted at shutdown): the release
     * stays queued and the GL thread still quits once it has run.
     */
    override fun close() {
        val done = CountDownLatch(1)
        try {
            if (handler.post { release(); done.countDown() }) done.await(CALL_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        } finally {
            thread.quitSafely()
        }
    }

    private fun attach(surface: Surface?, redrawLast: Boolean): Boolean {
        if (surface === windowTarget && windowSurface != EGL14.EGL_NO_SURFACE) {
            if (redrawLast) draw()
            return true
        }
        detachWindow()
        if (surface == null) return true
        val created = EGL14.eglCreateWindowSurface(display, config, surface, intArrayOf(EGL14.EGL_NONE), 0)
        if (created == null || created == EGL14.EGL_NO_SURFACE) {
            Log.w(TAG, "video colour renderer could not draw to the surface: egl=0x${Integer.toHexString(EGL14.eglGetError())}")
            return false
        }
        windowSurface = created
        windowTarget = surface
        if (!EGL14.eglMakeCurrent(display, windowSurface, windowSurface, context)) {
            Log.w(TAG, "video colour renderer could not use the surface: egl=0x${Integer.toHexString(EGL14.eglGetError())}")
            detachWindow()
            return false
        }
        // Never wait for the display: a late picture should not hold up the next one.
        EGL14.eglSwapInterval(display, 0)
        if (redrawLast) draw()
        return true
    }

    private fun detachWindow() {
        if (windowSurface == EGL14.EGL_NO_SURFACE) return
        EGL14.eglMakeCurrent(display, idleSurface, idleSurface, context)
        EGL14.eglDestroySurface(display, windowSurface)
        windowSurface = EGL14.EGL_NO_SURFACE
        windowTarget = null
    }

    private fun onFrame() {
        try {
            surfaceTexture.updateTexImage()
            surfaceTexture.getTransformMatrix(texMatrix)
            hasFrame = true
            draw()
        } catch (error: RuntimeException) {
            if (!drawFailureLogged) {
                drawFailureLogged = true
                Log.w(TAG, "video colour renderer failed to take a picture", error)
            }
        }
    }

    private fun draw() {
        if (!hasFrame || windowSurface == EGL14.EGL_NO_SURFACE || frameWidth <= 0) return
        // Pass 1: raw YUV to RGB at the picture's own size, one texel per pixel (no filtering in YUV).
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, framebuffer)
        GLES30.glViewport(0, 0, frameWidth, frameHeight)
        GLES30.glUseProgram(yuvProgram)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, yuvTexture)
        GLES30.glUniformMatrix4fv(GLES30.glGetUniformLocation(yuvProgram, "uTexMatrix"), 1, false, texMatrix, 0)
        GLES30.glUniformMatrix3fv(GLES30.glGetUniformLocation(yuvProgram, "uYuvToRgb"), 1, false, colourMatrix, 0)
        GLES30.glUniform3fv(GLES30.glGetUniformLocation(yuvProgram, "uOffset"), 1, colourOffset, 0)
        drawQuad(yuvProgram)
        // Pass 2: the RGB picture scaled onto the window with ordinary filtering.
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
        val size = IntArray(2)
        EGL14.eglQuerySurface(display, windowSurface, EGL14.EGL_WIDTH, size, 0)
        EGL14.eglQuerySurface(display, windowSurface, EGL14.EGL_HEIGHT, size, 1)
        GLES30.glViewport(0, 0, size[0], size[1])
        GLES30.glUseProgram(rgbProgram)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, rgbTexture)
        GLES30.glUniformMatrix4fv(GLES30.glGetUniformLocation(rgbProgram, "uTexMatrix"), 1, false, IDENTITY, 0)
        drawQuad(rgbProgram)
        if (!EGL14.eglSwapBuffers(display, windowSurface) && !drawFailureLogged) {
            drawFailureLogged = true
            Log.w(TAG, "video colour renderer swap failed: egl=0x${Integer.toHexString(EGL14.eglGetError())}")
        }
    }

    private fun drawQuad(program: Int) {
        val position = GLES30.glGetAttribLocation(program, "aPos")
        GLES30.glEnableVertexAttribArray(position)
        GLES30.glVertexAttribPointer(position, 2, GLES30.GL_FLOAT, false, 0, QUAD)
        GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4)
        GLES30.glDisableVertexAttribArray(position)
    }

    /** Runs on the GL thread. False leaves partial state for [release]. */
    private fun init(): Boolean {
        display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        if (display == EGL14.EGL_NO_DISPLAY || !EGL14.eglInitialize(display, IntArray(2), 0, IntArray(2), 1)) return false
        val configs = arrayOfNulls<EGLConfig>(1)
        val count = IntArray(1)
        val attributes = intArrayOf(
            EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8, EGL14.EGL_BLUE_SIZE, 8, EGL14.EGL_ALPHA_SIZE, 8,
            EGL14.EGL_RENDERABLE_TYPE, EGLExt.EGL_OPENGL_ES3_BIT_KHR,
            EGL14.EGL_SURFACE_TYPE, EGL14.EGL_WINDOW_BIT or EGL14.EGL_PBUFFER_BIT,
            EGL14.EGL_NONE,
        )
        if (!EGL14.eglChooseConfig(display, attributes, 0, configs, 0, 1, count, 0) || count[0] < 1) return false
        config = configs[0]
        context = EGL14.eglCreateContext(
            display, config, EGL14.EGL_NO_CONTEXT, intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 3, EGL14.EGL_NONE), 0,
        )
        if (context == null || context == EGL14.EGL_NO_CONTEXT) return false
        idleSurface = EGL14.eglCreatePbufferSurface(
            display, config, intArrayOf(EGL14.EGL_WIDTH, 1, EGL14.EGL_HEIGHT, 1, EGL14.EGL_NONE), 0,
        )
        if (idleSurface == null || idleSurface == EGL14.EGL_NO_SURFACE) return false
        if (!EGL14.eglMakeCurrent(display, idleSurface, idleSurface, context)) return false
        val extensions = GLES30.glGetString(GLES30.GL_EXTENSIONS).orEmpty()
        if (!extensions.split(' ').contains("GL_EXT_YUV_target")) {
            Log.i(TAG, "video colour conversion unavailable: no GL_EXT_YUV_target")
            return false
        }
        yuvProgram = program(VERTEX_SHADER, YUV_FRAGMENT_SHADER)
        rgbProgram = program(VERTEX_SHADER, RGB_FRAGMENT_SHADER)
        if (yuvProgram == 0 || rgbProgram == 0) return false
        val textures = IntArray(2)
        GLES30.glGenTextures(2, textures, 0)
        yuvTexture = textures[0]
        rgbTexture = textures[1]
        GLES30.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, yuvTexture)
        texParameters(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES30.GL_NEAREST)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, rgbTexture)
        texParameters(GLES30.GL_TEXTURE_2D, GLES30.GL_LINEAR)
        val framebuffers = IntArray(1)
        GLES30.glGenFramebuffers(1, framebuffers, 0)
        framebuffer = framebuffers[0]
        // The framebuffer is completed by the first setColour; until then nothing is drawn.
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, rgbTexture)
        GLES30.glTexImage2D(
            GLES30.GL_TEXTURE_2D, 0, GLES30.GL_RGBA8, 16, 16, 0, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, null,
        )
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, framebuffer)
        GLES30.glFramebufferTexture2D(
            GLES30.GL_FRAMEBUFFER, GLES30.GL_COLOR_ATTACHMENT0, GLES30.GL_TEXTURE_2D, rgbTexture, 0,
        )
        val status = GLES30.glCheckFramebufferStatus(GLES30.GL_FRAMEBUFFER)
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
        if (status != GLES30.GL_FRAMEBUFFER_COMPLETE) {
            Log.w(TAG, "video colour renderer framebuffer incomplete: 0x${Integer.toHexString(status)}")
            return false
        }
        if (GLES30.glGetError() != GLES30.GL_NO_ERROR) return false
        surfaceTexture = SurfaceTexture(yuvTexture).apply {
            setOnFrameAvailableListener({ onFrame() }, handler)
        }
        inputSurface = Surface(surfaceTexture)
        return true
    }

    private fun texParameters(target: Int, filter: Int) {
        GLES30.glTexParameteri(target, GLES30.GL_TEXTURE_MIN_FILTER, filter)
        GLES30.glTexParameteri(target, GLES30.GL_TEXTURE_MAG_FILTER, filter)
        GLES30.glTexParameteri(target, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexParameteri(target, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)
    }

    private fun program(vertexSource: String, fragmentSource: String): Int {
        val vertex = shader(GLES30.GL_VERTEX_SHADER, vertexSource)
        val fragment = shader(GLES30.GL_FRAGMENT_SHADER, fragmentSource)
        if (vertex == 0 || fragment == 0) return 0
        val program = GLES30.glCreateProgram()
        GLES30.glAttachShader(program, vertex)
        GLES30.glAttachShader(program, fragment)
        GLES30.glLinkProgram(program)
        GLES30.glDeleteShader(vertex)
        GLES30.glDeleteShader(fragment)
        val linked = IntArray(1)
        GLES30.glGetProgramiv(program, GLES30.GL_LINK_STATUS, linked, 0)
        if (linked[0] == 0) {
            Log.w(TAG, "video colour renderer link failed: ${GLES30.glGetProgramInfoLog(program)}")
            GLES30.glDeleteProgram(program)
            return 0
        }
        return program
    }

    private fun shader(type: Int, source: String): Int {
        val shader = GLES30.glCreateShader(type)
        GLES30.glShaderSource(shader, source)
        GLES30.glCompileShader(shader)
        val compiled = IntArray(1)
        GLES30.glGetShaderiv(shader, GLES30.GL_COMPILE_STATUS, compiled, 0)
        if (compiled[0] == 0) {
            Log.w(TAG, "video colour renderer shader failed: ${GLES30.glGetShaderInfoLog(shader)}")
            GLES30.glDeleteShader(shader)
            return 0
        }
        return shader
    }

    /** Runs on the GL thread; safe after a partial [init]. */
    private fun release() {
        if (::surfaceTexture.isInitialized) {
            surfaceTexture.setOnFrameAvailableListener(null)
            inputSurface.release()
            surfaceTexture.release()
        }
        if (display != EGL14.EGL_NO_DISPLAY) {
            detachWindow()
            if (context != EGL14.EGL_NO_CONTEXT) {
                if (idleSurface != EGL14.EGL_NO_SURFACE &&
                    EGL14.eglMakeCurrent(display, idleSurface, idleSurface, context)
                ) {
                    if (framebuffer != 0) GLES30.glDeleteFramebuffers(1, intArrayOf(framebuffer), 0)
                    if (yuvTexture != 0) GLES30.glDeleteTextures(2, intArrayOf(yuvTexture, rgbTexture), 0)
                    if (yuvProgram != 0) GLES30.glDeleteProgram(yuvProgram)
                    if (rgbProgram != 0) GLES30.glDeleteProgram(rgbProgram)
                }
                EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
                EGL14.eglDestroyContext(display, context)
            }
            if (idleSurface != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(display, idleSurface)
            EGL14.eglReleaseThread()
            EGL14.eglTerminate(display)
        }
        display = EGL14.EGL_NO_DISPLAY
        context = EGL14.EGL_NO_CONTEXT
        idleSurface = EGL14.EGL_NO_SURFACE
    }

    companion object {
        private const val TAG = "xcertplay-usb"
        private const val CALL_TIMEOUT_MS = 1_000L

        /** Set once the GPU has turned this down, so later sessions don't retry. */
        @Volatile private var unavailable = false

        /** A running renderer, or null to render straight to the Surface (see the class comment). */
        fun create(): VideoColourRenderer? {
            if (unavailable) return null
            val thread = HandlerThread("carplay-video-colour", android.os.Process.THREAD_PRIORITY_DISPLAY).apply { start() }
            val handler = Handler(thread.looper)
            val renderer = VideoColourRenderer(thread, handler)
            var ok = false
            val done = CountDownLatch(1)
            handler.post {
                ok = try { renderer.init() } catch (error: RuntimeException) {
                    Log.w(TAG, "video colour renderer init failed", error)
                    false
                }
                if (!ok) renderer.release()
                done.countDown()
            }
            val finished = try {
                done.await(CALL_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            } catch (interrupted: InterruptedException) {
                // The session closed mid-init: nothing owns the renderer yet, so release it here
                // (after the init, on the GL thread) and let the thread end, then pass it on.
                handler.post { if (ok) renderer.release() }
                thread.quitSafely()
                throw interrupted
            }
            if (!finished || !ok) {
                Log.i(TAG, "video colour conversion off: rendering straight to the surface")
                unavailable = true
                // A late init that still succeeds must not leave its EGL context behind (a failed
                // one has released already). Runs after the init on the same thread.
                handler.post { if (ok) renderer.release() }
                thread.quitSafely()
                return null
            }
            return renderer
        }

        // MediaFormat values (android.media.MediaFormat.COLOR_*), kept literal for JVM tests.
        internal const val STANDARD_BT709 = 1
        internal const val STANDARD_BT601_PAL = 2
        internal const val STANDARD_BT601_NTSC = 4
        internal const val STANDARD_BT2020 = 6
        internal const val RANGE_FULL = 1
        internal const val RANGE_LIMITED = 2

        /**
         * The column-major 3x3 matrix and the offset that turn raw (Y, Cb, Cr) samples in 0..1 into
         * RGB: rgb = matrix * (yuv - offset). Unspecified fields follow the usual defaults: TV range,
         * and BT.709 from 720 lines up, BT.601 below.
         */
        internal fun yuvToRgb(standard: Int, range: Int, height: Int): Pair<FloatArray, FloatArray> {
            val (kr, kb) = when (standard) {
                STANDARD_BT709 -> 0.2126 to 0.0722
                STANDARD_BT601_PAL, STANDARD_BT601_NTSC -> 0.299 to 0.114
                STANDARD_BT2020 -> 0.2627 to 0.0593
                else -> if (height >= 720) 0.2126 to 0.0722 else 0.299 to 0.114
            }
            val kg = 1 - kr - kb
            val full = range == RANGE_FULL
            val yScale = if (full) 1.0 else 255.0 / 219.0
            val cScale = if (full) 1.0 else 255.0 / 224.0
            val rCr = 2 * (1 - kr)
            val bCb = 2 * (1 - kb)
            val gCb = bCb * kb / kg
            val gCr = rCr * kr / kg
            val matrix = doubleArrayOf(
                yScale, yScale, yScale,                 // Y column
                0.0, -gCb * cScale, bCb * cScale,      // Cb column
                rCr * cScale, -gCr * cScale, 0.0,      // Cr column
            ).map { it.toFloat() }.toFloatArray()
            val offset = floatArrayOf(if (full) 0f else 16f / 255f, 128f / 255f, 128f / 255f)
            return matrix to offset
        }

        private val IDENTITY = floatArrayOf(1f, 0f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 0f, 1f)

        private val QUAD: FloatBuffer = ByteBuffer.allocateDirect(8 * 4).order(ByteOrder.nativeOrder())
            .asFloatBuffer().apply { put(floatArrayOf(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f)); position(0) }

        private const val VERTEX_SHADER = """#version 300 es
in vec2 aPos;
uniform mat4 uTexMatrix;
out vec2 vTex;
void main() {
    gl_Position = vec4(aPos, 0.0, 1.0);
    vTex = (uTexMatrix * vec4(aPos * 0.5 + 0.5, 0.0, 1.0)).xy;
}
"""

        private const val YUV_FRAGMENT_SHADER = """#version 300 es
#extension GL_EXT_YUV_target : require
precision highp float;
uniform __samplerExternal2DY2YEXT uYuv;
uniform mat3 uYuvToRgb;
uniform vec3 uOffset;
in vec2 vTex;
out vec4 outColor;
void main() {
    vec3 yuv = texture(uYuv, vTex).xyz;
    outColor = vec4(clamp(uYuvToRgb * (yuv - uOffset), 0.0, 1.0), 1.0);
}
"""

        private const val RGB_FRAGMENT_SHADER = """#version 300 es
precision mediump float;
uniform sampler2D uRgb;
in vec2 vTex;
out vec4 outColor;
void main() {
    outColor = vec4(texture(uRgb, vTex).rgb, 1.0);
}
"""
    }
}
