package com.usee.scanner.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.LockOpen
import androidx.compose.material.icons.rounded.Memory
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Wifi
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.usee.scanner.core.Band
import com.usee.scanner.core.WifiMath
import com.usee.scanner.data.AccessPoint
import com.usee.scanner.data.ScanStatus
import com.usee.scanner.ui.components.CardShape
import com.usee.scanner.ui.components.ChannelSpectrum
import com.usee.scanner.ui.components.GlassCard
import com.usee.scanner.ui.components.KeyValue
import com.usee.scanner.ui.components.Pill
import com.usee.scanner.ui.components.SectionLabel
import com.usee.scanner.ui.components.SignalBars
import com.usee.scanner.ui.components.StatTile
import com.usee.scanner.ui.theme.NumberStyle
import com.usee.scanner.ui.theme.Palette
import java.text.DateFormat
import java.util.Date

private enum class BandFilter(val label: String, val band: Band?) {
    ALL("All", null), B24("2.4 GHz", Band.GHZ_2_4), B5("5 GHz", Band.GHZ_5), B6("6 GHz", Band.GHZ_6)
}

@Composable
fun ScanScreen(aps: List<AccessPoint>, status: ScanStatus, onRefresh: () -> Unit) {
    var filter by rememberSaveable { mutableStateOf(BandFilter.ALL) }
    val shown = remember(aps, filter) { aps.filter { filter.band == null || it.band == filter.band } }
    val spectrumBand = filter.band ?: Band.GHZ_2_4

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 20.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                StatTile("NETWORKS", "${aps.size}", Modifier.weight(1f), color = Palette.Cyan)
                StatTile("2.4 / 5 / 6", "${aps.count { it.band == Band.GHZ_2_4 }}/${aps.count { it.band == Band.GHZ_5 }}/${aps.count { it.band == Band.GHZ_6 }}", Modifier.weight(1.3f))
                StatTile("OPEN", "${aps.count { WifiMath.isOpen(it.security) }}", Modifier.weight(0.8f), color = if (aps.any { WifiMath.isOpen(it.security) }) Palette.Amber else Palette.Text)
            }
        }
        item {
            GlassCard {
                SectionLabel("Channel spectrum · ${spectrumBand.label}") {
                    IconButton(onClick = onRefresh, modifier = Modifier.size(32.dp)) {
                        Icon(Icons.Rounded.Refresh, "Scan now", tint = Palette.Cyan)
                    }
                }
                if (status.scanning) {
                    LinearProgressIndicator(Modifier.fillMaxWidth().height(2.dp), color = Palette.Cyan, trackColor = Palette.Stroke)
                    Spacer(Modifier.height(6.dp))
                }
                ChannelSpectrum(aps, spectrumBand)
                Spacer(Modifier.height(6.dp))
                Text(
                    buildString {
                        if (status.lastResultsMs > 0) append("Updated ${DateFormat.getTimeInstance(DateFormat.MEDIUM).format(Date(status.lastResultsMs))}")
                        status.message?.let { append(" · ").append(it) }
                    },
                    color = Palette.TextFaint,
                    fontSize = 11.sp,
                )
            }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                BandFilter.entries.forEach { f ->
                    FilterChip(
                        selected = filter == f,
                        onClick = { filter = f },
                        label = { Text(f.label) },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = Palette.Cyan.copy(alpha = 0.18f),
                            selectedLabelColor = Palette.Cyan,
                            labelColor = Palette.TextDim,
                        ),
                    )
                }
            }
        }
        if (shown.isEmpty()) {
            item {
                GlassCard {
                    Text(
                        if (aps.isEmpty()) "No networks yet. Android may throttle scans — results appear as soon as the OS delivers them."
                        else "No networks on this band.",
                        color = Palette.TextDim,
                    )
                }
            }
        }
        items(shown, key = { it.bssid }) { ap -> ApRow(ap) }
    }
}

@Composable
private fun ApRow(ap: AccessPoint) {
    var open by rememberSaveable(ap.bssid) { mutableStateOf(false) }
    val accent = if (ap.connected) Palette.Cyan else Palette.Stroke
    Column(
        Modifier
            .fillMaxWidth()
            .clip(CardShape)
            .background(Palette.Surface.copy(alpha = 0.92f))
            .border(1.dp, if (ap.connected) accent.copy(alpha = 0.5f) else accent, CardShape)
            .clickable { open = !open }
            .animateContentSize()
            .padding(14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(40.dp).clip(RoundedCornerShape(12.dp)).background(Palette.signal(ap.rssi).copy(alpha = 0.12f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(if (ap.sensorCandidate) Icons.Rounded.Memory else Icons.Rounded.Wifi, null, tint = Palette.signal(ap.rssi))
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        ap.ssid.ifEmpty { "Hidden network" },
                        style = MaterialTheme.typography.titleSmall,
                        color = if (ap.ssid.isEmpty()) Palette.TextDim else Palette.Text,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    Spacer(Modifier.width(6.dp))
                    Icon(
                        if (WifiMath.isOpen(ap.security)) Icons.Rounded.LockOpen else Icons.Rounded.Lock,
                        null,
                        tint = if (WifiMath.isOpen(ap.security)) Palette.Amber else Palette.TextFaint,
                        modifier = Modifier.size(13.dp),
                    )
                }
                Text(
                    "ch ${ap.channel} · ${ap.band.label} · ${ap.widthMhz} MHz · ${ap.security}",
                    color = Palette.TextDim,
                    fontSize = 12.sp,
                    maxLines = 1,
                )
            }
            Spacer(Modifier.width(8.dp))
            Column(horizontalAlignment = Alignment.End) {
                Text("${ap.rssi}", style = NumberStyle, color = Palette.signal(ap.rssi), fontSize = 18.sp)
                SignalBars(ap.rssi)
            }
        }
        if (ap.connected || ap.sensorCandidate) {
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                if (ap.connected) Pill("Connected", Palette.Cyan)
                if (ap.sensorCandidate) Pill(ap.vendorHint ?: "Sensor candidate", Palette.Green)
            }
        }
        AnimatedVisibility(open) {
            Column(Modifier.padding(top = 10.dp)) {
                KeyValue("BSSID", ap.bssid)
                KeyValue("Frequency", "${ap.frequencyMhz} MHz")
                KeyValue("Standard", ap.standard)
                KeyValue("Signal quality", "${WifiMath.qualityPercent(ap.rssi)} %")
                KeyValue("Distance (free-space est.)", if (ap.distanceMeters.isNaN()) "—" else "≈ %.1f m".format(ap.distanceMeters))
                ap.vendorHint?.let { KeyValue("Hardware hint", it) }
                Text(
                    "Distance ignores walls, antennas and transmit power — treat it as an order of magnitude.",
                    color = Palette.TextFaint,
                    fontSize = 11.sp,
                    modifier = Modifier.padding(top = 6.dp),
                )
            }
        }
    }
}
