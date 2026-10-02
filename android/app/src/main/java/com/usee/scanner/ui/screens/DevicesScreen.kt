package com.usee.scanner.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Dns
import androidx.compose.material.icons.rounded.Memory
import androidx.compose.material.icons.rounded.PhoneAndroid
import androidx.compose.material.icons.rounded.RadioButtonUnchecked
import androidx.compose.material.icons.rounded.Radar
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.usee.scanner.data.ConnectedLink
import com.usee.scanner.data.Discovery
import com.usee.scanner.data.Esp32Node
import com.usee.scanner.data.PhoneProfile
import com.usee.scanner.data.RuViewServer
import com.usee.scanner.data.SensingState
import com.usee.scanner.data.SensorKind
import com.usee.scanner.data.ServerLink
import com.usee.scanner.data.SweepProgress
import com.usee.scanner.ui.components.EvidenceNote
import com.usee.scanner.ui.components.GlassCard
import com.usee.scanner.ui.components.KeyValue
import com.usee.scanner.ui.components.Pill
import com.usee.scanner.ui.components.SectionLabel
import com.usee.scanner.ui.theme.Mono
import com.usee.scanner.ui.theme.Palette

@Composable
fun DevicesScreen(
    sensing: SensingState,
    profile: PhoneProfile?,
    link: ConnectedLink?,
    nodes: List<Esp32Node>,
    servers: List<RuViewServer>,
    serverLink: ServerLink,
    sweep: SweepProgress,
    udpPort: Int,
    udpError: String?,
    onSweep: () -> Unit,
    onProbe: (String, (String) -> Unit) -> Unit,
    onConnect: (RuViewServer) -> Unit,
) {
    val now = System.currentTimeMillis()
    val liveNodes = nodes.filter { (it.streamsCsi || it.streamsVitals) && now - it.lastSeenMs < 5_000 }
    ScreenColumn {
        // Auto-detection summary
        GlassCard(glow = sensing.active.color()) {
            SectionLabel("Auto-detected scanning tool")
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier.size(52.dp).clip(RoundedCornerShape(16.dp)).background(sensing.active.color().copy(alpha = 0.15f)),
                    contentAlignment = Alignment.Center,
                ) { Icon(sensing.active.icon(), null, tint = sensing.active.color(), modifier = Modifier.size(30.dp)) }
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text(sensing.active.title, style = MaterialTheme.typography.titleLarge, color = Palette.Text)
                    Text(sensing.active.detail, color = sensing.active.color(), fontSize = 13.sp)
                }
            }
            Spacer(Modifier.height(14.dp))
            Candidate(Icons.Rounded.Dns, "RuView sensing server", when {
                serverLink.connected -> "Streaming"
                servers.isNotEmpty() -> "${servers.size} found"
                else -> "Not found"
            }, serverLink.connected, sensing.active == SensorKind.SERVER)
            Candidate(Icons.Rounded.Memory, "ESP32 CSI node", when {
                liveNodes.isNotEmpty() -> "${liveNodes.size} streaming"
                nodes.any { Discovery.HTTP in it.discovery } -> "Found, not streaming here"
                nodes.isNotEmpty() -> "Espressif radio nearby"
                else -> "Not found"
            }, liveNodes.isNotEmpty(), sensing.active == SensorKind.ESP32)
            Candidate(Icons.Rounded.PhoneAndroid, "Phone WiFi radio", if (profile?.wifiEnabled == false) "WiFi off" else "Ready", profile?.wifiEnabled != false, sensing.active == SensorKind.PHONE)
            Spacer(Modifier.height(6.dp))
            Text("Priority: server → ESP32 → phone. Override in Settings.", color = Palette.TextFaint, fontSize = 11.sp)
        }

        // Discovery controls
        GlassCard {
            SectionLabel("Network discovery")
            if (sweep.running) {
                LinearProgressIndicator(
                    progress = { if (sweep.total == 0) 0f else sweep.done.toFloat() / sweep.total },
                    modifier = Modifier.fillMaxWidth(),
                    color = Palette.Cyan,
                    trackColor = Palette.Stroke,
                )
                Spacer(Modifier.height(6.dp))
                Text("Probing ${sweep.done}/${sweep.total} hosts (read-only)…", color = Palette.TextDim, fontSize = 12.sp)
            } else {
                Text(
                    sweep.message ?: if (sweep.total > 0) "Last sweep checked ${sweep.total} hosts." else "mDNS browsing for _ruview._tcp is always on.",
                    color = Palette.TextDim,
                    fontSize = 12.sp,
                )
            }
            Spacer(Modifier.height(10.dp))
            Button(
                onClick = onSweep,
                enabled = !sweep.running,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(containerColor = Palette.Cyan, contentColor = Color(0xFF00201D)),
            ) {
                Icon(Icons.Rounded.Radar, null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("Sweep local network")
            }
            Spacer(Modifier.height(12.dp))
            ManualProbe(onProbe)
        }

        // Servers
        if (servers.isNotEmpty() || serverLink.target != null) {
            GlassCard(glow = Palette.Violet) {
                SectionLabel("RuView servers")
                serverLink.target?.let { t ->
                    KeyValue("Stream", t.removePrefix("ws://"))
                    KeyValue("State", if (serverLink.connected) "Connected" else serverLink.error ?: "Connecting…",
                        if (serverLink.connected) Palette.Green else Palette.Amber)
                    Spacer(Modifier.height(8.dp))
                }
                servers.forEach { s -> ServerRow(s, serverLink, onConnect) }
            }
        }

        // ESP32 nodes
        GlassCard(glow = if (liveNodes.isNotEmpty()) Palette.Green else null) {
            SectionLabel("ESP32 nodes") { Pill("UDP :$udpPort", Palette.Green) }
            if (udpError != null) {
                Text(udpError, color = Palette.Coral, fontSize = 12.sp)
                Spacer(Modifier.height(6.dp))
            }
            if (nodes.isEmpty()) {
                Text("No ESP32 nodes detected yet.", color = Palette.TextDim, fontSize = 13.sp)
            }
            nodes.forEach { NodeRow(it, now) }
            Spacer(Modifier.height(10.dp))
            Text("Stream a node to this phone", style = MaterialTheme.typography.titleSmall, color = Palette.Text)
            Spacer(Modifier.height(4.dp))
            val ip = link?.ipv4 ?: "<phone-ip>"
            Text(
                "python firmware/esp32-csi-node/provision.py --port <serial> --target-ip $ip --target-port $udpPort",
                fontFamily = Mono,
                fontSize = 11.sp,
                color = Palette.Cyan,
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .background(Palette.Bg)
                    .padding(10.dp),
            )
        }

        // Phone radio
        profile?.let { PhoneCard(it, link) }
    }
}

