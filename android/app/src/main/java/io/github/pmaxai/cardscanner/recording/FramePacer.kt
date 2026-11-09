package io.github.pmaxai.cardscanner.recording

import android.graphics.SurfaceTexture
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLExt
import android.opengl.EGLSurface
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.view.Surface
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * A constant frame rate for the screen video. The virtual display draws into [surface] as
 * often as the screen refreshes (60, 90, 120 Hz); this draws the newest picture into the
 * encoder at exactly [fps] frames a second, frame k stamped first + k / fps (System.nanoTime's
 * clock): frames above the rate are dropped, a still screen repeats its last frame.
 */
class FramePacer(private val target: Surface, private val width: Int, private val height: Int, private val fps: Int = 30,
                 private val onError: (Throwable) -> Unit = {}) {
    private val thread = HandlerThread("rec-gl").apply { start() }
    private val handler = Handler(thread.looper)
    private val period = 1_000_000_000L / fps

    private var display: EGLDisplay = EGL14.EGL_NO_DISPLAY
    private var context: EGLContext = EGL14.EGL_NO_CONTEXT
    private var eglSurface: EGLSurface = EGL14.EGL_NO_SURFACE
    private var program = 0
    private var tex = 0
    private var uMatrix = 0
    private var aPos = 0
    private var aTex = 0
    private lateinit var texture: SurfaceTexture
    private val matrix = FloatArray(16)
    private val quad: FloatBuffer = floats(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f)
    private val uv: FloatBuffer = floats(0f, 0f, 1f, 0f, 0f, 1f, 1f, 1f)

    /** where the virtual display draws */
    lateinit var surface: Surface
        private set
    @Volatile private var running = false
    private var haveFrame = false
    private var first = 0L
    private var frame = 0L
    /** frames drawn into the encoder (for tests and the log) */
    @Volatile var framesOut = 0L
        private set

    private val tick = object : Runnable {
        override fun run() {
            if (!running) return
            try {
                val now = System.nanoTime()
                // far behind (the phone stalled): skip ahead instead of a burst of old frames
                val due = first + frame * period
                if (now - due > 4 * period) frame += (now - due) / period
                draw(first + frame * period)
                frame++
                val next = first + frame * period
                handler.postDelayed(this, ((next - System.nanoTime()) / 1_000_000).coerceAtLeast(0))
            } catch (t: Throwable) {
                running = false
                Log.e(TAG, "frame pacer", t)
                onError(t)
            }
        }
    }

    /** Sets up GL on its own thread; [surface] is ready when this returns. */
    fun start(): FramePacer {
        try {
            onGl {
                setupEgl()
                setupGl()
                texture = SurfaceTexture(tex).apply {
                    setDefaultBufferSize(width, height)
                    setOnFrameAvailableListener({ newFrame() }, handler)
                }
                surface = Surface(texture)
                running = true
            }
        } catch (t: Throwable) {
            runCatching { onGl { if (display != EGL14.EGL_NO_DISPLAY) EGL14.eglTerminate(display) } }
            thread.quitSafely()
            throw t
        }
        return this
    }

    /** A new picture from the screen: it is used from the next tick on; the first starts the clock. */
    private fun newFrame() {
        if (!running) return
        runCatching { texture.updateTexImage() }.onFailure { Log.w(TAG, "updateTexImage", it); return }
        if (!haveFrame) {
            haveFrame = true
            first = System.nanoTime()
            frame = 0
            handler.post(tick)
        }
    }

    private fun draw(ptsNanos: Long) {
        GLES20.glViewport(0, 0, width, height)
        GLES20.glClearColor(0f, 0f, 0f, 1f)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
        GLES20.glUseProgram(program)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, tex)
        texture.getTransformMatrix(matrix)
        GLES20.glUniformMatrix4fv(uMatrix, 1, false, matrix, 0)
        GLES20.glEnableVertexAttribArray(aPos)
        GLES20.glVertexAttribPointer(aPos, 2, GLES20.GL_FLOAT, false, 0, quad)
        GLES20.glEnableVertexAttribArray(aTex)
        GLES20.glVertexAttribPointer(aTex, 2, GLES20.GL_FLOAT, false, 0, uv)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        EGLExt.eglPresentationTimeANDROID(display, eglSurface, ptsNanos)
        check(EGL14.eglSwapBuffers(display, eglSurface)) { "eglSwapBuffers: 0x" + Integer.toHexString(EGL14.eglGetError()) }
        framesOut++
    }

    /** No more frames; everything released. The encoder's input can be ended after this. */
    fun stop() {
        runCatching {
            onGl {
                running = false
                handler.removeCallbacks(tick)
                runCatching { surface.release() }
                runCatching { texture.release() }
                if (program != 0) GLES20.glDeleteProgram(program)
                if (tex != 0) GLES20.glDeleteTextures(1, intArrayOf(tex), 0)
                if (display != EGL14.EGL_NO_DISPLAY) {
                    EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
                    EGL14.eglDestroySurface(display, eglSurface)
                    EGL14.eglDestroyContext(display, context)
                    EGL14.eglReleaseThread()
                    EGL14.eglTerminate(display)
                }
                display = EGL14.EGL_NO_DISPLAY
            }
        }.onFailure { Log.w(TAG, "frame pacer stop", it) }
        thread.quitSafely()
    }

    private fun setupEgl() {
        display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        check(display != EGL14.EGL_NO_DISPLAY) { "no EGL display" }
        val v = IntArray(2)
        check(EGL14.eglInitialize(display, v, 0, v, 1)) { "eglInitialize" }
        val attrs = intArrayOf(
            EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8, EGL14.EGL_BLUE_SIZE, 8, EGL14.EGL_ALPHA_SIZE, 8,
            EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT, EGL_RECORDABLE_ANDROID, 1, EGL14.EGL_NONE)
        val configs = arrayOfNulls<EGLConfig>(1)
        val n = IntArray(1)
        check(EGL14.eglChooseConfig(display, attrs, 0, configs, 0, 1, n, 0) && n[0] > 0) { "no recordable EGL config" }
        context = EGL14.eglCreateContext(display, configs[0], EGL14.EGL_NO_CONTEXT,
            intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE), 0)
        check(context != EGL14.EGL_NO_CONTEXT) { "eglCreateContext: 0x" + Integer.toHexString(EGL14.eglGetError()) }
        eglSurface = EGL14.eglCreateWindowSurface(display, configs[0], target, intArrayOf(EGL14.EGL_NONE), 0)
        check(eglSurface != EGL14.EGL_NO_SURFACE) { "eglCreateWindowSurface: 0x" + Integer.toHexString(EGL14.eglGetError()) }
        check(EGL14.eglMakeCurrent(display, eglSurface, eglSurface, context)) { "eglMakeCurrent" }
    }

    private fun setupGl() {
        program = GLES20.glCreateProgram()
        GLES20.glAttachShader(program, shader(GLES20.GL_VERTEX_SHADER, VERTEX))
        GLES20.glAttachShader(program, shader(GLES20.GL_FRAGMENT_SHADER, FRAGMENT))
        GLES20.glLinkProgram(program)
        val ok = IntArray(1)
        GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, ok, 0)
        check(ok[0] == GLES20.GL_TRUE) { "link: " + GLES20.glGetProgramInfoLog(program) }
        aPos = GLES20.glGetAttribLocation(program, "aPos")
        aTex = GLES20.glGetAttribLocation(program, "aTex")
        uMatrix = GLES20.glGetUniformLocation(program, "uMatrix")
        val t = IntArray(1)
        GLES20.glGenTextures(1, t, 0)
        tex = t[0]
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, tex)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
    }

    private fun shader(type: Int, src: String): Int {
        val s = GLES20.glCreateShader(type)
        GLES20.glShaderSource(s, src)
        GLES20.glCompileShader(s)
        val ok = IntArray(1)
        GLES20.glGetShaderiv(s, GLES20.GL_COMPILE_STATUS, ok, 0)
        check(ok[0] == GLES20.GL_TRUE) { "shader: " + GLES20.glGetShaderInfoLog(s) }
        return s
    }

    /** Runs [f] on the GL thread and waits for it; its exception is thrown here. */
    private fun onGl(f: () -> Unit) {
        val done = CountDownLatch(1)
        var error: Throwable? = null
        handler.post { try { f() } catch (t: Throwable) { error = t } finally { done.countDown() } }
        check(done.await(5, TimeUnit.SECONDS)) { "GL thread didn't answer" }
        error?.let { throw it }
    }

    companion object {
        private const val TAG = "Recording"
        private const val EGL_RECORDABLE_ANDROID = 0x3142
        private const val VERTEX = """
            attribute vec4 aPos;
            attribute vec4 aTex;
            uniform mat4 uMatrix;
            varying vec2 vTex;
            void main() { gl_Position = aPos; vTex = (uMatrix * aTex).xy; }
        """
        private const val FRAGMENT = """
            #extension GL_OES_EGL_image_external : require
            precision mediump float;
            varying vec2 vTex;
            uniform samplerExternalOES sTex;
            void main() { gl_FragColor = texture2D(sTex, vTex); }
        """

        private fun floats(vararg v: Float): FloatBuffer =
            ByteBuffer.allocateDirect(v.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().apply { put(v); position(0) }
    }
}
