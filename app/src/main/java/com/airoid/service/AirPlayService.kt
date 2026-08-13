package com.airoid.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.content.res.Configuration
import android.hardware.display.DisplayManager
import android.os.Binder
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.util.Log
import android.view.Display
import android.view.Surface
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.airoid.MainActivity
import com.airoid.PairingCode
import com.airoid.Prefs
import com.airoid.R
import com.airoid.bridge.LogListener
import com.airoid.bridge.NativeBridge
import com.airoid.bridge.RaopCallbackHandler
import com.airoid.discovery.NsdServiceManager
import com.airoid.renderer.AudioRenderer
import com.airoid.renderer.VideoRenderer
import java.net.NetworkInterface
import java.security.SecureRandom
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Airoid 수신기 서비스.
 * 네이티브(UxPlay) 서버 수명주기를 소유하고, mDNS 등록을 맡으며,
 * RAOP 콜백(비디오/오디오/연결)을 MediaCodec 렌더러로 라우팅한다.
 * 화면 미러링(보조 모니터) + 오디오 수신이 핵심. (HLS/DACP/미디어 세션은 생략)
 */
class AirPlayService : LifecycleService(), RaopCallbackHandler, LogListener {

    private var nativeHandle = 0L
    private var nsdManager: NsdServiceManager? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var foregroundStarted = false

    val videoRenderer = VideoRenderer()
    val audioRenderer = AudioRenderer()

    private val _serverState = MutableStateFlow(ServerState.STOPPED)
    val serverState = _serverState.asStateFlow()

    private val _connectionCount = MutableStateFlow(0)
    val connectionCount = _connectionCount.asStateFlow()

    private val _mirroringActive = MutableStateFlow(false)
    val mirroringActive = _mirroringActive.asStateFlow()

    private val _audioOnly = MutableStateFlow(false)
    val audioOnly = _audioOnly.asStateFlow()

    private val _videoAspect = MutableStateFlow(16f / 9f)
    val videoAspect = _videoAspect.asStateFlow()

    private val _videoResolution = MutableStateFlow("")
    val videoResolution = _videoResolution.asStateFlow()

    /** 현재 연결 대기 세션의 페어링 코드. 서버 시작 시 새로 생성된다. */
    private val _pairingCode = MutableStateFlow("")
    val pairingCode = _pairingCode.asStateFlow()

    var logCallback: ((String) -> Unit)? = null

    private fun log(msg: String) {
        Log.i(TAG, msg)
        logCallback?.invoke(msg)
    }

    override fun onLog(msg: String) {
        logCallback?.invoke(msg)
    }

    inner class LocalBinder : Binder() {
        val service: AirPlayService
            get() = this@AirPlayService
    }

