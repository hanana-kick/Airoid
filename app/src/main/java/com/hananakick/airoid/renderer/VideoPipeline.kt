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
    private var uWindow = 0
    private var uRadius = 0
    private var uFade = 0
    // 외곽선(링) 프로그램: 영상과 같은 스케일/트랜스폼으로 그려 싱크가 구조적으로 맞는다.
    private var ringProgram = 0
    private var ringAPos = 0
    private var ringUWindow = 0
    private var ringUHalf = 0
    private var ringURadius = 0
    private var ringUBorder = 0
    private var ringUColor = 0
    private val texMatrix = FloatArray(16)
    private var hasFrame = false

    private var surfaceTexture: SurfaceTexture? = null
    var inputSurface: Surface? = null; private set

    @Volatile private var frameAvailable = false
    private var pendingDisplay: Surface? = null
    private var displayDirty = false
    @Volatile private var videoW = 0
    @Volatile private var videoH = 0

    /** 전환 애니메이션용 표시 스케일: 박스 크기(0.48) → 화면 밖(1.1).
     * SurfaceView는 plain full-screen으로 유지한 채 GL 쿼드를 축소해 그린다 —
     * Compose 레이어 변환 없이 영상만 박스에 맞게 작아진다.
     * 영상 쿼드는 min(scale, 1.0)으로, 외곽선 링은 scale 그대로 (화면 밖으로 나간다).
     * 초기값은 1.1(링이 화면 밖) — 링 테두리는 박스 안쪽에 그려지므로 s=1.0에서
     * 가장자리 링이 남지 않게, scale은 항상 애니메이션이 소유한다. */
    @Volatile var displayScale = 1.1f; private set

    /** 컨테이너(영상) 전용 투명도(0..1). 외곽선(링)은 이 값을 받지 않는다 —
     * 부모(링)=크기, 자식(영상)=투명도 구조. 페이드는 전환 애니메이션(Compose)만
     * 올린다 — 게이트 전(페이드인 전) 프레임은 0으로 렌더되어 영상이 먼저
     * 노출되는 깜빡임이 없다. */
    @Volatile var displayFade = 0f; private set

    @Volatile private var scaleDirty = false

    /** 외곽선(링) 파라미터 — 기기 radius 내각 기준, 테마 색. */
    @Volatile var borderColorArgb = 0; private set
    @Volatile var borderWidthPx = 0f; private set
    @Volatile var borderRadiusPx = 0f; private set

    /** 외곽선 설정: colorArgb(테마 primary), radiusPx(내각 = 기기 radius), widthPx(테두리 두께). */
    fun setVideoBorder(colorArgb: Int, radiusPx: Float, widthPx: Float) = synchronized(lock) {
        borderColorArgb = colorArgb
        borderRadiusPx = radiusPx
        borderWidthPx = widthPx
        scaleDirty = true
        lock.notifyAll()
    }

    /** 전환 상태 변경 (스케일 + 컨테이너 페이드). 새 프레임이 없어도 마지막 프레임을
     * 다시 그리게 해 (정적 화면 저프레임에서도) 확대/축소가 끊기지 않게 한다. */
    fun setTransition(scale: Float, fade: Float) = synchronized(lock) {
        displayScale = scale.coerceIn(0f, 1.2f)
        displayFade = fade.coerceIn(0f, 1f)
        scaleDirty = true
        lock.notifyAll()
    }

    /** 새 세션(재연결) 시작 시 전환 게이트를 재무장한다. 이전 세션에서 열린 게이트가
     * 새 스트림의 firstFrameShown을 막지 않게 한다. 디스플레이 표면이 살아 있는
     * 빠른 재연결에서는 setDisplaySurface(null)이 호출되지 않아 이 재무장이 필요하다.
     * 페이드는 애니메이션이 소유하므로 여기서 건드리지 않는다 — 게이트 전 프레임은
     * fade=0(애니메이션 초기값)으로 렌더되어 페이드인 전 영상 노출(깜빡임)이 없고,
     * 콘텐츠 판별은 _render의 unfaded 샘플 패스가 담당한다. */
    fun resetGate() = synchronized(lock) {
        contentNotified = false
        contentFramesSeen = 0
    }

    /** 첫 번째 실제 콘텐츠(비검정) 프레임이 화면에 표시되었을 때 한 번 호출된다.
     * macOS는 미러링 모드 선택 전 검정 플레이스홀더를 보낼 수 있어,
     * 검정 프레임에서는 알리지 않고 실제 화면 콘텐츠가 나올 때 알린다. */
    @Volatile var onFirstFramePresented: (() -> Unit)? = null
    @Volatile private var contentNotified = false
    @Volatile private var firstRenderLogged = false
    @Volatile private var contentFramesSeen = 0

    fun start() = synchronized(lock) {
        if (running) return@synchronized
        running = true
        contentNotified = false
        firstRenderLogged = false
        contentFramesSeen = 0
        scaleDirty = false
        thread = Thread({ _loop() }, "VideoPipeline").also { it.start() }
        while (inputSurface == null && running) lock.wait()
    }

    fun setDisplaySurface(surface: Surface?) = synchronized(lock) {
        if (surface == null) {
            // 세션 종료(표시 해제): 첫 콘텐츠 게이트를 재무장하고, 이전 세션의 마지막
            // 프레임 상태도 버린다. 남아 있으면 재연결 직후 새 스트림이 오기 전에
            // 낡은 화면이 게이트를 열어 "애니메이션이 새 화면보다 먼저 시작"되는 것처럼 보인다.
            contentNotified = false
            contentFramesSeen = 0
            hasFrame = false
            scaleDirty = false
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
            var doRedraw = false
            synchronized(lock) {
                while (running && !frameAvailable && !displayDirty && !scaleDirty) lock.wait()
                if (running && displayDirty) {
                    newDisplay = pendingDisplay
                    displayChanged = true
                    displayDirty = false
                }
                if (running && frameAvailable) {
                    frameAvailable = false
                    doFrame = true
                }
                if (running && scaleDirty) {
                    scaleDirty = false
                    doRedraw = true
                }
            }
            if (!running) break
            if (displayChanged) _bindDisplay(newDisplay)
            if (doFrame) _consumeAndDraw()
            else if (doRedraw) _redraw()
        }
        _releaseGl()
    }

    private fun _bindDisplay(surface: Surface?) {
        if (window != EGL14.EGL_NO_SURFACE) {
            EGL14.eglMakeCurrent(eglDisplay, pbuffer, pbuffer, eglContext)
            EGL14.eglDestroySurface(eglDisplay, window)
            window = EGL14.EGL_NO_SURFACE
        }
        if (surface == null || !surface.isValid) {
            Log.i(TAG, "bindDisplay: no surface")
            return
        }
        window = EGL14.eglCreateWindowSurface(eglDisplay, eglConfig, surface, intArrayOf(EGL14.EGL_NONE), 0)
        if (window == EGL14.EGL_NO_SURFACE) {
            Log.w(TAG, "eglCreateWindowSurface failed: ${EGL14.eglGetError()}")
            return
        }
        EGL14.eglMakeCurrent(eglDisplay, window, window, eglContext)
        winW = _query(EGL14.EGL_WIDTH)
        winH = _query(EGL14.EGL_HEIGHT)
        Log.i(TAG, "Display bound: ${winW}x${winH}")
        // an idle source sends no new frames, so repaint last one or new surface stays black
        if (hasFrame) _render()
    }

    /** 새 프레임 없이 마지막 텍스처를 현재 스케일로 다시 그린다 (전환 확대/축소용). */
    private fun _redraw() {
        if (window == EGL14.EGL_NO_SURFACE) return
        EGL14.eglMakeCurrent(eglDisplay, window, window, eglContext)
        if (hasFrame) _render()
    }

    private fun _consumeAndDraw() {
        val st = surfaceTexture ?: return
        if (window == EGL14.EGL_NO_SURFACE) {
            // no display: keep consuming so decoder doesn't stall
            Log.w(TAG, "Frame consumed but NO display window — video not shown")
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
        // 소스 비율 보존(fit): 창 비율과 다르면 레터박스 — 회전/리사이즈에도 찌그러지지 않음
        val srcAspect = if (videoH > 0) videoW.toFloat() / videoH else 1f
        val winAspect = if (winH > 0) winW.toFloat() / winH else 1f
        val sx = if (srcAspect > winAspect) 1f else srcAspect / winAspect
        val sy = if (srcAspect > winAspect) winAspect / srcAspect else 1f
        // 전환 스케일: 피트 비율에 곱해 쿼드를 축소/확대 (중앙 기준 — 박스 크기 → 전체 화면).
        // 영상은 1.0에서 멈추고(크롭 금지), 외곽선 링만 계속 커져 화면 밖으로 나간다.
        val s = displayScale
        val videoScale = if (s < 1f) s else 1f
        // 게이트 대기 중(페이드인 전)에는 영상을 fade=0으로 렌더한다 — 페이드인 전에
        // 영상이 노출되는 깜빡임(flash)을 막는다. 콘텐츠 판별(게이트)은 표시와 독립된
        // unfaded 샘플 패스로 수행한다: fade=0이면 샘플도 검정만 읽게 되므로, 실제
        // 콘텐츠 여부는 fade=1로 한 번 그려 판별한 뒤 지우고 표시 패스를 그린다.
        val gatePending = onFirstFramePresented != null && !contentNotified
        var presentContent = false
        if (gatePending) {
            _drawVideo(sx * videoScale, sy * videoScale, 1f)
            presentContent = _gateCheck()
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
        }
        // 영상을 먼저 그리고 링을 위에 덮는다 — 링 테두리는 박스 안쪽에 그려져
        // (스탠바이 border와 동일) 영상 가장자리를 감싼다.
        _drawVideo(sx * videoScale, sy * videoScale, displayFade)
        _drawRing(s)
        EGL14.eglSwapBuffers(eglDisplay, window)
        if (presentContent) {
            onFirstFramePresented?.invoke()
        }
        if (!firstRenderLogged) {
            firstRenderLogged = true
            Log.i(TAG, "First frame rendered to display (swap done)")
        }
    }

    /** 영상 쿼드를 현재 fade로 그린다 (링은 그리지 않음). 샘플 패스(fade=1)와
     * 표시 패스(fade=displayFade)가 공유한다. */
    private fun _drawVideo(scaleX: Float, scaleY: Float, fade: Float) {
        GLES20.glEnable(GLES20.GL_BLEND)
        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA)
        GLES20.glUseProgram(program)
        // 영상도 기기 radius로 라운드 처리(마스크) — 링의 내각과 정확히 맞물려 모서리에서 겹치지 않는다.
        GLES20.glUniform2f(uScale, scaleX, scaleY)
        GLES20.glUniform2f(uWindow, winW.toFloat(), winH.toFloat())
        GLES20.glUniform1f(uRadius, borderRadiusPx)
        GLES20.glUniform1f(uFade, fade)
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
        GLES20.glDisable(GLES20.GL_BLEND)
    }

    /**
     * 첫 콘텐츠 게이트: 현재(샘플 패스) 프레임이 실제(비검정) 콘텐츠인지 swap 전에 판별.
     * swap 완료(화면에 제출) 후에 게이트를 열어 "화면에 표시된 뒤" 호출을 보장한다 —
     * 실제 호출은 _render의 swap 뒤에서 일어난다. 첫 콘텐츠 프레임 1개로는 게이트를
     * 열지 않는다 — macOS 연결 초반엔 어둡거나 멈춘 프레임이 와서, 게이트가 열렸는데
     * 화면이 아직 안 들어온 것처럼 보인다. 연속 CONTENT_FRAMES_TO_OPEN개의 실제
     * 콘텐츠 프레임이 표시됐을 때 게이트를 연다.
     */
    private fun _gateCheck(): Boolean {
        val m = _sampleMaxChannel()
        if (m > CONTENT_THRESHOLD) {
            contentFramesSeen++
            if (contentFramesSeen >= CONTENT_FRAMES_TO_OPEN) {
                contentNotified = true
                Log.i(TAG, "Gate opened: $contentFramesSeen content frames, sample max=$m")
                return true
            }
        } else {
            if (contentFramesSeen > 0) {
                Log.i(TAG, "Gate reset: non-content frame (sample max=$m)")
            }
            contentFramesSeen = 0
        }
        return false
    }

    /** 외곽선(링)을 스케일(s)로 그린다. 링은 고정된 기기(창) 비율을 유지한다 —
     * 컨테이너(영상) 비율에 의존하지 않는다. 영상은 그 안에 피트되어 표시된다
     * (다른 비율 미러링 — 예: 아이폰 — 시에도 링은 그대로, 내부 영역만 달라진다).
     * 바깥 사각형 = s×창, 테두리는 박스 안쪽(Compose border와 동일 방향) — 전환
     * 핸드오프(스탠바이 박스 0.48×창)에서 테두리 띠/코너가 픽셀 단위로 일치해
     * "흔들림"이 없다. 정상 상태(s≈1.1)에서는 링이 화면 밖이라 보이지 않는다 —
     * displayScale을 1f로 되돌리는 리셋이 없으므로(애니메이션이 소유) s=1.0에서
     * 가장자리 링이 남는 문제도 없다.
     * 내각 radius = 기기 radius(borderRadiusPx), 외각 = 기기 radius + 테두리 두께.
     * s > 1.0이면 링 전체가 화면 밖으로 나가 자연스럽게 사라진다. */
    private fun _drawRing(s: Float) {
        if (borderWidthPx <= 0f || borderColorArgb == 0 || winW <= 0 || winH <= 0) return
        GLES20.glEnable(GLES20.GL_BLEND)
        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA)
        GLES20.glUseProgram(ringProgram)
        // 링 바깥 사각형 = 고정 기기 비율(s×창) — 테두리는 안쪽으로 b만큼 그려진다.
        val halfW = s * winW / 2f
        val halfH = s * winH / 2f
        GLES20.glUniform2f(ringUWindow, winW.toFloat(), winH.toFloat())
        GLES20.glUniform2f(ringUHalf, halfW, halfH)
        GLES20.glUniform1f(ringURadius, borderRadiusPx + borderWidthPx)
        GLES20.glUniform1f(ringUBorder, borderWidthPx)
        val r = (borderColorArgb shr 16) and 0xFF
        val g = (borderColorArgb shr 8) and 0xFF
        val b = borderColorArgb and 0xFF
        val a = (borderColorArgb ushr 24) and 0xFF
        GLES20.glUniform4f(ringUColor, r / 255f, g / 255f, b / 255f, a / 255f)
        GLES20.glEnableVertexAttribArray(ringAPos)
        GLES20.glVertexAttribPointer(ringAPos, 2, GLES20.GL_FLOAT, false, 0, POS)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        GLES20.glDisableVertexAttribArray(ringAPos)
        GLES20.glDisable(GLES20.GL_BLEND)
        GLES20.glUseProgram(program)
    }

    /** 화면 중앙 64x64 픽셀의 최대 채널값(0..255). 실제 콘텐츠(비검정)인지 판별에 사용. */
    private fun _sampleMaxChannel(): Int {
        if (window == EGL14.EGL_NO_SURFACE || winW <= 0 || winH <= 0) return 0
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
        }
        return maxChannel
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
        uWindow = GLES20.glGetUniformLocation(program, "uWindow")
        uRadius = GLES20.glGetUniformLocation(program, "uRadius")
        uFade = GLES20.glGetUniformLocation(program, "uFade")
        ringProgram = _buildProgram(RING_VERT, RING_FRAG)
        ringAPos = GLES20.glGetAttribLocation(ringProgram, "aPos")
        ringUWindow = GLES20.glGetUniformLocation(ringProgram, "uWindow")
        ringUHalf = GLES20.glGetUniformLocation(ringProgram, "uHalf")
        ringURadius = GLES20.glGetUniformLocation(ringProgram, "uRadius")
        ringUBorder = GLES20.glGetUniformLocation(ringProgram, "uBorder")
        ringUColor = GLES20.glGetUniformLocation(ringProgram, "uColor")
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

    private fun _buildProgram(vs: String = VERT, fs: String = FRAG): Int {
        val v = _shader(GLES20.GL_VERTEX_SHADER, vs)
        val f = _shader(GLES20.GL_FRAGMENT_SHADER, fs)
        return GLES20.glCreateProgram().also {
            GLES20.glAttachShader(it, v)
            GLES20.glAttachShader(it, f)
            GLES20.glLinkProgram(it)
            GLES20.glDeleteShader(v)
            GLES20.glDeleteShader(f)
            val ok = IntArray(1)
            GLES20.glGetProgramiv(it, GLES20.GL_LINK_STATUS, ok, 0)
            if (ok[0] == 0) {
                Log.e(TAG, "program link failed: ${GLES20.glGetProgramInfoLog(it)}")
            }
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
        /** 콘텐츠 판별 임계값: 중앙 64x64 최대 채널이 이 값 초과면 실제 화면으로 본다. */
        private const val CONTENT_THRESHOLD = 24
        /** 게이트를 열기 위해 연속으로 표시되어야 하는 콘텐츠 프레임 수. */
        private const val CONTENT_FRAMES_TO_OPEN = 8

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
            "varying vec2 vPos;\n" +
            "void main() {\n" +
            "  vPos = aPos;\n" +
            "  gl_Position = vec4(aPos * uScale, 0.0, 1.0);\n" +
            "  vTex = (uTexMatrix * vec4(aTex, 0.0, 1.0)).xy;\n" +
            "}\n"

        private const val FRAG =
            "#extension GL_OES_EGL_image_external : require\n" +
            "precision mediump float;\n" +
            "varying vec2 vTex;\n" +
            "varying vec2 vPos;\n" +
            "uniform samplerExternalOES sTex;\n" +
            "uniform vec2 uWindow;\n" +
            "uniform vec2 uScale;\n" +
            "uniform float uRadius;\n" +
            "uniform float uFade;\n" +
            "float sdRoundRect(vec2 p, vec2 h, float r) {\n" +
            "  vec2 q = abs(p) - (h - r);\n" +
            "  return length(max(q, 0.0)) - r;\n" +
            "}\n" +
            "void main() {\n" +
            "  vec4 col = texture2D(sTex, vTex);\n" +
            // 영상 모서리를 기기 radius로 라운드 — 링의 내각과 맞물려 "겉"만 감싸게 한다.
            // uFade: 컨테이너(영상)만 받는 투명도 애니메이션 — 외곽선(링)은 안 받는다.
            "  vec2 p = vPos * uScale * uWindow * 0.5;\n" +
            "  float d = sdRoundRect(p, uScale * uWindow * 0.5, uRadius);\n" +
            "  float a = (1.0 - smoothstep(-1.0, 0.0, d)) * uFade;\n" +
            "  gl_FragColor = vec4(col.rgb, col.a * a);\n" +
            "}\n"

        private const val RING_VERT =
            "attribute vec2 aPos;\n" +
            "varying vec2 vPos;\n" +
            "void main() {\n" +
            "  vPos = aPos;\n" +
            "  gl_Position = vec4(aPos, 0.0, 1.0);\n" +
            "}\n"

        // 둥근 사각형 링(외곽선): 내각 radius 기준 — 픽셀 공간 SDF로 정확한 코너.
        private const val RING_FRAG =
            "precision mediump float;\n" +
            "varying vec2 vPos;\n" +
            "uniform vec2 uWindow;\n" +
            "uniform vec2 uHalf;\n" +
            "uniform float uRadius;\n" +
            "uniform float uBorder;\n" +
            "uniform vec4 uColor;\n" +
            "float sdRoundRect(vec2 p, vec2 h, float r) {\n" +
            "  vec2 q = abs(p) - (h - r);\n" +
            "  return length(max(q, 0.0)) - r;\n" +
            "}\n" +
            "void main() {\n" +
            "  vec2 p = vPos * uWindow * 0.5;\n" +
            "  float dOuter = sdRoundRect(p, uHalf, uRadius);\n" +
            "  float rIn = max(uRadius - uBorder, 0.0);\n" +
            "  float dInner = sdRoundRect(p, uHalf - vec2(uBorder), rIn);\n" +
            // smoothstep은 edge0 < edge1이어야 한다 (반대면 GPU에서 정의되지 않음 → 링 무보임).
            // 외곽 안(dOuter<0) → 1, 내부 홀(dInner<0) → 0.
            "  float a = (1.0 - smoothstep(-1.0, 0.0, dOuter)) * smoothstep(0.0, 1.0, dInner);\n" +
            "  gl_FragColor = vec4(uColor.rgb, uColor.a * a);\n" +
            "}\n"
    }
}
