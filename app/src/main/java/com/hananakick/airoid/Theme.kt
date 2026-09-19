package com.airoid

import android.graphics.Color as AndroidColor
import android.os.Build
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext

/** API 31 미만에서 동적 색상을 쓸 수 없을 때 사용하는 기본 시드. */
private val DEFAULT_SEED = Color(0xFF2E6BFF)

/**
 * 앱 테마. 수신기 UI 특성상 항상 다크 스킴을 사용한다.
 * 색상은 따로 지정하지 않고 시스템 배경화면 기반 동적 색상(API 31+)을 자동으로 가져온다.
 */
@Composable
fun AiroidTheme(content: @Composable () -> Unit) {
    val context = LocalContext.current
    val colorScheme = remember(context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            dynamicDarkColorScheme(context)
        } else {
            buildDarkScheme(DEFAULT_SEED)
        }
    }
    MaterialTheme(colorScheme = colorScheme, content = content)
}

/** M3 톤(tone 0..100)을 HSV로 근사해 만든다. 검정↔시드↔흰색 보간 + 고톤에서 채도 감소. */
private fun tonalColor(hue: Float, saturation: Float, value: Float, tone: Int): Color {
    val v = when {
        tone <= 0 -> 0f
        tone >= 100 -> 1f
        tone <= 50 -> (tone / 50f) * value
        else -> 1f - ((tone - 50) / 50f) * (1f - value)
    }
    val s = when {
        tone <= 50 -> saturation
        else -> saturation * (1f - ((tone - 50) / 50f) * 0.6f)
    }
    return Color.hsv(hue, s, v)
}

private fun buildDarkScheme(seed: Color): ColorScheme {
    val hsv = FloatArray(3)
    AndroidColor.colorToHSV(seed.toArgb(), hsv)
    val h = hsv[0]; val s = hsv[1]; val v = hsv[2]
    fun p(tone: Int) = tonalColor(h, s, v, tone)
    fun sec(tone: Int) = tonalColor((h + 30) % 360f, s * 0.55f, v, tone)
    fun ter(tone: Int) = tonalColor((h + 330) % 360f, s * 0.4f, v, tone)
    return darkColorScheme(
        primary = p(80), onPrimary = p(20),
        primaryContainer = p(30), onPrimaryContainer = p(90),
        secondary = sec(80), onSecondary = sec(20),
        secondaryContainer = sec(30), onSecondaryContainer = sec(90),
        tertiary = ter(80), onTertiary = ter(20),
        tertiaryContainer = ter(30), onTertiaryContainer = ter(90),
        background = p(6), onBackground = p(90),
        surface = p(6), onSurface = p(90),
        surfaceDim = p(6), surfaceBright = p(24),
        surfaceContainerLowest = p(4), surfaceContainerLow = p(10),
        surfaceContainer = p(12), surfaceContainerHigh = p(17), surfaceContainerHighest = p(22),
        surfaceVariant = p(30), onSurfaceVariant = p(80),
        outline = p(60), outlineVariant = p(30),
        inverseSurface = p(90), inverseOnSurface = p(20), inversePrimary = p(40),
        surfaceTint = p(80),
    )
}