@Composable
private fun Candidate(icon: ImageVector, name: String, status: String, ok: Boolean, active: Boolean) {
    Row(Modifier.fillMaxWidth().padding(vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(
            if (ok) Icons.Rounded.CheckCircle else Icons.Rounded.RadioButtonUnchecked,
            null,
            tint = if (ok) Palette.Green else Palette.TextFaint,
            modifier = Modifier.size(18.dp),
        )
        Spacer(Modifier.width(10.dp))
        Icon(icon, null, tint = Palette.TextDim, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(8.dp))
        Text(name, color = Palette.Text, fontSize = 14.sp, modifier = Modifier.weight(1f))
        if (active) Pill("Active", Palette.Cyan, filled = true) else Text(status, color = Palette.TextDim, fontSize = 12.sp)
    }
}

@Composable
private fun ManualProbe(onProbe: (String, (String) -> Unit) -> Unit) {
    var host by rememberSaveable { mutableStateOf("") }
    var result by rememberSaveable { mutableStateOf<String?>(null) }
    var busy by rememberSaveable { mutableStateOf(false) }
    val submit = {
        if (host.isNotBlank() && !busy) {
            busy = true
            result = null
            onProbe(host) { r -> result = r; busy = false }
        }
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        OutlinedTextField(
            value = host,
            onValueChange = { host = it.take(253) },
            label = { Text("Host or IP") },
            singleLine = true,
            modifier = Modifier.weight(1f),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { submit() }),
        )
        Spacer(Modifier.width(8.dp))
        OutlinedButton(onClick = { submit() }, enabled = !busy) {
            Icon(Icons.Rounded.Search, "Probe", modifier = Modifier.size(18.dp))
        }
    }
    result?.let {
        Spacer(Modifier.height(6.dp))
        Text(it, color = Palette.TextDim, fontSize = 12.sp)
    }
}

