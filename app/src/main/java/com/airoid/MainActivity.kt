package com.airoid

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.IBinder
import android.util.Log
import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
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
    val firstFrameShown = service?.firstFrameShown?.collectAsState()?.value ?: false
    val pairingCode = service?.pairingCode?.collectAsState()?.value ?: ""
    val keepScreenOn = remember {
        context.getSharedPreferences(Prefs.NAME, Context.MODE_PRIVATE)
            .getBoolean(Prefs.KEEP_SCREEN_ON, Prefs.DEF_KEEP_SCREEN_ON)
    }

    // 미러링 중에는 화면이 꺼지지 않게 유지 — 단, 앱이 포그라운드(RESUMED)일 때만.
    // 백그라운드로 나가면 keepScreenOn을 내려 시스템 화면 꺼짐 타이머를 허용한다
    // (수신 서비스는 CPU 웨이크락으로 계속 동작 — 화면은 유저가 보고 있을 때만 유지).
    var inForeground by remember { mutableStateOf(true) }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            inForeground = event == Lifecycle.Event.ON_RESUME
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    val view = LocalView.current
    SideEffect { view.keepScreenOn = mirroring && keepScreenOn && inForeground }

    // 항상 전체 화면
    FullScreenEffect()

    // 미러링 전환 애니메이션: 연결 시엔 첫 콘텐츠 프레임이 화면에 실제 표시된
    // 순간(firstFrameShown 이벤트) 즉시 시작된다 — 고정 타이머 없음.
    // 종료 시엔 역방향으로 재생된다(영상이 박스 크기로 작아지며 스탠바이로 복귀).
    val mirrored = mirroring && service != null
    val transition = remember { Animatable(0f) }
    LaunchedEffect(mirrored, firstFrameShown) {
        if (mirrored && firstFrameShown) {
            transition.animateTo(1f, tween(1000, easing = FastOutSlowInEasing))
        } else if (!mirrored && transition.value > 0f) {
            transition.animateTo(0f, tween(1000, easing = FastOutSlowInEasing))
        }
    }
    // 외곽선(링) 파라미터: 테마 색 + 기기 radius(내각 = 링 내각/영상 코너 기준) + 테두리 두께.
    // borderRadiusPx는 "내각 radius" — 기기 radius 그대로 보낸다. 렌더러가 링 외각에
    // 테두리 두께를 더하므로(r+b), 여기에 b까지 더하면 코너가 2b만큼 과하게 둥글어진다.
    val borderColorArgb = MaterialTheme.colorScheme.primary.toArgb()
    val borderWidthPx = with(LocalDensity.current) { BORDER_WIDTH_DP.toPx() }
    val borderRadiusPx = with(LocalDensity.current) { deviceCornerRadiusDp().toPx() }
    LaunchedEffect(service, borderColorArgb) {
        service?.setVideoBorder(borderColorArgb, borderRadiusPx, borderWidthPx)
    }

    // 전환 중 영상+외곽선을 한 몸으로 매 프레임 스케일 동기화 (GL에서 축소 — 레이어 없음).
    // 부모=외곽선(링): 1.1까지 커져 화면 밖으로 나간다. 자식=컨테이너(영상):
    // 스케일은 링을 따르고(1.0 캡), 투명도(fade)도 같은 t를 써서 페이드와 사이즈가
    // 같은 속도로 움직인다 — 진입 시 페이드만 먼저 끝나지 않는다.
    // 링 테두리는 s×창 박스의 안쪽(스탠바이 border와 동일)이라 t=0에서 스탠바이
    // 박스와 픽셀 단위로 일치한다 — 별도 보정 불필요.
    LaunchedEffect(service, transition) {
        snapshotFlow { transition.value }.collect { t ->
            val boxFraction = BOX_MIN_FRACTION + (BOX_MAX_FRACTION - BOX_MIN_FRACTION) * t
            service?.setVideoTransition(boxFraction, t)
        }
    }

    // 앱 창 크기 변화(회전/스플릿뷰/윈도우모드 등 모든 리사이즈)를 서버에 보고 →
    // 광고 해상도와 렌더러 해상도가 함께 따라간다
    Box(
        Modifier
            .fillMaxSize()
            .onSizeChanged { size ->
                if (size.width > 0 && size.height > 0) {
                    service?.setVideoAreaSize(size.width, size.height)
                }
            }
    ) {
        DisplayOptionsLayer(
            mirroring = mirroring,
            onDisconnectMirroring = { service?.disconnectClients() },
        ) {
            // 미러 영상: 연결/표시 중 + 종료 애니메이션 동안 유지된다.
            // SurfaceView는 항상 plain full-screen — 영상 스케일과 외곽선(링)을 GL에서
            // 한 트랜스폼으로 그려 싱크가 구조적으로 맞는다 (Compose 레이어 합성 문제 회피).
            val showVideo = (mirrored || transition.value > 0f) && service != null
            if (showVideo) {
                Box(Modifier.fillMaxSize()) {
                    MirrorView(service)
                }
            }
            // 스탠바이/전환:
            // - 완전 미러링(t=1): 스탠바이 없음
            // - 대기/게이트 전(t=0): 배경 포함 불투명
            // - 전환 중(0<t<1): 내용(아이콘/제목/코드/박스)만 (1-t)로 페이드 —
            //   배경(검정)은 GL clear가 담당해 영상 페이드(셰이더)와 같은 속도로 맞물린다.
            //   진입 시 내용이 사라지고, 종료 시 내용이 나타난다 (팝 없음).
            val t = transition.value
            if (!(mirrored && t >= 1f)) {
                StandbyScreen(
                    code = pairingCode,
                    contentAlpha = (1f - t).coerceIn(0f, 1f),
                    showBackground = t == 0f,
                )
            }
        }
    }
}

/**
 * 미러링 영상을 표시하는 SurfaceView. 서버에 서피스를 넘겨 MediaCodec 렌더러가
 * 디코딩된 프레임을 여기로 그리게 한다.
 * 크기 보고는 루트 Box가 담당한다(회전/리사이즈 공용).
 */
private const val MIRROR_TAG = "MirrorView"

@Composable
private fun MirrorView(service: AirPlayService) {
    val callback = remember(service) {
        object : SurfaceHolder.Callback {
            override fun surfaceCreated(holder: SurfaceHolder) {
                Log.i(MIRROR_TAG, "Surface created: ${holder.surface}")
                service.setVideoSurface(holder.surface)
            }
            override fun surfaceChanged(holder: SurfaceHolder, format: Int, w: Int, h: Int) {
                // 크기 변경(회전/리사이즈): 서피스가 살아 있는 채 크기만 바뀌면
                // surfaceDestroyed 없이 여기만 온다. EGL 윈도우를 다시 바인드해
                // 새 winW/winH를 쿼리한다 — 안 하면 뷰포트가 낡은 크기로 남는다.
                if (w > 0 && h > 0) {
                    service.setVideoSurface(holder.surface)
                }
            }
            override fun surfaceDestroyed(holder: SurfaceHolder) {
                Log.i(MIRROR_TAG, "Surface destroyed")
                service.clearVideoSurface(holder.surface)
            }
        }
    }
    AndroidView(
        factory = { ctx ->
            SurfaceView(ctx).apply { holder.addCallback(callback) }
        },
        modifier = Modifier.fillMaxSize()
    )
}
