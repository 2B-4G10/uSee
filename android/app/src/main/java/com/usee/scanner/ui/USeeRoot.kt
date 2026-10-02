package com.usee.scanner.ui

import android.content.Intent
import android.provider.Settings
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Radar
import androidx.compose.material.icons.rounded.Sensors
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material.icons.rounded.WifiFind
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.usee.scanner.ui.components.LiveDot
import com.usee.scanner.ui.screens.DevicesScreen
import com.usee.scanner.ui.screens.ScanScreen
import com.usee.scanner.ui.screens.SenseScreen
import com.usee.scanner.ui.screens.SettingsScreen
import com.usee.scanner.ui.screens.color
import com.usee.scanner.ui.theme.Palette

private enum class Tab(val label: String, val icon: ImageVector) {
    SENSE("Sense", Icons.Rounded.Radar),
    SCAN("Scan", Icons.Rounded.WifiFind),
    DETECT("Detect", Icons.Rounded.Sensors),
    SETTINGS("Settings", Icons.Rounded.Tune),
}

@Composable
fun USeeRoot(vm: AppViewModel) {
    val sensing by vm.sensing.collectAsStateWithLifecycle()
    val aps by vm.accessPoints.collectAsStateWithLifecycle()
    val status by vm.scanStatus.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    val profile by vm.profile.collectAsStateWithLifecycle()
    val link by vm.link.collectAsStateWithLifecycle()
    val nodes by vm.nodes.collectAsStateWithLifecycle()
    val servers by vm.servers.collectAsStateWithLifecycle()
    val serverLink by vm.serverLink.collectAsStateWithLifecycle()
    val sweep by vm.sweep.collectAsStateWithLifecycle()
    val udpError by vm.udpError.collectAsStateWithLifecycle()
    var tab by rememberSaveable { mutableStateOf(Tab.SENSE) }
    val context = LocalContext.current

    Box(Modifier.fillMaxSize().background(Palette.backdrop)) {
        Scaffold(
            containerColor = Color.Transparent,
            topBar = { TopBar(sensing.active.color(), sensing.active.tier) },
            bottomBar = {
                NavigationBar(containerColor = Palette.BgRaised.copy(alpha = 0.96f), tonalElevation = 0.dp) {
                    Tab.entries.forEach { t ->
                        NavigationBarItem(
                            selected = tab == t,
                            onClick = { tab = t },
                            icon = { Icon(t.icon, t.label) },
                            label = { Text(t.label) },
                            colors = NavigationBarItemDefaults.colors(
                                selectedIconColor = Palette.Cyan,
                                selectedTextColor = Palette.Cyan,
                                indicatorColor = Palette.Cyan.copy(alpha = 0.14f),
                                unselectedIconColor = Palette.TextDim,
                                unselectedTextColor = Palette.TextDim,
                            ),
                        )
                    }
                }
            },
        ) { pad ->
            Column(Modifier.padding(pad)) {
                val wifiOn = profile?.wifiEnabled != false && vm.wifi.isWifiEnabled()
                if (!wifiOn) {
                    Banner("WiFi is off — scanning is paused.", "Turn on") {
                        context.startActivity(Intent(Settings.ACTION_WIFI_SETTINGS))
                    }
                } else if (!vm.wifi.isLocationEnabled()) {
                    Banner("Location services are off — Android hides scan results.", "Enable") {
                        context.startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS))
                    }
                }
                AnimatedContent(tab, transitionSpec = { fadeIn() togetherWith fadeOut() }, label = "tab") { t ->
                    when (t) {
                        Tab.SENSE -> SenseScreen(sensing, vm::recalibrate)
                        Tab.SCAN -> ScanScreen(aps, status) { vm.refreshScan() }
                        Tab.DETECT -> DevicesScreen(
                            sensing = sensing,
                            profile = profile,
                            link = link,
                            nodes = nodes,
                            servers = servers,
                            serverLink = serverLink,
                            sweep = sweep,
                            udpPort = settings.udpPort,
                            udpError = udpError,
                            onSweep = vm::sweepLan,
                            onProbe = vm::probeManual,
                            onConnect = vm::connectServer,
                        )
                        Tab.SETTINGS -> SettingsScreen(settings, vm::updateSettings)
                    }
                }
            }
        }
    }
}

@Composable
private fun TopBar(color: Color, tier: String) {
    Row(
        Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 20.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            "uSee",
            style = TextStyle(
                brush = Brush.linearGradient(listOf(Palette.Cyan, Palette.Violet)),
                fontSize = 28.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = (-0.5).sp,
            ),
        )
        Spacer(Modifier.weight(1f))
        Row(
            Modifier
                .clip(RoundedCornerShape(50))
                .background(color.copy(alpha = 0.12f))
                .border(1.dp, color.copy(alpha = 0.3f), RoundedCornerShape(50))
                .padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            LiveDot(color)
            Spacer(Modifier.width(6.dp))
            Text(tier, color = color, fontSize = 12.sp, fontWeight = FontWeight.Medium)
        }
    }
}

@Composable
private fun Banner(text: String, action: String, onAction: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(Palette.Amber.copy(alpha = 0.12f))
            .padding(start = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Rounded.Warning, null, tint = Palette.Amber, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(8.dp))
        Text(text, color = Palette.Amber, fontSize = 12.sp, modifier = Modifier.weight(1f))
        TextButton(onClick = onAction) { Text(action, color = Palette.Amber) }
    }
}
