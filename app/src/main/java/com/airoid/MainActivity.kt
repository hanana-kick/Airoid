package com.airoid

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.IBinder
import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.viewinterop.AndroidView
import com.airoid.service.AirPlayService

/**
 * Airoid — AirPlay 수신기.
 * 열면 미러링 대기 상태가 되고, 맥이 연결되면 미러링 화면이 전체 화면으로 표시된다.
 * 미러링 중 위로 스와이프하면 "미러링 종료" 패널이 열린다.
 */
class MainActivity : ComponentActivity() {

    private val serviceState = mutableStateOf<AirPlayService?>(null)

    private val conn = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            serviceState.value = (binder as AirPlayService.LocalBinder).service
        }
        override fun onServiceDisconnected(name: ComponentName) {
            serviceState.value = null
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // 수신기 서비스 시작(포그라운드) + 바인드
        val startIntent = Intent(this, AirPlayService::class.java)
            .setAction(AirPlayService.ACTION_START_SERVER)
        startForegroundService(startIntent)
        bindService(
            Intent(this, AirPlayService::class.java),
            conn,
            Context.BIND_AUTO_CREATE
        )

        setContent {
            AiroidTheme {
                AiroidApp(serviceState.value)
            }
        }
    }

    override fun onDestroy() {
        runCatching { unbindService(conn) }
        super.onDestroy()
    }
}

@Composable
fun AiroidApp(service: AirPlayService?) {
    val context = LocalContext.current.applicationContext
    val mirroring = service?.mirroringActive?.collectAsState()?.value ?: false
    val connectionCount = service?.connectionCount?.collectAsState()?.value ?: 0
    val connecting = connectionCount > 0 && !mirroring
    val pairingCode = service?.pairingCode?.collectAsState()?.value ?: ""
    val keepScreenOn = remember {
        context.getSharedPreferences(Prefs.NAME, Context.MODE_PRIVATE)
            .getBoolean(Prefs.KEEP_SCREEN_ON, Prefs.DEF_KEEP_SCREEN_ON)
    }

    // 미러링 중에는 화면이 꺼지지 않게 유지
    val view = LocalView.current
    SideEffect { view.keepScreenOn = mirroring && keepScreenOn }

    // 항상 전체 화면
    FullScreenEffect()

    Box(Modifier.fillMaxSize()) {
        DisplayOptionsLayer(
            mirroring = mirroring,
            onDisconnectMirroring = { service?.disconnectClients() },
        ) {
            if (mirroring && service != null) {
                MirrorView(service)
            } else {
                StandbyScreen(connecting = connecting, code = pairingCode)
            }
        }
    }
}

/**
 * 미러링 영상을 표시하는 SurfaceView. 서버에 서피스를 넘겨 MediaCodec 렌더러가
 * 디코딩된 프레임을 여기로 그리게 한다.
 *
 * 표시 영역 크기가 바뀌면(회전/리사이즈) 서버에 보고해 렌더러 해상도가 자동으로 따라간다.
 */
@Composable
private fun MirrorView(service: AirPlayService) {
    val callback = remember(service) {
        object : SurfaceHolder.Callback {
            override fun surfaceCreated(holder: SurfaceHolder) {
                service.setVideoSurface(holder.surface)
            }
            override fun surfaceChanged(holder: SurfaceHolder, format: Int, w: Int, h: Int) {}
            override fun surfaceDestroyed(holder: SurfaceHolder) {
                service.clearVideoSurface(holder.surface)
            }
        }
    }
    AndroidView(
        factory = { ctx ->
            SurfaceView(ctx).apply { holder.addCallback(callback) }
        },
        modifier = Modifier
            .fillMaxSize()
            .onSizeChanged { size ->
                if (size.width > 0 && size.height > 0) {
                    service.setVideoAreaSize(size.width, size.height)
                }
            }
    )
}
