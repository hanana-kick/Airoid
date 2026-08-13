package com.airoid

import android.app.Activity
import android.content.Context
import android.content.res.Resources
import android.os.Build
import android.view.RoundedCorner
import android.view.WindowInsets as AndroidWindowInsets
import android.view.WindowManager
import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsControllerCompat
import kotlin.math.roundToInt
import kotlinx.coroutines.launch

/**
 * 미러링 대기 화면(정적). 검정 배경을 사용한다.
 * 기기 화면 비율의 둥근 박스(기기 코너 반경, 테마색 외곽선, 검정 내부)가
 * "Airoid" 타이틀과 페어링 코드(코드 필: SF "airplay.video" 아이콘 + 코드)를 함께 감싼다.
 * 박스는 비율을 유지하되 내용(타이틀+코드)보다 작아지지 않는 최소 크기를 보장한다.
 * 코드는 서버가 연결 대기를 만들 때마다 새로 생성되어 전달된다(영속화하지 않음).
 * 전환 애니메이션은 여기서 하지 않는다 — 진입/종료 시엔 이 화면이 언마운트되고,
 * GL 렌더러가 영상+외곽선(링)을 한 트랜스폼으로 그린다 (싱크 불일치 원천 제거).
 */
@Composable
fun StandbyScreen(code: String, contentAlpha: Float = 1f, showBackground: Boolean = true) {
    val scheme = MaterialTheme.colorScheme
    val config = LocalConfiguration.current
    // 기기 화면 비율: 세로일 때 0.755 (1848:2448), 회전 시 자동 반영
    val screenAspect = config.screenWidthDp.toFloat() / config.screenHeightDp.toFloat()
    // 코너 radius는 "안쪽(검정 내부)" 기준으로 기기와 일치시킨다.
    // 테두리가 shape 안쪽에 그려지므로 shape radius = 기기 radius + 테두리 두께.
    val cornerRadius = deviceCornerRadiusDp() + BORDER_WIDTH_DP
    // 상하 패딩을 크게 유지한다(항상 64dp).
    val contentVerticalPadding = 64.dp

    Box(Modifier.fillMaxSize()) {
        // 검정 배경 — 전환 중에는 GL clear 검정이 배경을 담당하므로 생략된다
        if (showBackground) {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(Color.Black)
            )
        }
        // 박스(외곽선) + 내용: 전환 중엔 contentAlpha로 아이콘/제목/코드가 함께 페이드.
        // 박스 테두리는 t=0(대기/게이트 전)에서만 그린다 — 전환이 시작되면 같은
        // 위치(0.48×창)의 GL 링이 이어받으므로 외곽선이 두 개로 겹쳐 보이지 않는다.
        DeviceAspectBox(
            aspect = screenAspect,
            growth = 0f,
            modifier = Modifier.align(Alignment.Center).alpha(contentAlpha),
            border = if (contentAlpha >= 1f) {
                Modifier.border(BORDER_WIDTH_DP, scheme.primary, RoundedCornerShape(cornerRadius))
            } else {
                Modifier
            },
        ) {
            Column(
                Modifier
                    .padding(horizontal = 28.dp, vertical = contentVerticalPadding),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // AirPlay 아이콘 (상단)
                Icon(
                    imageVector = MirrorIcon,
                    contentDescription = null,
                    tint = scheme.onBackground,
                    modifier = Modifier.size(44.dp),
                )
                // "Airoid [코드]" — 코드는 코드박스 스타일로
                Row(
                    Modifier.padding(top = 18.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = stringResource(R.string.app_name),
                        color = scheme.onBackground,
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.Bold
                    )
                    CodeBox(
                        code = code,
                        modifier = Modifier.padding(start = 10.dp),
                    )
                }
            }
        }
    }
}

/**
 * 코드를 "코드"임을 알아볼 수 있게 감싸는 코드박스.
 * 어두운 표면(surfaceVariant) + 고정폭 글꼴 + 둥근 모서리.
 */
@Composable
private fun CodeBox(code: String, modifier: Modifier = Modifier) {
    val scheme = MaterialTheme.colorScheme
    Box(
        modifier
            .background(scheme.surfaceVariant, RoundedCornerShape(8.dp))
            .padding(horizontal = 12.dp, vertical = 6.dp)
    ) {
        Text(
            text = code,
            color = scheme.onSurfaceVariant,
            style = MaterialTheme.typography.headlineSmall.copy(lineHeight = 32.sp),
            fontWeight = FontWeight.SemiBold,
            fontFamily = FontFamily.Monospace,
            maxLines = 1,
        )
    }
}

