package com.airoid.renderer

import android.graphics.SurfaceTexture
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLSurface
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.opengl.Matrix
import android.util.Log
import android.view.Surface
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

// decodes into an app-owned SurfaceTexture that outlives display surface
// gl thread blits it to any surface attached, so fullscreen toggles re-point display without restarting codec
class VideoPipeline {

    private val lock = Object()
    private var thread: Thread? = null
    @Volatile private var running = false

    private var eglDisplay: EGLDisplay = EGL14.EGL_NO_DISPLAY
    private var eglContext: EGLContext = EGL14.EGL_NO_CONTEXT
    private var eglConfig: EGLConfig? = null
    private var pbuffer: EGLSurface = EGL14.EGL_NO_SURFACE
    private var window: EGLSurface = EGL14.EGL_NO_SURFACE
    private var winW = 0
    private var winH = 0

    private var oesTex = 0
    private var program = 0
    private var aPos = 0
    private var aTex = 0
    private var uTexMatrix = 0
    private var uScale = 0
    private val texMatrix = FloatArray(16)
    private var hasFrame = false

    private var surfaceTexture: SurfaceTexture? = null
    var inputSurface: Surface? = null; private set

    @Volatile private var frameAvailable = false
    private var pendingDisplay: Surface? = null
    private var displayDirty = false
    @Volatile private var videoW = 0
    @Volatile private var videoH = 0

    /** 첫 번째 실제 콘텐츠(비검정) 프레임이 화면에 표시되었을 때 한 번 호출된다.
     * macOS는 미러링 모드 선택 전 검정 플레이스홀더를 보낼 수 있어,
     * 검정 프레임에서는 알리지 않고 실제 화면 콘텐츠가 나올 때 알린다. */
    @Volatile var onFirstFramePresented: (() -> Unit)? = null
    @Volatile private var contentNotified = false

    fun start() = synchronized(lock) {
        if (running) return@synchronized
        running = true
        contentNotified = false
        thread = Thread({ _loop() }, "VideoPipeline").also { it.start() }
        while (inputSurface == null && running) lock.wait()
    }

    fun setDisplaySurface(surface: Surface?) = synchronized(lock) {
        if (surface == null) {
            // 세션 종료(표시 해제): 첫 콘텐츠 게이트를 재무장해 다음 세션에서 다시 열리게 한다.
            contentNotified = false
        }
        pendingDisplay = surface
        displayDirty = true
        lock.notifyAll()
    }

    fun setVideoSize(w: Int, h: Int) {
        videoW = w
        videoH = h
        surfaceTexture?.setDefaultBufferSize(w, h)
    }

    fun release() {
        synchronized(lock) {
            if (!running) return
            running = false
            lock.notifyAll()
        }
        thread?.join()
        thread = null
    }

    private fun _loop() {
        try {
            _initEgl()
            _initGl()
        } catch (e: Exception) {
            Log.e(TAG, "GL init failed", e)
            synchronized(lock) { running = false; lock.notifyAll() }
            return
        }
        Matrix.setIdentityM(texMatrix, 0)
        synchronized(lock) {
            inputSurface = Surface(surfaceTexture)
            lock.notifyAll()
        }
        while (true) {
            var newDisplay: Surface? = null
            var displayChanged = false
            var doFrame = false
            synchronized(lock) {
                while (running && !frameAvailable && !displayDirty) lock.wait()
                if (running && displayDirty) {
                    newDisplay = pendingDisplay
                    displayChanged = true
                    displayDirty = false
                }
                if (running && frameAvailable) {
                    frameAvailable = false
                    doFrame = true
                }
            }
            if (!running) break
            if (displayChanged) _bindDisplay(newDisplay)
            if (doFrame) _consumeAndDraw()
        }
        _releaseGl()
    }

    private fun _bindDisplay(surface: Surface?) {
        if (window != EGL14.EGL_NO_SURFACE) {
            EGL14.eglMakeCurrent(eglDisplay, pbuffer, pbuffer, eglContext)
            EGL14.eglDestroySurface(eglDisplay, window)
            window = EGL14.EGL_NO_SURFACE
        }
        if (surface == null || !surface.isValid) return
        window = EGL14.eglCreateWindowSurface(eglDisplay, eglConfig, surface, intArrayOf(EGL14.EGL_NONE), 0)
        if (window == EGL14.EGL_NO_SURFACE) {
            Log.w(TAG, "eglCreateWindowSurface failed: ${EGL14.eglGetError()}")
            return
        }
        EGL14.eglMakeCurrent(eglDisplay, window, window, eglContext)
        winW = _query(EGL14.EGL_WIDTH)
        winH = _query(EGL14.EGL_HEIGHT)
        // an idle source sends no new frames, so repaint last one or new surface stays black
        if (hasFrame) _render()
    }

