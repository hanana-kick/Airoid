package com.airoid

import android.app.Activity
import android.view.WindowInsets as AndroidWindowInsets
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
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
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathBuilder
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.lerp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsControllerCompat

/**
 * 미러링 대기 화면. 검정 배경을 사용한다.
 * Airoid 아래에 AirPlay 기기 이름과 같은 페어링 코드를 익스프레시브 스타일로 표시한다:
 * 코드 필(primaryContainer)의 코너 반경이 모프(모핑 셰이프)되고, 코드 옆에 AirPlay 아이콘이 있어
 * "이 기기로 에어플레이를 연결하라"는 의도를 보여준다.
 * "미러링 대기 중" 텍스트는 점(···) 애니메이션으로 대기 상태를 표현한다.
 * 연결이 시작되면(connecting) 필이 스프링으로 커지고 진행 인디케이터가 나타난다.
 */
@Composable
fun StandbyScreen(connecting: Boolean) {
    val scheme = MaterialTheme.colorScheme
    val context = LocalContext.current.applicationContext
    val code = remember { PairingCode.get(context) }

    // 익스프레시브 셰이프 모프: 코너 반경 28↔56dp (스케일 펄스 없음)
    val breathing by rememberInfiniteTransition(label = "standbyBreath").animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 2400, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "breath",
    )
    val corner = lerp(28.dp, 56.dp, breathing)

    // 연결 시작 시 스프링 버프
    val connectScale by animateFloatAsState(
        targetValue = if (connecting) 1.08f else 1f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessLow,
        ),
        label = "connectScale",
    )

    // "미러링 대기 중" 점(···) 애니메이션
    val dotTransition = rememberInfiniteTransition(label = "waitingDots")
    val dotAlphas = List(3) { i ->
        dotTransition.animateFloat(
            initialValue = 0.25f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = 500, delayMillis = i * 250),
                repeatMode = RepeatMode.Reverse,
            ),
            label = "dot$i",
        )
    }

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
                shape = RoundedCornerShape(corner),
                color = scheme.primaryContainer,
                modifier = Modifier
                    .padding(top = 16.dp)
                    .graphicsLayer {
                        scaleX = connectScale
                        scaleY = connectScale
                    },
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
                    Row(
                        Modifier.padding(top = 28.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(20.dp),
                            color = scheme.primary,
                            strokeWidth = 2.5.dp,
                        )
                        Text(
                            text = stringResource(R.string.standby_connecting),
                            color = scheme.onSurfaceVariant,
                            style = MaterialTheme.typography.bodyLarge,
                            modifier = Modifier.padding(start = 10.dp)
                        )
                    }
                } else {
                    Row(
                        Modifier.padding(top = 28.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = stringResource(R.string.standby_waiting),
                            color = scheme.onSurfaceVariant,
                            style = MaterialTheme.typography.bodyLarge,
                        )
                        dotAlphas.forEach { d ->
                            Text(
                                text = "·",
                                color = scheme.onSurfaceVariant.copy(alpha = d.value),
                                style = MaterialTheme.typography.bodyLarge,
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * 화면 미러링(에어플레이) 아이콘 — SF Symbol "rectangle.on.rectangle" 참조:
 * 대각선으로 겹친 둥근 모서리의 두 사각형 (뒤쪽은 좌상, 앞쪽은 우하).
 */
private val MirrorIcon: ImageVector = ImageVector.Builder(
    name = "ScreenMirror",
    defaultWidth = 24.dp,
    defaultHeight = 24.dp,
    viewportWidth = 24f,
    viewportHeight = 24f,
).apply {
    // 뒤 사각형 (좌상)
    path(fill = SolidColor(Color.Black)) {
        roundedRect(3f, 3f, 17f, 17f, 2.5f)
    }
    // 앞 사각형 (우하, 겹침)
    path(fill = SolidColor(Color.Black)) {
        roundedRect(7f, 7f, 21f, 21f, 2.5f)
    }
}.build()

/** 시계 방향 둥근 사각형 경로 (SF 심볼 스타일 코너). */
private fun PathBuilder.roundedRect(x0: Float, y0: Float, x1: Float, y1: Float, r: Float) {
    moveTo(x0 + r, y0)
    lineTo(x1 - r, y0)
    arcTo(r, r, 0f, false, true, x1, y0 + r)
    lineTo(x1, y1 - r)
    arcTo(r, r, 0f, false, true, x1 - r, y1)
    lineTo(x0 + r, y1)
    arcTo(r, r, 0f, false, true, x0, y1 - r)
    lineTo(x0, y0 + r)
    arcTo(r, r, 0f, false, true, x0 + r, y0)
    close()
}

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