/**
 * 기기 화면 비율을 유지하는 박스. growth(0..1): 최소 크기 ↔ 화면 BOX_MAX_FRACTION배 사이를
 * 크기 자체로 보간한다. 크기 레이아웃으로 애니메이션하므로(래스터 스케일 아님)
 * 외곽선이 항상 또렷하고, 최대 크기에서 외곽선이 화면 밖으로 나간다.
 * 크기는 requiredSize로 부모 제약을 무시한다 — 부모(fillMaxSize)가 tight 제약이라
 * size()는 1.0배에서 잘려 외곽선이 화면 끝에 멈추게 된다.
 * 기준은 BoxWithConstraints.maxWidth(실측 창 폭) — 영상 GL 스케일(창 기준)과 일치시킨다.
 * SubcomposeLayout/placeWithLayer(레이어)를 쓰지 않는다 — 같은 화면의
 * SurfaceView 합성이 깨지지 않도록. 내용은 고정 크기로 중앙에 유지된다.
 * BOX_MIN_FRACTION/BOX_MAX_FRACTION은 영상 전환 스케일(MainActivity)과 공유된다.
 */
@Composable
private fun DeviceAspectBox(
    aspect: Float,
    growth: Float,
    modifier: Modifier = Modifier,
    border: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    BoxWithConstraints(modifier, contentAlignment = Alignment.Center) {
        // 최소 크기: 창 폭의 BOX_MIN_FRACTION배 (아이콘+타이틀+코드가 들어가는 기기 프레임 크기)
        val minW = maxWidth * BOX_MIN_FRACTION
        // 최대 크기: 창 폭의 BOX_MAX_FRACTION배 — 외곽선이 화면 밖으로 나간다.
        val maxW = maxWidth * BOX_MAX_FRACTION
        val width = minW + (maxW - minW) * growth
        val height = width / aspect
        Box(
            Modifier
                .requiredSize(width, height)
                .then(border),
            contentAlignment = Alignment.Center,
        ) {
            content()
        }
    }
}

/**
 * 기기 디스플레이 코너 반경(px)을 dp로 변환한다.
 * 1) API 31+: RoundedCorner 인셋(가장 정확한 현재 표시 설정 값)
 * 2) 폴백: 시스템 리소스 "rounded_corner_radius" (일부 OEM만 정의, 삼성은 0)
 * 3) 최종 폴백: 24.dp
 */
@Composable
internal fun deviceCornerRadiusDp(): Dp {
    val context = LocalContext.current
    val density = LocalDensity.current
    var radiusPx = 0
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        val wm = context.getSystemService(Context.WINDOW_SERVICE) as? WindowManager
        radiusPx = wm?.maximumWindowMetrics?.windowInsets
            ?.getRoundedCorner(RoundedCorner.POSITION_TOP_LEFT)?.radius ?: 0
    }
    if (radiusPx <= 0) {
        val res = Resources.getSystem()
        val id = res.getIdentifier("rounded_corner_radius", "dimen", "android")
        radiusPx = if (id != 0) {
            runCatching { res.getDimensionPixelSize(id) }.getOrDefault(0)
        } else 0
    }
    return if (radiusPx > 0) with(density) { radiusPx.toDp() } else 24.dp
}

/** 전환 박스 최소 크기(화면 폭 대비 비율). 영상 전환 스케일(MainActivity)과 공유. */
internal const val BOX_MIN_FRACTION = 0.48f

/** 전환 박스 최대 크기(화면 폭 대비 비율): 1.1배 — 외곽선이 화면 밖으로 나간다. */
internal const val BOX_MAX_FRACTION = 1.1f

/** 기기 프레임 외곽선 두께 (스탠바이 박스 + GL 링 공용). */
internal val BORDER_WIDTH_DP = 4.dp

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
 * 시트는 항상 컴포즈되어 있고, fraction(0=숨김, 1=표시)에 따라 아래로 이동한다.
 * 위로 스와이프하면 손가락을 1:1로 따라 올라오고, 놓으면 스프링으로 settle된다.
 * 스크림 탭이나 아래로 스와이프(임계값 초과)로 닫힌다.
 * 패널은 별도 창(popup)이 아니라 같은 창 안에 그린다 — 팝업 창이 열릴 때
 * 시스템 표시줄이 나타나는 것을 막기 위함이다.
 */
