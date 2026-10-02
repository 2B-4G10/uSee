package com.usee.scanner.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.usee.scanner.ui.theme.NumberStyle
import com.usee.scanner.ui.theme.Palette

val CardShape = RoundedCornerShape(22.dp)

@Composable
fun GlassCard(
    modifier: Modifier = Modifier,
    padding: Dp = 18.dp,
    glow: Color? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val bg = Brush.verticalGradient(
        listOf(
            (glow ?: Palette.Violet).copy(alpha = if (glow != null) 0.10f else 0.04f),
            Palette.Surface.copy(alpha = 0.92f),
        ),
    )
    Column(
        modifier
            .fillMaxWidth()
            .clip(CardShape)
            .background(bg)
            .border(1.dp, glow?.copy(alpha = 0.28f) ?: Palette.Stroke, CardShape)
            .padding(padding),
        content = content,
    )
}

@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier, trailing: @Composable RowScope.() -> Unit = {}) {
    Row(modifier.fillMaxWidth().padding(bottom = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(
            text.uppercase(),
            style = MaterialTheme.typography.labelSmall,
            color = Palette.TextDim,
            modifier = Modifier.weight(1f),
        )
        trailing()
    }
}

@Composable
fun Pill(
    text: String,
    color: Color = Palette.Cyan,
    icon: ImageVector? = null,
    modifier: Modifier = Modifier,
    filled: Boolean = false,
) {
    Row(
        modifier
            .clip(RoundedCornerShape(50))
            .background(color.copy(alpha = if (filled) 0.22f else 0.10f))
            .border(1.dp, color.copy(alpha = 0.35f), RoundedCornerShape(50))
            .padding(horizontal = 10.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Icon(icon, null, tint = color, modifier = Modifier.size(14.dp))
            Spacer(Modifier.width(5.dp))
        }
        Text(text, color = color, fontSize = 12.sp, fontWeight = FontWeight.Medium, maxLines = 1)
    }
}

@Composable
fun LiveDot(color: Color, modifier: Modifier = Modifier) {
    Box(modifier.size(8.dp).clip(CircleShape).background(color))
}

@Composable
fun StatTile(label: String, value: String, modifier: Modifier = Modifier, unit: String? = null, color: Color = Palette.Text) {
    Column(
        modifier
            .clip(RoundedCornerShape(16.dp))
            .background(Palette.BgRaised.copy(alpha = 0.7f))
            .border(1.dp, Palette.Stroke, RoundedCornerShape(16.dp))
            .padding(horizontal = 14.dp, vertical = 12.dp),
    ) {
        Text(label, style = MaterialTheme.typography.labelSmall, color = Palette.TextDim, maxLines = 1)
        Spacer(Modifier.height(4.dp))
        Row(verticalAlignment = Alignment.Bottom) {
            Text(value, style = NumberStyle, fontSize = 20.sp, color = color, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (unit != null) {
                Spacer(Modifier.width(3.dp))
                Text(unit, color = Palette.TextDim, fontSize = 11.sp, modifier = Modifier.padding(bottom = 3.dp))
            }
        }
    }
}

@Composable
fun KeyValue(key: String, value: String, valueColor: Color = Palette.Text) {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(key, color = Palette.TextDim, fontSize = 13.sp)
        Spacer(Modifier.width(12.dp))
        Text(value, color = valueColor, fontSize = 13.sp, style = NumberStyle, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}

/** Evidence tag required by the project policy for any accuracy-like statement. */
@Composable
fun EvidenceNote(text: String, modifier: Modifier = Modifier) {
    Row(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(Palette.Amber.copy(alpha = 0.07f))
            .border(1.dp, Palette.Amber.copy(alpha = 0.22f), RoundedCornerShape(14.dp))
            .padding(12.dp),
    ) {
        Text(text, color = Palette.Amber.copy(alpha = 0.9f), fontSize = 12.sp, lineHeight = 17.sp)
    }
}

@Composable
fun SignalBars(rssi: Int, modifier: Modifier = Modifier) {
    val level = when {
        rssi >= -55 -> 4
        rssi >= -67 -> 3
        rssi >= -78 -> 2
        rssi >= -88 -> 1
        else -> 0
    }
    val c = Palette.signal(rssi)
    Row(modifier.height(16.dp), verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
        for (i in 1..4) {
            Box(
                Modifier
                    .width(4.dp)
                    .height((4 + i * 3).dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(if (i <= level) c else Palette.Stroke),
            )
        }
    }
}
