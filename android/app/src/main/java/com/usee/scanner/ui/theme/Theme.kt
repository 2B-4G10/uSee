package com.usee.scanner.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

object Palette {
    val Bg = Color(0xFF070B14)
    val BgRaised = Color(0xFF0C1322)
    val Surface = Color(0xFF111A2D)
    val SurfaceHigh = Color(0xFF17223A)
    val Stroke = Color(0x1FFFFFFF)
    val Text = Color(0xFFE9F1FF)
    val TextDim = Color(0xFF8C9AB8)
    val TextFaint = Color(0xFF5B6884)
    val Cyan = Color(0xFF2EE6D6)
    val Violet = Color(0xFF7C5CFF)
    val Blue = Color(0xFF3D8BFF)
    val Green = Color(0xFF3DDC97)
    val Amber = Color(0xFFFFB547)
    val Coral = Color(0xFFFF5C7A)

    val accentGradient = Brush.linearGradient(listOf(Cyan, Violet))
    val backdrop = Brush.verticalGradient(listOf(Color(0xFF0B1426), Bg, Bg))

    /** Signal colour ramp: weak (coral) → strong (cyan). */
    fun signal(rssi: Int): Color = when {
        rssi >= -55 -> Cyan
        rssi >= -67 -> Green
        rssi >= -78 -> Amber
        else -> Coral
    }

    /** Motion colour ramp keyed on the normalised score (≈1 is the noise floor). */
    fun motion(score: Double): Color = when {
        score < 1.35 -> Cyan
        score < 1.9 -> Green
        score < 3.0 -> Amber
        else -> Coral
    }
}

private val scheme = darkColorScheme(
    primary = Palette.Cyan,
    onPrimary = Color(0xFF00201D),
    secondary = Palette.Violet,
    onSecondary = Color.White,
    tertiary = Palette.Amber,
    background = Palette.Bg,
    onBackground = Palette.Text,
    surface = Palette.Surface,
    onSurface = Palette.Text,
    surfaceVariant = Palette.SurfaceHigh,
    onSurfaceVariant = Palette.TextDim,
    surfaceContainer = Palette.BgRaised,
    surfaceContainerHigh = Palette.SurfaceHigh,
    surfaceContainerHighest = Palette.SurfaceHigh,
    outline = Palette.Stroke,
    outlineVariant = Palette.Stroke,
    error = Palette.Coral,
)

val Mono = FontFamily.Monospace

private val type = Typography().let { t ->
    t.copy(
        displaySmall = t.displaySmall.copy(fontWeight = FontWeight.SemiBold, letterSpacing = (-0.5).sp),
        headlineMedium = t.headlineMedium.copy(fontWeight = FontWeight.SemiBold, letterSpacing = (-0.3).sp),
        headlineSmall = t.headlineSmall.copy(fontWeight = FontWeight.SemiBold),
        titleLarge = t.titleLarge.copy(fontWeight = FontWeight.SemiBold),
        titleMedium = t.titleMedium.copy(fontWeight = FontWeight.SemiBold),
        labelSmall = t.labelSmall.copy(letterSpacing = 0.8.sp),
    )
}

val NumberStyle = TextStyle(fontFamily = Mono, fontWeight = FontWeight.Medium)

@Composable
fun USeeTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = scheme, typography = type, content = content)
}