@Composable
fun DisplayOptionsLayer(
    mirroring: Boolean,
    onDisconnectMirroring: () -> Unit,
    content: @Composable () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    val scope = rememberCoroutineScope()
    // 회전(액티비티 재생성) 후에도 시트 상태 유지: 값은 저장 가능 상태로 보관
    var savedFraction by rememberSaveable { mutableFloatStateOf(0f) }
    val fraction = remember { Animatable(savedFraction) }
    var sheetHeightPx by remember { mutableStateOf(0) }

    // 미러링 종료 시 시트가 자연스럽게(아래로) 닫힌다 — 다음 연결에서 열린 채 남지 않게 한다.
    LaunchedEffect(mirroring) {
        if (!mirroring && fraction.value > 0f) {
            fraction.animateTo(0f)
            savedFraction = 0f
        }
    }

    Box(Modifier.fillMaxSize()) {
        content()

        // 종료 애니메이션 동안(닫히는 중)에도 시트를 유지한다
        if (mirroring || fraction.value > 0f) {
            // 상호작용 레이어: 스크림 + 탭 닫기 + 전체 스와이프로 열기/추적
            Box(
                Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.5f * fraction.value))
                    .pointerInput(Unit) {
                        detectTapGestures {
                            scope.launch {
                                fraction.animateTo(0f)
                                savedFraction = 0f
                            }
                        }
                    }
                    .pointerInput(Unit) {
                        detectVerticalDragGestures(
                            onVerticalDrag = { _, dragAmount ->
                                // 위로 드래그(음수) → fraction 증가 (시트가 손을 따라 올라옴)
                                val delta = -dragAmount / sheetHeightPx.coerceAtLeast(1).toFloat()
                                scope.launch {
                                    fraction.snapTo((fraction.value + delta).coerceIn(0f, 1f))
                                    savedFraction = fraction.value
                                }
                            },
                            onDragEnd = {
                                scope.launch {
                                    fraction.animateTo(if (fraction.value > 0.35f) 1f else 0f)
                                    savedFraction = fraction.value
                                }
                            },
                            onDragCancel = {
                                scope.launch {
                                    fraction.animateTo(0f)
                                    savedFraction = 0f
                                }
                            },
                        )
                    }
            )

            // 시트 컨테이너: 하단 정렬 + 가로를 채우되 내용(시트)은 폭 제한 + 중앙 정렬.
            // Surface에 fillMaxWidth를 쓰지 않아 widthIn cap이 반드시 적용된다.
            Box(
                Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .onSizeChanged { sheetHeightPx = it.height }
                    .offset { IntOffset(0, ((1f - fraction.value) * sheetHeightPx).roundToInt()) }
                    .navigationBarsPadding(),
                contentAlignment = Alignment.BottomCenter,
            ) {
                Surface(
                    modifier = Modifier
                        .widthIn(max = 400.dp)
                        .pointerInput(Unit) {
                            detectVerticalDragGestures(
                                onVerticalDrag = { _, dragAmount ->
                                    val delta = -dragAmount / sheetHeightPx.coerceAtLeast(1).toFloat()
                                    scope.launch {
                                        fraction.snapTo((fraction.value + delta).coerceIn(0f, 1f))
                                        savedFraction = fraction.value
                                    }
                                },
                                onDragEnd = {
                                    scope.launch {
                                        fraction.animateTo(if (fraction.value > 0.35f) 1f else 0f)
                                        savedFraction = fraction.value
                                    }
                                },
                                onDragCancel = {
                                    scope.launch {
                                        fraction.animateTo(1f)
                                        savedFraction = 1f
                                    }
                                },
                            )
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
                            text = stringResource(R.string.app_name),
                            style = MaterialTheme.typography.titleLarge,
                            textAlign = TextAlign.Center,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(bottom = 4.dp)
                        )
                        Button(
                            onClick = onDisconnectMirroring,
                            colors = ButtonDefaults.buttonColors(
                                containerColor = scheme.error,
                                contentColor = scheme.onError,
                            ),
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