    private fun _consumeAndDraw() {
        val st = surfaceTexture ?: return
        if (window == EGL14.EGL_NO_SURFACE) {
            // no display: keep consuming so decoder doesn't stall
            EGL14.eglMakeCurrent(eglDisplay, pbuffer, pbuffer, eglContext)
            st.updateTexImage()
            hasFrame = true
            return
        }
        EGL14.eglMakeCurrent(eglDisplay, window, window, eglContext)
        st.updateTexImage()
        st.getTransformMatrix(texMatrix)
        hasFrame = true
        _render()
    }

    private fun _render() {
        GLES20.glViewport(0, 0, winW, winH)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
        GLES20.glUseProgram(program)
        // 소스 비율 보존(fit): 창 비율과 다르면 레터박스 — 회전/리사이즈에도 찌그러지지 않음
        val srcAspect = if (videoH > 0) videoW.toFloat() / videoH else 1f
        val winAspect = if (winH > 0) winW.toFloat() / winH else 1f
        val sx = if (srcAspect > winAspect) 1f else srcAspect / winAspect
        val sy = if (srcAspect > winAspect) winAspect / srcAspect else 1f
        GLES20.glUniform2f(uScale, sx, sy)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, oesTex)
        GLES20.glUniformMatrix4fv(uTexMatrix, 1, false, texMatrix, 0)
        GLES20.glEnableVertexAttribArray(aPos)
        GLES20.glVertexAttribPointer(aPos, 2, GLES20.GL_FLOAT, false, 0, POS)
        GLES20.glEnableVertexAttribArray(aTex)
        GLES20.glVertexAttribPointer(aTex, 2, GLES20.GL_FLOAT, false, 0, TEX)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        GLES20.glDisableVertexAttribArray(aPos)
        GLES20.glDisableVertexAttribArray(aTex)
        // 첫 콘텐츠 게이트: swap 전에 중앙 영역을 읽어 실제(비검정) 콘텐츠인지 판별
        if (onFirstFramePresented != null && !contentNotified && _sampleHasContent()) {
            contentNotified = true
            onFirstFramePresented?.invoke()
        }
        EGL14.eglSwapBuffers(eglDisplay, window)
    }

    /** 화면 중앙 64x64 픽셀의 최대 채널값으로 실제 콘텐츠(비검정)인지 판별. */
    private fun _sampleHasContent(): Boolean {
        if (window == EGL14.EGL_NO_SURFACE || winW <= 0 || winH <= 0) return false
        val size = 64
        val x = (winW - size) / 2
        val y = (winH - size) / 2
        val buf = ByteBuffer.allocateDirect(size * size * 4)
        GLES20.glReadPixels(x, y, size, size, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, buf)
        buf.rewind()
        var maxChannel = 0
        repeat(size * size) {
            val r = buf.get().toInt() and 0xFF
            val g = buf.get().toInt() and 0xFF
            val b = buf.get().toInt() and 0xFF
            buf.get() // alpha
            val m = maxOf(r, g, b)
            if (m > maxChannel) maxChannel = m
            if (maxChannel > 24) return true
        }
        return false
    }

    private fun _query(what: Int): Int {
        val v = IntArray(1)
        EGL14.eglQuerySurface(eglDisplay, window, what, v, 0)
        return v[0]
    }

    private fun _initEgl() {
        eglDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        EGL14.eglInitialize(eglDisplay, IntArray(2), 0, IntArray(2), 1)
        val attribs = intArrayOf(
            EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
            EGL14.EGL_SURFACE_TYPE, EGL14.EGL_WINDOW_BIT or EGL14.EGL_PBUFFER_BIT,
            EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8, EGL14.EGL_BLUE_SIZE, 8,
            EGL14.EGL_NONE
        )
        val configs = arrayOfNulls<EGLConfig>(1)
        EGL14.eglChooseConfig(eglDisplay, attribs, 0, configs, 0, 1, IntArray(1), 0)
        eglConfig = configs[0]
        eglContext = EGL14.eglCreateContext(
            eglDisplay, eglConfig, EGL14.EGL_NO_CONTEXT,
            intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE), 0
        )
        pbuffer = EGL14.eglCreatePbufferSurface(
            eglDisplay, eglConfig, intArrayOf(EGL14.EGL_WIDTH, 1, EGL14.EGL_HEIGHT, 1, EGL14.EGL_NONE), 0
        )
        EGL14.eglMakeCurrent(eglDisplay, pbuffer, pbuffer, eglContext)
    }

    private fun _initGl() {
        program = _buildProgram()
        aPos = GLES20.glGetAttribLocation(program, "aPos")
        aTex = GLES20.glGetAttribLocation(program, "aTex")
        uTexMatrix = GLES20.glGetUniformLocation(program, "uTexMatrix")
        uScale = GLES20.glGetUniformLocation(program, "uScale")
        val tex = IntArray(1)
        GLES20.glGenTextures(1, tex, 0)
        oesTex = tex[0]
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, oesTex)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        surfaceTexture = SurfaceTexture(oesTex).also {
            if (videoW > 0 && videoH > 0) it.setDefaultBufferSize(videoW, videoH)
            it.setOnFrameAvailableListener {
                synchronized(lock) { frameAvailable = true; lock.notifyAll() }
            }
        }
    }

    private fun _buildProgram(): Int {
        val vs = _shader(GLES20.GL_VERTEX_SHADER, VERT)
        val fs = _shader(GLES20.GL_FRAGMENT_SHADER, FRAG)
        return GLES20.glCreateProgram().also {
            GLES20.glAttachShader(it, vs)
            GLES20.glAttachShader(it, fs)
            GLES20.glLinkProgram(it)
            GLES20.glDeleteShader(vs)
            GLES20.glDeleteShader(fs)
        }
    }

    private fun _shader(type: Int, src: String): Int {
        return GLES20.glCreateShader(type).also {
            GLES20.glShaderSource(it, src)
            GLES20.glCompileShader(it)
            val ok = IntArray(1)
            GLES20.glGetShaderiv(it, GLES20.GL_COMPILE_STATUS, ok, 0)
            if (ok[0] == 0) Log.e(TAG, "shader compile failed: ${GLES20.glGetShaderInfoLog(it)}")
        }
    }

    private fun _releaseGl() {
        surfaceTexture?.release()
        surfaceTexture = null
        inputSurface?.release()
        inputSurface = null
        if (eglDisplay != EGL14.EGL_NO_DISPLAY) {
            EGL14.eglMakeCurrent(eglDisplay, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
            if (window != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(eglDisplay, window)
            if (pbuffer != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(eglDisplay, pbuffer)
            EGL14.eglDestroyContext(eglDisplay, eglContext)
            EGL14.eglTerminate(eglDisplay)
        }
        eglDisplay = EGL14.EGL_NO_DISPLAY
        window = EGL14.EGL_NO_SURFACE
        pbuffer = EGL14.EGL_NO_SURFACE
    }

    companion object {
        private const val TAG = "VideoPipeline"

        private val POS = _fb(floatArrayOf(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f))
        private val TEX = _fb(floatArrayOf(0f, 0f, 1f, 0f, 0f, 1f, 1f, 1f))

        private fun _fb(a: FloatArray): FloatBuffer =
            ByteBuffer.allocateDirect(a.size * 4).order(ByteOrder.nativeOrder())
                .asFloatBuffer().apply { put(a); position(0) }

        private const val VERT =
            "attribute vec2 aPos;\n" +
            "attribute vec2 aTex;\n" +
            "uniform mat4 uTexMatrix;\n" +
            "uniform vec2 uScale;\n" +
            "varying vec2 vTex;\n" +
            "void main() {\n" +
            "  gl_Position = vec4(aPos * uScale, 0.0, 1.0);\n" +
            "  vTex = (uTexMatrix * vec4(aTex, 0.0, 1.0)).xy;\n" +
            "}\n"

        private const val FRAG =
            "#extension GL_OES_EGL_image_external : require\n" +
            "precision mediump float;\n" +
            "varying vec2 vTex;\n" +
            "uniform samplerExternalOES sTex;\n" +
            "void main() {\n" +
            "  gl_FragColor = texture2D(sTex, vTex);\n" +
            "}\n"
    }
}
