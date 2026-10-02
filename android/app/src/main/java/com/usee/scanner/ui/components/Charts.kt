package com.usee.scanner.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.unit.dp
import com.usee.scanner.core.Band
import com.usee.scanner.data.AccessPoint
import com.usee.scanner.ui.theme.Palette
import kotlin.math.max

/** Smoothed area sparkline with a dashed noise-floor reference at y = [reference]. */
@Composable
fun Sparkline(
    values: List<Float>,
    color: Color,
    modifier: Modifier = Modifier,
    maxValue: Float = 5f,
    reference: Float? = 1.35f,
) {
    Canvas(modifier.fillMaxWidth().height(96.dp)) {
        val w = size.width
        val h = size.height
        // grid
        for (i in 1..3) drawLine(Palette.Stroke, Offset(0f, h * i / 4f), Offset(w, h * i / 4f), 1f)
        if (reference != null) {
            val y = h - (reference / maxValue).coerceIn(0f, 1f) * h
            drawLine(
                Palette.TextFaint, Offset(0f, y), Offset(w, y), 1.2f,
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 8f)),
            )
        }
        if (values.size < 2) return@Canvas
        val step = w / (values.size - 1)
        val pts = values.mapIndexed { i, v -> Offset(i * step, h - (v / maxValue).coerceIn(0f, 1f) * h) }
        val line = Path().apply {
            moveTo(pts[0].x, pts[0].y)
            for (i in 1 until pts.size) {
                val p0 = pts[i - 1]
                val p1 = pts[i]
                val mx = (p0.x + p1.x) / 2
                cubicTo(mx, p0.y, mx, p1.y, p1.x, p1.y)
            }
        }
        val fill = Path().apply {
            addPath(line)
            lineTo(pts.last().x, h)
            lineTo(0f, h)
            close()
        }
        drawPath(fill, Brush.verticalGradient(listOf(color.copy(alpha = 0.35f), Color.Transparent)))
        drawPath(line, color, style = Stroke(2.5f))
        drawCircle(color, 5f, pts.last())
        drawCircle(color.copy(alpha = 0.3f), 11f, pts.last())
    }
}

/**
 * WiFi-analyser style spectrum: each AP is a lobe centred on its channel,
 * as wide as its channel bandwidth and as tall as its RSSI.
 */
@Composable
fun ChannelSpectrum(aps: List<AccessPoint>, band: Band, modifier: Modifier = Modifier) {
    val (minF, maxF, ticks) = remember(band) {
        when (band) {
            Band.GHZ_2_4 -> Triple(2397f, 2487f, listOf(1, 6, 11, 13).map { it to (2407 + it * 5) })
            Band.GHZ_6 -> Triple(5945f, 7125f, listOf(1, 37, 69, 101, 133, 165, 197, 229).map { it to (5950 + it * 5) })
            else -> Triple(5150f, 5895f, listOf(36, 52, 100, 116, 132, 149, 165).map { it to (5000 + it * 5) })
        }
    }
    val palette = listOf(Palette.Cyan, Palette.Violet, Palette.Green, Palette.Amber, Palette.Blue, Palette.Coral)
    Canvas(modifier.fillMaxWidth().height(190.dp)) {
        val w = size.width
        val h = size.height - 22f
        fun x(f: Float) = (f - minF) / (maxF - minF) * w
        fun y(rssi: Int) = h - ((rssi + 100).coerceIn(0, 70) / 70f) * h
        // dBm grid
        for (db in listOf(-90, -70, -50, -30)) {
            val yy = y(db)
            drawLine(Palette.Stroke, Offset(0f, yy), Offset(w, yy), 1f)
        }
        val paint = android.graphics.Paint().apply {
            isAntiAlias = true
            textSize = 26f
            color = android.graphics.Color.argb(150, 140, 154, 184)
        }
        for ((ch, f) in ticks) {
            val xx = x(f.toFloat())
            drawLine(Palette.Stroke, Offset(xx, h), Offset(xx, h + 6f), 1f)
            drawContext.canvas.nativeCanvas.drawText("$ch", xx - 8f, size.height - 2f, paint)
        }
        aps.filter { it.band == band }.sortedBy { it.rssi }.forEachIndexed { i, ap ->
            val col = if (ap.connected) Palette.Cyan else palette[(ap.bssid.hashCode() and 0x7fffffff) % palette.size]
            val half = max(ap.widthMhz, 20) / 2f
            // ScanResult.frequency is the primary channel; widen around it as an approximation.
            val cx = x(ap.frequencyMhz.toFloat())
            val left = x(ap.frequencyMhz - half)
            val right = x(ap.frequencyMhz + half)
            val top = y(ap.rssi)
            val path = Path().apply {
                moveTo(left, h)
                cubicTo(left + (cx - left) * 0.45f, top, cx - (cx - left) * 0.3f, top, cx, top)
                cubicTo(cx + (right - cx) * 0.3f, top, right - (right - cx) * 0.45f, top, right, h)
            }
            drawPath(path, Brush.verticalGradient(listOf(col.copy(alpha = 0.28f), col.copy(alpha = 0.02f)), startY = top, endY = h))
            drawPath(path, col.copy(alpha = if (ap.connected) 1f else 0.85f), style = Stroke(if (ap.connected) 3.5f else 2f))
            if (i >= aps.count { it.band == band } - 4 && ap.ssid.isNotEmpty()) {
                paint.color = android.graphics.Color.argb(220, 233, 241, 255)
                drawContext.canvas.nativeCanvas.drawText(ap.ssid.take(14), cx - 30f, top - 8f, paint)
                paint.color = android.graphics.Color.argb(150, 140, 154, 184)
            }
        }
    }
}

/** CSI amplitude waterfall: x = subcarrier, y = time (newest at bottom). */
@Composable
fun CsiWaterfall(rows: List<FloatArray>, modifier: Modifier = Modifier) {
    Canvas(modifier.fillMaxWidth().height(150.dp)) {
        if (rows.isEmpty()) return@Canvas
        val cols = rows.maxOf { it.size }.coerceAtLeast(1)
        val cw = size.width / cols
        val rh = size.height / rows.size
        rows.forEachIndexed { r, row ->
            for (k in row.indices) {
                val v = (row[k] / 2f).coerceIn(0f, 1f)
                drawRect(heat(v), Offset(k * cw, r * rh), Size(cw + 0.5f, rh + 0.5f))
            }
        }
    }
}

private fun heat(v: Float): Color {
    // dark navy → violet → cyan → white
    return when {
        v < 0.33f -> lerp(Color(0xFF0B1426), Palette.Violet, v / 0.33f)
        v < 0.66f -> lerp(Palette.Violet, Palette.Cyan, (v - 0.33f) / 0.33f)
        else -> lerp(Palette.Cyan, Color.White, (v - 0.66f) / 0.34f)
    }
}

private fun lerp(a: Color, b: Color, t: Float): Color {
    val k = t.coerceIn(0f, 1f)
    return Color(
        a.red + (b.red - a.red) * k,
        a.green + (b.green - a.green) * k,
        a.blue + (b.blue - a.blue) * k,
        1f,
    )
}
