package com.usee.scanner.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.LocationOn
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Wifi
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.usee.scanner.ui.components.RadarView
import com.usee.scanner.ui.theme.Palette

@Composable
fun PermissionScreen(permanentlyDenied: Boolean, onGrant: () -> Unit, onOpenSettings: () -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        RadarView(Palette.Cyan, 0.35f, Modifier.fillMaxWidth(0.7f))
        Spacer(Modifier.height(20.dp))
        Text("See your RF environment", style = MaterialTheme.typography.headlineMedium, color = Palette.Text, textAlign = TextAlign.Center)
        Spacer(Modifier.height(8.dp))
        Text(
            "uSee scans nearby WiFi, detects which sensing hardware is available and turns RF disturbances into a live motion picture.",
            color = Palette.TextDim,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(24.dp))
        Reason(Icons.Rounded.LocationOn, "Location", "Android requires it to read WiFi scan results. uSee never records your position.")
        Reason(Icons.Rounded.Wifi, "Nearby WiFi devices", "Lets uSee scan access points and read the connected link.")
        Reason(Icons.Rounded.Lock, "Local only", "Discovery uses read-only requests on your private network. Nothing is uploaded.")
        Spacer(Modifier.height(24.dp))
        Button(
            onClick = if (permanentlyDenied) onOpenSettings else onGrant,
            modifier = Modifier.fillMaxWidth().height(52.dp),
            colors = ButtonDefaults.buttonColors(containerColor = Palette.Cyan, contentColor = Color(0xFF00201D)),
        ) { Text(if (permanentlyDenied) "Open app settings" else "Grant access", fontSize = 16.sp) }
        if (permanentlyDenied) {
            TextButton(onClick = onGrant) { Text("Try again") }
        }
    }
}

@Composable
private fun Reason(icon: ImageVector, title: String, body: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.Top) {
        Icon(icon, null, tint = Palette.Cyan, modifier = Modifier.size(22.dp))
        Spacer(Modifier.width(14.dp))
        Column {
            Text(title, color = Palette.Text, style = MaterialTheme.typography.titleSmall)
            Text(body, color = Palette.TextDim, fontSize = 13.sp)
        }
    }
}
