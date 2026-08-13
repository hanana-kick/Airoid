package com.airoid

import android.app.Activity
import android.view.WindowInsets as AndroidWindowInsets
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsControllerCompat

/**
 * 미러링 대기 화면. 검정 배경을 사용한다.
 * Airoid 아래에 AirPlay 기기 이름과 같은 페어링 코드를 표시한다:
 * 코드 필(primaryContainer)에 SF "airplay.video" 아이콘과 코드가 함께 들어간다.
 * 로딩 스피너/대기 텍스트는 표시하지 않는다. 연결이 시작되면(connecting) 필 아래에 "연결 중…"이 나타난다.
 */
@Composable
fun StandbyScreen(connecting: Boolean) {
    val scheme = MaterialTheme.colorScheme
    val context = LocalContext.current.applicationContext
    val code = remember { PairingCode.get(context) }

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        Column(
            Modifier
                .fillMaxSize()
                .padding(horizontal = 24.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = stringResource(R.string.app_name),
                color = scheme.onBackground,
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold
            )
            Surface(
                shape = RoundedCornerShape(28.dp),
                color = scheme.primaryContainer,
                modifier = Modifier.padding(top = 16.dp),
            ) {
                Row(
                    Modifier.padding(horizontal = 28.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        imageVector = MirrorIcon,
                        contentDescription = null,
                        tint = scheme.onPrimaryContainer,
                        modifier = Modifier.size(24.dp),
                    )
                    Text(
                        text = code,
                        color = scheme.onPrimaryContainer,
                        style = MaterialTheme.typography.headlineLarge,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(start = 10.dp)
                    )
                }
            }
            AnimatedContent(
                targetState = connecting,
                transitionSpec = { fadeIn() togetherWith fadeOut() },
                label = "status",
            ) { isConnecting ->
                if (isConnecting) {
                    Text(
                        text = stringResource(R.string.standby_connecting),
                        color = scheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.padding(top = 28.dp)
                    )
                }
            }
        }
    }
}

/**
 * 화면 미러링(에어플레이) 아이콘 — SF Symbol "airplay.video" 원본 경로 데이터
 * (SF Symbols 기반 공개 SVG 경로를 그대로 사용): 하단 중앙이 열린 TV 외곽선 + 위를 향한 삼각형.
 */
private val MirrorIcon: ImageVector = ImageVector.Builder(
    name = "AirplayVideo",
    defaultWidth = 24.dp,
    defaultHeight = 24.dp,
    viewportWidth = 24f,
    viewportHeight = 24f,
).apply {
    // TV 외곽선 (하단 중앙 열림)
    path(
        stroke = SolidColor(Color.Black),
        strokeLineWidth = 2f,
        strokeLineCap = StrokeCap.Round,
        strokeLineJoin = StrokeJoin.Round,
    ) {
        moveTo(5f, 17f)
        lineTo(4f, 17f)
        curveTo(2.89543f, 17f, 2f, 16.1046f, 2f, 15f)
        lineTo(2f, 5f)
        curveTo(2f, 3.89543f, 2.89543f, 3f, 4f, 3f)
        lineTo(20f, 3f)
        curveTo(21.1046f, 3f, 22f, 3.89543f, 22f, 5f)
        lineTo(22f, 15f)
        curveTo(22f, 16.1046f, 21.1046f, 17f, 20f, 17f)
        lineTo(19f, 17f)
    }
    // AirPlay 삼각형 (위를 향함)
    path(fill = SolidColor(Color.Black)) {
        moveTo(12f, 16f)
        lineTo(18f, 21f)
        lineTo(6f, 21f)
        close()
    }
}.build()

/**
 * 미러링 영상 위에 올라가는 공용 레이어.
 * 미러링 중 위로 스와이프하면 "미러링 종료" 패널이 열린다.
 * 패널은 별도 창(popup)이 아니라 같은 창 안에 그린다 — 팝업 창이 열릴 때
 * 시스템 표시줄이 나타나는 것을 막기 위함이다.
 */
@Composable
fun DisplayOptionsLayer(
    mirroring: Boolean,
    onDisconnectMirroring: () -> Unit,
    content: @Composable () -> Unit,
) {
    var showOptions by remember { mutableStateOf(false) }

    Box(Modifier.fillMaxSize()) {
        content()

        if (mirroring) {
            // 제스처 레이어: 위로 스와이프 → 미러링 종료 패널 열기
            Box(
                Modifier
                    .fillMaxSize()
                    .pointerInput(showOptions) {
                        var total = 0f
                        detectDragGestures { _, amount ->
                            total += amount.y
                            if (!showOptions && total <= -120f) {
                                showOptions = true
                            }
                        }
                    }
            )
        }

        if (showOptions && mirroring) {
            OptionsOverlay(
                onDisconnectMirroring = onDisconnectMirroring,
                onDismiss = { showOptions = false },
            )
        }
    }
}

/**
 * 미러링 종료 패널(인앱 오버레이). 스크림 탭이나 아래로 스와이프로 닫힌다.
 */
@Composable
private fun OptionsOverlay(
    onDisconnectMirroring: () -> Unit,
    onDismiss: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    Box(Modifier.fillMaxSize()) {
        // 스크림: 탭하면 닫기
        Box(
            Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.5f))
                .pointerInput(Unit) { detectTapGestures { onDismiss() } }
        )

        Surface(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .widthIn(max = 640.dp)
                .navigationBarsPadding()
                .pointerInput(onDismiss) {
                    var total = 0f
                    detectVerticalDragGestures { _, amount ->
                        total += amount
                        if (total >= 96f) onDismiss()
                    }
                },
            shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
            color = scheme.surfaceContainerLow,
        ) {
            Column(
                Modifier
                    .padding(horizontal = 24.dp)
                    .padding(bottom = 24.dp)
            ) {
                // 드래그 핸들
                Box(
                    Modifier
                        .align(Alignment.CenterHorizontally)
                        .padding(top = 12.dp, bottom = 8.dp)
                        .size(width = 32.dp, height = 4.dp)
                        .background(
                            color = scheme.onSurfaceVariant.copy(alpha = 0.4f),
                            shape = RoundedCornerShape(2.dp)
                        )
                )
                Text(
                    text = stringResource(R.string.options_title),
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.padding(bottom = 4.dp)
                )
                Button(
                    onClick = {
                        onDisconnectMirroring()
                        onDismiss()
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp),
                ) {
                    Text(stringResource(R.string.option_end_mirroring))
                }
            }
        }
    }
}

/** 시스템 바를 숨겨 항상 전체 화면으로 유지한다. */
@Composable
fun FullScreenEffect() {
    val view = LocalView.current
    val window = (view.context as? Activity)?.window ?: return
    val controller = WindowCompat.getInsetsController(window, view)
    SideEffect {
        controller.hide(AndroidWindowInsets.Type.systemBars())
        controller.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
    }
}
