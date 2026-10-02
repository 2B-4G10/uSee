package com.usee.scanner.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.usee.scanner.BuildConfig
import com.usee.scanner.core.NetUtil
import com.usee.scanner.data.AppSettings
import com.usee.scanner.data.SensorMode
import com.usee.scanner.ui.components.GlassCard
import com.usee.scanner.ui.components.KeyValue
import com.usee.scanner.ui.components.SectionLabel
import com.usee.scanner.ui.theme.Palette

@Composable
fun SettingsScreen(settings: AppSettings, onChange: ((AppSettings) -> AppSettings) -> Unit) {
    ScreenColumn {
        GlassCard {
            SectionLabel("Sensor source")
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                SensorMode.entries.forEachIndexed { i, m ->
                    SegmentedButton(
                        selected = settings.mode == m,
                        onClick = { onChange { it.copy(mode = m) } },
                        shape = SegmentedButtonDefaults.itemShape(i, SensorMode.entries.size),
                        colors = SegmentedButtonDefaults.colors(
                            activeContainerColor = Palette.Cyan.copy(alpha = 0.2f),
                            activeContentColor = Palette.Cyan,
                            inactiveContainerColor = Color.Transparent,
                            inactiveContentColor = Palette.TextDim,
                        ),
                    ) { Text(m.label, fontSize = 12.sp) }
                }
            }
            Spacer(Modifier.height(8.dp))
            Text(
                "Auto picks the best live source: RuView server, then an ESP32 streaming to this phone, then the phone's own radio.",
                color = Palette.TextFaint,
                fontSize = 12.sp,
            )
        }

        GlassCard {
            SectionLabel("Phone scanning")
            Text("WiFi scan interval: ${settings.scanIntervalSec} s", color = Palette.Text)
            Slider(
                value = settings.scanIntervalSec.toFloat(),
                onValueChange = { v -> onChange { it.copy(scanIntervalSec = v.toInt()) } },
                valueRange = 5f..120f,
                colors = SliderDefaults.colors(
                    thumbColor = Palette.Cyan,
                    activeTrackColor = Palette.Cyan,
                    inactiveTrackColor = Palette.SurfaceHigh,
                ),
            )
            Text(
                "Android allows 4 scans per 2 minutes unless \"WiFi scan throttling\" is disabled in Developer options. Faster requests are queued, never forced.",
                color = Palette.TextFaint,
                fontSize = 12.sp,
            )
            Spacer(Modifier.height(8.dp))
            ToggleRow("Sweep LAN for sensors on launch", settings.autoSweep) { v -> onChange { it.copy(autoSweep = v) } }
            ToggleRow("Keep screen on while sensing", settings.keepScreenOn) { v -> onChange { it.copy(keepScreenOn = v) } }
        }

        GlassCard {
            SectionLabel("ESP32 UDP")
            NumberField("Listen port", settings.udpPort, 1024..65535) { p -> onChange { it.copy(udpPort = p) } }
        }

        GlassCard {
            SectionLabel("RuView sensing server")
            var host by rememberSaveable(settings.serverHost) { mutableStateOf(settings.serverHost) }
            var token by rememberSaveable(settings.serverToken) { mutableStateOf(settings.serverToken) }
            val hostOk = host.isBlank() || NetUtil.isValidHost(host)
            OutlinedTextField(
                value = host,
                onValueChange = { host = it.take(253) },
                label = { Text("Host (blank = auto-discover)") },
                singleLine = true,
                isError = !hostOk,
                supportingText = { if (!hostOk) Text("Enter an IPv4 address or host name") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                modifier = Modifier.fillMaxWidth(),
            )
            NumberField("WebSocket port", settings.serverWsPort, 1..65535) { p -> onChange { it.copy(serverWsPort = p) } }
            Spacer(Modifier.height(6.dp))
            OutlinedTextField(
                value = token,
                onValueChange = { token = it.take(512) },
                label = { Text("Bearer token (optional)") },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                modifier = Modifier.fillMaxWidth(),
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = { host = ""; onChange { it.copy(serverHost = "") } }) { Text("Clear") }
                TextButton(
                    enabled = hostOk,
                    onClick = { onChange { it.copy(serverHost = host.trim(), serverToken = token.trim()) } },
                ) { Text("Save") }
            }
        }

        GlassCard {
            SectionLabel("About")
            Text("uSee", style = MaterialTheme.typography.headlineSmall, color = Palette.Text)
            Text("WiFi environment scanner & RF sensing companion", color = Palette.TextDim, fontSize = 13.sp)
            Spacer(Modifier.height(10.dp))
            KeyValue("Version", BuildConfig.VERSION_NAME)
            KeyValue("Author", "Faisal AlDossary")
            KeyValue("Sensing stack", "RuView (MIT)")
            Spacer(Modifier.height(6.dp))
            Text(
                "All network probes are read-only and limited to private subnets. No data leaves your local network.",
                color = Palette.TextFaint,
                fontSize = 12.sp,
            )
        }
    }
}

@Composable
private fun ToggleRow(label: String, value: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = Palette.Text, modifier = Modifier.weight(1f), fontSize = 14.sp)
        Spacer(Modifier.width(8.dp))
        Switch(
            checked = value,
            onCheckedChange = onChange,
            colors = SwitchDefaults.colors(checkedTrackColor = Palette.Cyan, checkedThumbColor = Color(0xFF00201D)),
        )
    }
}

@Composable
private fun NumberField(label: String, value: Int, range: IntRange, onCommit: (Int) -> Unit) {
    var text by rememberSaveable(value) { mutableStateOf(value.toString()) }
    val parsed = text.toIntOrNull()
    val ok = parsed != null && parsed in range
    Column {
        OutlinedTextField(
            value = text,
            onValueChange = { t ->
                text = t.filter(Char::isDigit).take(5)
                text.toIntOrNull()?.takeIf { it in range }?.let(onCommit)
            },
            label = { Text(label) },
            singleLine = true,
            isError = !ok,
            supportingText = { if (!ok) Text("Allowed: ${range.first}–${range.last}") },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier.fillMaxWidth(),
        )
    }
}