    override fun onBind(intent: Intent): IBinder {
        super.onBind(intent)
        return LocalBinder()
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        log("Service created")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_START_SERVER) {
            promoteToForeground()
            startServer()
            if (_serverState.value != ServerState.RUNNING) stopSelf(startId)
        }
        return START_NOT_STICKY
    }

    fun startServer() {
        if (_serverState.value == ServerState.RUNNING) return
        // 연결 대기(서버 시작) 시에만 새 코드 생성 — 재실행 중 서버가 살아 있으면 코드 유지
        val code = PairingCode.random()
        _pairingCode.value = code
        val effectiveName = PairingCode.deviceName(code)

        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "airplay:server").apply { acquire() }

        nsdManager = NsdServiceManager(this).apply { acquireMulticastLock() }

        val hwAddr = getHwAddr()
        val keyFile = filesDir.resolve("airplay.pem").absolutePath
        val nohold = true
        val requirePin = false

        NativeBridge.nativeSetDefaultStreamValues(44100, 0)
        nativeHandle = NativeBridge.nativeInit(this, hwAddr, effectiveName, keyFile, nohold, requirePin)
        if (nativeHandle == 0L) {
            log("Native init failed")
            _failStart()
            return
        }
        audioRenderer.attachEngine(nativeHandle)

        val prefs = getSharedPreferences(Prefs.NAME, Context.MODE_PRIVATE)
        videoRenderer.benchmarkLog = prefs.getBoolean(Prefs.BENCHMARK_LOG, Prefs.DEF_BENCHMARK_LOG)
        // 기본은 즉시 표시(저지연). PTS 스케줄 표시는 지연을 늘릴 수 있다.
        videoRenderer.scheduledOutputBufferRelease =
            prefs.getBoolean(Prefs.SCHEDULED_OUTPUT_BUFFER_RELEASE, Prefs.DEF_SCHEDULED_OUTPUT_BUFFER_RELEASE)

        val h265 = VideoRenderer.supportsH265()
        NativeBridge.nativeSetH265Enabled(nativeHandle, h265)
        NativeBridge.nativeSetCodecs(nativeHandle, false, true) // AAC only; ALAC(ffmpeg)은 기본 끔
        NativeBridge.nativeSetHlsEnabled(nativeHandle, false)
        NativeBridge.nativeSetAudioEnabled(nativeHandle, true)

        // 광고 해상도/재생률
        val (w, h) = realDisplaySize()
        videoRenderer.setResolution(w, h)
        _videoResolution.value = "${w}x${h}"
        _videoAspect.value = w.toFloat() / h
        NativeBridge.nativeSetDisplaySize(nativeHandle, w, h, displayMaxRefreshRate())

        val port = NativeBridge.nativeStart(nativeHandle, 7000)
        if (port < 0) {
            log("Failed to start on port 7000")
            _failStart()
            return
        }

        val raopTxt = NativeBridge.nativeGetRaopTxtRecords(nativeHandle) ?: emptyMap()
        val airplayTxt = NativeBridge.nativeGetAirplayTxtRecords(nativeHandle) ?: emptyMap()
        val raopName = NativeBridge.nativeGetRaopServiceName(nativeHandle) ?: "AirPlay"
        val resolvedName = NativeBridge.nativeGetServerName(nativeHandle) ?: effectiveName

        nsdManager?.registerRaop(raopName, port, raopTxt)
        nsdManager?.registerAirplay(resolvedName, port, airplayTxt)

        _serverState.value = ServerState.RUNNING
        promoteToForeground()
        log("Server started on port $port")
    }

    fun stopServer() {
        audioRenderer.detachEngine()
        if (nativeHandle != 0L) {
            NativeBridge.nativeStop(nativeHandle)
            NativeBridge.nativeDestroy(nativeHandle)
            nativeHandle = 0L
        }
        nsdManager?.release()
        nsdManager = null
        wakeLock?.release()
        wakeLock = null
        videoRenderer.release()
        _audioOnly.value = false
        _mirroringActive.value = false
        _serverState.value = ServerState.STOPPED
        _connectionCount.value = 0
        stopForeground(STOP_FOREGROUND_REMOVE)
        foregroundStarted = false
        stopSelf()
        log("Server stopped")
    }

    private fun _failStart() {
        audioRenderer.detachEngine()
        if (nativeHandle != 0L) {
            NativeBridge.nativeDestroy(nativeHandle)
            nativeHandle = 0L
        }
        nsdManager?.release()
        nsdManager = null
        wakeLock?.release()
        wakeLock = null
        _serverState.value = ServerState.ERROR
        if (foregroundStarted) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            foregroundStarted = false
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        if (nativeHandle == 0L || _serverState.value != ServerState.RUNNING) return
        val (w, h) = realDisplaySize()
        NativeBridge.nativeSetDisplaySize(nativeHandle, w, h, displayMaxRefreshRate())
        log("Advertising ${w}x${h}")
    }

    /**
     * 액티비티가 실제 표시 영역 크기를 보고한다(회전/스플릿뷰/윈도우모드 등 모든 리사이즈 시).
     * 광고 해상도(SDP)를 갱신한다. 화면 방향/해상도 변경은 ScreenRotate 메커니즘으로
     * 송신기가 새 Codec Data(SPS/PPS)를 보내고, 렌더러가 코덱을 재시작해 끊김 없이 반영한다.
     */
    fun setVideoAreaSize(w: Int, h: Int) {
        if (w <= 0 || h <= 0) return
        if (nativeHandle == 0L || _serverState.value != ServerState.RUNNING) return
        NativeBridge.nativeSetDisplaySize(nativeHandle, w, h, displayMaxRefreshRate())
        _videoResolution.value = "${w}x${h}"
        _videoAspect.value = w.toFloat() / h
    }

    /** 서버 측에서 현재 연결된 AirPlay 클라이언트(미러링/오디오)를 강제로 종료한다. */
    fun disconnectClients() {
        if (nativeHandle == 0L || _serverState.value != ServerState.RUNNING) return
        NativeBridge.nativeDisconnectClients(nativeHandle)
        audioRenderer.stop()
        log("Disconnected clients on request")
    }

    override fun onDestroy() {
        if (_serverState.value == ServerState.RUNNING) stopServer()
        super.onDestroy()
    }

    fun setVideoSurface(surface: Surface) {
        videoRenderer.setSurface(surface)
    }

    fun clearVideoSurface(surface: Surface) {
        videoRenderer.clearSurface(surface)
    }

    // ---- RaopCallbackHandler (native threads) ----

    override fun onVideoData(data: ByteArray, ntpTimeNs: Long, isH265: Boolean) {
        videoRenderer.feedFrame(data, ntpTimeNs, isH265)
    }

    override fun onVideoSize(srcW: Float, srcH: Float, w: Float, h: Float) {
        if (w > 0 && h > 0) {
            _videoAspect.value = w / h
            _videoResolution.value = "${w.toInt()}x${h.toInt()}"
            // 버퍼는 소스 해상도로: 디코더 업스케일 부하 제거 (GL이 서피스 크기로 확대)
            if (srcW > 0 && srcH > 0) {
                videoRenderer.setResolution(srcW.toInt(), srcH.toInt())
            } else {
                videoRenderer.setResolution(w.toInt(), h.toInt())
            }
            _mirroringActive.value = true
        }
        log("Video size: ${srcW}x${srcH} -> ${w}x${h}")
    }

    override fun onAudioFormat(ct: Int, spf: Int, usingScreen: Boolean) {
        audioRenderer.start()
        audioRenderer.setFormat(ct, spf)
        log("Audio format: ct=$ct spf=$spf screen=$usingScreen")
    }

    override fun onConnectionInit() {
        _connectionCount.value++
        log("Client connected (${_connectionCount.value})")
        launchMainActivity()
    }

    override fun onConnectionDestroy() {
        _connectionCount.value = (_connectionCount.value - 1).coerceAtLeast(0)
        if (_connectionCount.value == 0) {
            audioRenderer.stop()
            _audioOnly.value = false
            _mirroringActive.value = false
        }
        log("Client disconnected (${_connectionCount.value})")
    }

    override fun onConnectionReset(reason: Int) {
        log("Connection reset: $reason")
    }

    override fun onAudioOnly(audioOnly: Boolean) {
        _audioOnly.value = audioOnly
        log(if (audioOnly) "Audio mode" else "Mirror mode")
    }

    override fun onAudioTeardown() = Unit
    override fun onVolumeChange(volume: Float) = Unit
    override fun onClientVolume(): Float = -1f
    override fun onDisplayPin(pin: String) = Unit
    override fun onMetadata(data: ByteArray) = Unit
    override fun onCoverArt(data: ByteArray) = Unit
    override fun onProgress(start: Long, curr: Long, end: Long) = Unit
    override fun onDacpId(dacpId: String, activeRemote: String) = Unit
    // HLS 비디오 재생은 생략 (보조 모니터 미러링이 핵심)
    override fun onVideoPlay(location: String, startPositionSeconds: Float) = Unit
    override fun onVideoScrub(positionSeconds: Float) = Unit
    override fun onVideoRate(rate: Float) = Unit
    override fun onVideoStop() = Unit
    override fun onVideoSessionPoll() = Unit

    // ---- helpers ----

    /**
     * AirPlay 기기 식별용 MAC. 최초 1회 결정 후 SharedPreferences에 저장하고,
     * 이후에는 항상 저장된 값을 사용한다. 서버 이름(연결명)이나 네트워크가 바뀌어도
     * 동일 기기로 인식되게 하기 위함이다.
     */
    private fun getHwAddr(): ByteArray {
        val prefs = getSharedPreferences(Prefs.NAME, Context.MODE_PRIVATE)
        prefs.getString(Prefs.FALLBACK_MAC_ADDRESS, null)?.let { saved ->
            parseMac(saved)?.let { return it }
        }
        val mac = interfaceMac() ?: randomAaiMac()
        prefs.edit().putString(Prefs.FALLBACK_MAC_ADDRESS, mac.toColonString()).apply()
        return mac
    }

    private fun interfaceMac(): ByteArray? {
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces()
            for (iface in interfaces) {
                if (iface.name.startsWith("wlan") || iface.name.startsWith("eth")) {
                    val mac = iface.hardwareAddress
                    if (isUsableMac(mac)) return mac
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to get hardware address", e)
        }
        return null
    }

    private fun parseMac(s: String): ByteArray? {
        val parts = s.split(":")
        if (parts.size != 6) return null
        val mac = ByteArray(6)
        for (i in 0 until 6) {
            val v = parts[i].toIntOrNull(16) ?: return null
            if (v !in 0..0xFF) return null
            mac[i] = v.toByte()
        }
        return mac.takeIf { isUsableMac(it) }
    }

    private fun ByteArray.toColonString(): String =
        joinToString(":") { "%02X".format(it.toInt() and 0xFF) }

    private fun isUsableMac(mac: ByteArray?): Boolean =
        mac != null && mac.size == 6 &&
            mac.any { it != 0.toByte() } &&
            !(mac[0] == 0x02.toByte() && mac.drop(1).all { it == 0.toByte() })

    private fun randomAaiMac(): ByteArray {
        val mac = ByteArray(6).also { SecureRandom().nextBytes(it) }
        mac[0] = ((mac[0].toInt() and 0xF0) or 0x0A).toByte()
        return mac
    }

    private fun realDisplaySize(): Pair<Int, Int> {
        val dm = resources.displayMetrics
        val w = dm.widthPixels
        val h = dm.heightPixels
        val portrait = resources.configuration.orientation == Configuration.ORIENTATION_PORTRAIT
        return if (portrait != h >= w) h to w else w to h
    }

    /** 광고 fps. 디스플레이 최대 주사율을 쓰되 60으로 상한. */
    private fun displayMaxRefreshRate(): Int {
        val dm = getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
        val display = dm.getDisplay(Display.DEFAULT_DISPLAY) ?: return 60
        val maxRate = display.supportedModes.maxOfOrNull { it.refreshRate } ?: 60f
        return maxRate.toInt().coerceIn(30, 60)
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.notification_channel),
            NotificationManager.IMPORTANCE_LOW
        )
        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(channel)
    }

    private fun buildNotification(): Notification {
        val intent = Intent(this, MainActivity::class.java)
        val pi = PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE)
        val title = if (_mirroringActive.value) {
            getString(R.string.notification_active_title)
        } else {
            getString(R.string.notification_title)
        }
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle(title)
            .setContentText(getString(R.string.notification_text))
            .setContentIntent(pi)
            .setOngoing(true)
            .build()
    }

    private fun promoteToForeground() {
        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            buildNotification(),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
        )
        foregroundStarted = true
    }

    private fun launchMainActivity() {
        Handler(Looper.getMainLooper()).post {
            val launchIntent = Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
            try {
                startActivity(launchIntent)
            } catch (e: Exception) {
                Log.w(TAG, "Failed to launch activity", e)
            }
        }
    }

    enum class ServerState {
        STOPPED,
        RUNNING,
        ERROR
    }

    companion object {
        private const val TAG = "AirPlayService"
        private const val CHANNEL_ID = "airplay_service"
        private const val NOTIFICATION_ID = 1
        const val ACTION_START_SERVER = "com.airoid.START_SERVER"
    }
}