@Composable
private fun ServerRow(s: RuViewServer, link: ServerLink, onConnect: (RuViewServer) -> Unit) {
    val current = link.target?.contains("${s.host}:${s.wsPort}") == true
    Column(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(s.name ?: s.host, color = Palette.Text, style = MaterialTheme.typography.titleSmall)
                Text(
                    "${s.host} · ws :${s.wsPort}${s.httpPort?.let { " · http :$it" } ?: ""}${s.source?.let { " · source $it" } ?: ""}",
                    color = Palette.TextDim,
                    fontSize = 12.sp,
                )
            }
            if (!current) {
                OutlinedButton(onClick = { onConnect(s) }) { Text("Use") }
            } else {
                Pill(if (link.connected) "Live" else "Linking", if (link.connected) Palette.Green else Palette.Amber)
            }
        }
        if (s.hostRejected) {
            Text(
                "Server refused this address (DNS-rebinding guard). Start it with --allowed-host ${s.host}",
                color = Palette.Amber,
                fontSize = 12.sp,
            )
        }
        Text("Found via ${s.discovery.joinToString { it.label }}", color = Palette.TextFaint, fontSize = 11.sp)
    }
}

@Composable
private fun NodeRow(n: Esp32Node, now: Long) {
    val live = (n.streamsCsi || n.streamsVitals) && now - n.lastSeenMs < 5_000
    Column(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.Memory, null, tint = if (live) Palette.Green else Palette.TextDim, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    when {
                        n.nodeId != null -> "Node #${n.nodeId}"
                        n.ssid != null -> n.ssid.ifEmpty { "Hidden soft-AP" }
                        else -> "ESP32 node"
                    },
                    color = Palette.Text,
                    style = MaterialTheme.typography.titleSmall,
                )
                Text(
                    listOfNotNull(
                        n.ip,
                        n.bssid,
                        n.firmware?.let { "fw $it" },
                        n.subcarriers?.let { "$it sc" },
                        n.channelFreqMhz?.takeIf { it > 0 }?.let { "$it MHz" },
                        n.rssi?.let { "$it dBm" },
                    ).joinToString(" · "),
                    color = Palette.TextDim,
                    fontSize = 12.sp,
                )
            }
            when {
                live && n.streamsCsi -> Pill("CSI %.0f/s".format(n.csiRateHz), Palette.Green, filled = true)
                live -> Pill("Vitals", Palette.Green, filled = true)
                Discovery.SOFTAP in n.discovery -> Pill("Beacon", Palette.TextDim)
                else -> Pill("Idle", Palette.Amber)
            }
        }
        if (Discovery.SOFTAP in n.discovery && n.discovery.size == 1) {
            Text(
                "Espressif hardware detected by its MAC/SSID. It may be a RuView node or any ESP-based device.",
                color = Palette.TextFaint,
                fontSize = 11.sp,
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PhoneCard(p: PhoneProfile, link: ConnectedLink?) {
    GlassCard(glow = Palette.Cyan) {
        SectionLabel("This phone")
        Text("${p.manufacturer} ${p.model}", style = MaterialTheme.typography.titleLarge, color = Palette.Text)
        Text(
            listOfNotNull(p.soc, "Android ${p.androidVersion} (API ${p.sdkInt})").joinToString(" · "),
            color = Palette.TextDim,
            fontSize = 12.sp,
        )
        Spacer(Modifier.height(12.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Pill("2.4 GHz", Palette.Cyan)
            if (p.band5) Pill("5 GHz", Palette.Cyan)
            if (p.band6) Pill("6 GHz", Palette.Violet)
            if (p.band60) Pill("60 GHz", Palette.Violet)
            p.standards.forEach { Pill(it, Palette.Blue) }
            if (p.rtt) Pill("802.11mc RTT", Palette.Green)
            if (p.aware) Pill("WiFi Aware", Palette.Green)
            if (p.p2p) Pill("WiFi Direct", Palette.Green)
        }
        Spacer(Modifier.height(10.dp))
        link?.let { l ->
            KeyValue("Connected to", l.ssid ?: "hidden / no permission")
            KeyValue("Link", "${l.rssi} dBm · ${l.frequencyMhz} MHz · ${l.linkSpeedMbps} Mbps")
            l.standard?.let { KeyValue("Link standard", it) }
            l.ipv4?.let { KeyValue("IPv4", "$it/${l.prefixLength ?: "?"}") }
        }
        if (p.maxTxMbps != null || p.maxRxMbps != null) KeyValue("Max link tx/rx", "${p.maxTxMbps ?: "?"}/${p.maxRxMbps ?: "?"} Mbps")
        p.scanThrottled?.let { KeyValue("OS scan throttling", if (it) "On (4 scans / 2 min)" else "Off") }
        KeyValue("CSI access", "Not exposed by Android", Palette.Amber)
        Spacer(Modifier.height(8.dp))
        EvidenceNote(
            "Stock Android only reports RSSI per access point. Channel State Information needs dedicated " +
                "hardware such as a RuView ESP32 node. RTT, if supported, measures distance to RTT-capable APs only.",
        )
    }
}
