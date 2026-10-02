package com.usee.scanner.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Air
import androidx.compose.material.icons.rounded.Dns
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.Memory
import androidx.compose.material.icons.rounded.PhoneAndroid
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.usee.scanner.core.MotionLevel
import com.usee.scanner.data.SensingState
import com.usee.scanner.data.SensorKind
import com.usee.scanner.ui.components.CsiWaterfall
import com.usee.scanner.ui.components.EvidenceNote
import com.usee.scanner.ui.components.GlassCard
import com.usee.scanner.ui.components.LiveDot
import com.usee.scanner.ui.components.Pill
import com.usee.scanner.ui.components.RadarView
import com.usee.scanner.ui.components.SectionLabel
import com.usee.scanner.ui.components.Sparkline
import com.usee.scanner.ui.components.StatTile
import com.usee.scanner.ui.theme.NumberStyle
import com.usee.scanner.ui.theme.Palette

fun SensorKind.icon(): ImageVector = when (this) {
    SensorKind.SERVER -> Icons.Rounded.Dns
    SensorKind.ESP32 -> Icons.Rounded.Memory
    SensorKind.PHONE -> Icons.Rounded.PhoneAndroid
}

fun SensorKind.color() = when (this) {
    SensorKind.SERVER -> Palette.Violet
    SensorKind.ESP32 -> Palette.Green
    SensorKind.PHONE -> Palette.Cyan
}

@Composable
fun SenseScreen(state: SensingState, onRecalibrate: () -> Unit) {
    val color = if (state.motion.level == MotionLevel.CALIBRATING) Palette.TextDim else Palette.motion(state.motion.score)
    val intensity by animateFloatAsState(
        ((state.motion.score - 0.8) / 3.5).toFloat().coerceIn(0f, 1f),
        tween(700),
        label = "intensity",
    )

    ScreenColumn {
        // Active sensor banner
        GlassCard(glow = state.active.color(), padding = 14.dp) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(state.active.icon(), null, tint = state.active.color(), modifier = Modifier.size(26.dp))
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        LiveDot(state.active.color())
                        Spacer(Modifier.width(6.dp))
                        Text("SCANNING WITH", style = MaterialTheme.typography.labelSmall, color = Palette.TextDim)
                    }
                    Text(state.active.title, style = MaterialTheme.typography.titleMedium, color = Palette.Text)
                    Text(state.reason, color = Palette.TextDim, fontSize = 12.sp, maxLines = 2)
                }
                Pill(state.active.tier, state.active.color())
            }
        }

        // Hero radar
        RadarView(
            color = color,
            intensity = intensity,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 18.dp),
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    state.headline,
                    style = MaterialTheme.typography.headlineSmall,
                    color = Palette.Text,
                    textAlign = TextAlign.Center,
                )
                Spacer(Modifier.height(6.dp))
                Pill(
                    if (state.motion.level == MotionLevel.CALIBRATING) "Keep still · learning noise floor"
                    else "Motion · ${state.motion.level.label}",
                    color,
                    filled = true,
                )
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            StatTile("SCORE", "%.2f".format(state.motion.score), Modifier.weight(1f), color = color)
            StatTile("CONFIDENCE", "${(state.confidence * 100).toInt()}", Modifier.weight(1f), unit = "%")
            StatTile(
                "UPDATES",
                if (state.updateRateHz > 0) "%.1f".format(state.updateRateHz) else "—",
                Modifier.weight(1f),
                unit = "Hz",
            )
        }

        GlassCard {
            SectionLabel("Motion · last 60 s") {
                Text("noise floor ┄", color = Palette.TextFaint, fontSize = 11.sp)
            }
            Sparkline(state.history, color)
        }

        AnimatedVisibility(state.vitals != null && (state.vitals.breathingBpm != null || state.vitals.heartRateBpm != null || state.vitals.persons != null)) {
            val v = state.vitals
            GlassCard(glow = Palette.Coral) {
                SectionLabel("Vital signs") { Pill("Sensor estimate", Palette.Amber) }
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    VitalTile(Icons.Rounded.Air, "Breathing", v?.breathingBpm?.let { "%.0f".format(it) } ?: "—", "bpm", Modifier.weight(1f))
                    VitalTile(Icons.Rounded.Favorite, "Heart", v?.heartRateBpm?.let { "%.0f".format(it) } ?: "—", "bpm", Modifier.weight(1f))
                }
                if (v?.persons != null) {
                    Spacer(Modifier.height(10.dp))
                    Text("Estimated people: ${v.persons}", color = Palette.TextDim, fontSize = 13.sp)
                }
            }
        }

        AnimatedVisibility(state.active == SensorKind.ESP32 && state.csiWaterfall.isNotEmpty()) {
            GlassCard(glow = Palette.Green) {
                SectionLabel("CSI amplitude · subcarrier × time") {
                    Pill("Live", Palette.Green)
                }
                CsiWaterfall(state.csiWaterfall)
            }
        }

        EvidenceNote(
            when (state.active) {
                SensorKind.PHONE ->
                    "RSSI-only mode. A phone exposes signal strength, not Channel State Information, so it can " +
                        "flag movement that disturbs the WiFi path but cannot see a person who is perfectly still, " +
                        "count people or estimate pose. Thresholds are heuristics (not measured accuracy). " +
                        "Connect an ESP32 node or a RuView server for CSI sensing."
                SensorKind.ESP32 ->
                    "CSI motion score is computed on this phone from raw amplitudes; vitals, when shown, come " +
                        "from the node's on-device estimator. Neither is a medical measurement or camera-grade."
                SensorKind.SERVER ->
                    "Presence, motion and vitals are reported by the RuView server pipeline as-is. WiFi sensing " +
                        "is probabilistic and not camera-grade."
            },
        )

        FilledTonalButton(onClick = onRecalibrate, modifier = Modifier.fillMaxWidth()) {
            Icon(Icons.Rounded.Refresh, null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text("Recalibrate (leave the room still)")
        }
    }
}

@Composable
private fun VitalTile(icon: ImageVector, label: String, value: String, unit: String, modifier: Modifier) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, tint = Palette.Coral, modifier = Modifier.size(28.dp))
        Spacer(Modifier.width(10.dp))
        Column {
            Text(label, color = Palette.TextDim, fontSize = 12.sp)
            Row(verticalAlignment = Alignment.Bottom) {
                Text(value, style = NumberStyle, fontSize = 26.sp, fontWeight = FontWeight.SemiBold, color = Palette.Text)
                Spacer(Modifier.width(4.dp))
                Text(unit, color = Palette.TextDim, fontSize = 12.sp, modifier = Modifier.padding(bottom = 4.dp))
            }
        }
    }
}
