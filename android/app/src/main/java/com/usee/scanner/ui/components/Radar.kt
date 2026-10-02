package com.usee.scanner.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * Animated radar used as the hero visual. [intensity] (0..1) drives ripple
 * amplitude and sweep speed so the screen visibly reacts to RF disturbance.
 * The blips are decorative; they do not represent located people.
 */
@Composable
fun RadarView(
    color: Color,
    intensity: Float,
    modifier: Modifier = Modifier,
    center: @Composable () -> Unit = {},
) {
    val tint by animateColorAsState(color, tween(600), label = "tint")
    val transition = rememberInfiniteTransition(label = "radar")
    val sweep by transition.animateFloat(
        0f, 360f,
        infiniteRepeatable(tween(4200, easing = LinearEasing)),
        label = "sweep",
    )
    val pulse by transition.animateFloat(
        0f, 1f,
        infiniteRepeatable(tween(2400, easing = LinearEasing)),
        label = "pulse",
    )
    val breathe by transition.animateFloat(
        0.92f, 1.0f,
        infiniteRepeatable(tween(1800), RepeatMode.Reverse),
        label = "breathe",
    )
    val k = intensity.coerceIn(0f, 1f)

    Box(modifier.aspectRatio(1f), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val c = Offset(size.width / 2, size.height / 2)
            val r = min(size.width, size.height) / 2 * 0.96f

            // Ambient glow
            drawCircle(
                Brush.radialGradient(listOf(tint.copy(alpha = 0.18f + 0.22f * k), Color.Transparent), c, r),
                r, c,
            )
            // Rings
            for (i in 1..4) {
                drawCircle(tint.copy(alpha = 0.10f + 0.04f * i), r * i / 4f, c, style = Stroke(1.2f))
            }
            // Cross-hair
            drawLine(tint.copy(alpha = 0.08f), Offset(c.x - r, c.y), Offset(c.x + r, c.y), 1f)
            drawLine(tint.copy(alpha = 0.08f), Offset(c.x, c.y - r), Offset(c.x, c.y + r), 1f)

            // Expanding RF ripples; stronger and more numerous with intensity.
            val ripples = 2 + (k * 3).toInt()
            for (i in 0 until ripples) {
                val p = (pulse + i.toFloat() / ripples) % 1f
                drawCircle(
                    tint.copy(alpha = (1f - p) * (0.25f + 0.45f * k)),
                    r * (0.15f + 0.85f * p),
                    c,
                    style = Stroke(1.5f + 3f * k * (1f - p)),
                )
            }

            // Sweep wedge
            rotate(sweep, c) {
                drawArc(
                    Brush.sweepGradient(
                        0f to Color.Transparent,
                        0.88f to Color.Transparent,
                        1f to tint.copy(alpha = 0.38f),
                        center = c,
                    ),
                    startAngle = 0f, sweepAngle = 360f, useCenter = true,
                    topLeft = Offset(c.x - r, c.y - r), size = androidx.compose.ui.geometry.Size(2 * r, 2 * r),
                )
                drawLine(tint.copy(alpha = 0.9f), c, Offset(c.x + r, c.y), 2.2f)
            }

            // Decorative blips whose brightness follows intensity.
            val seeds = floatArrayOf(0.3f, 1.4f, 2.6f, 3.9f, 5.1f)
            seeds.forEachIndexed { idx, a ->
                val rad = r * (0.35f + 0.12f * idx)
                val pos = Offset(c.x + cos(a) * rad, c.y + sin(a) * rad)
                drawCircle(tint.copy(alpha = 0.15f + 0.6f * k * breathe), 3f + 4f * k, pos)
            }

            // Core
            drawCircle(Brush.radialGradient(listOf(tint.copy(alpha = 0.35f), Color.Transparent), c, r * 0.3f * breathe), r * 0.3f, c)
        }
        center()
    }
}
