package com.usee.scanner.data

import android.content.Context
import androidx.core.content.edit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class AppSettings(
    val mode: SensorMode = SensorMode.AUTO,
    val scanIntervalSec: Int = 30,
    val udpPort: Int = 5005,
    val serverHost: String = "",
    val serverWsPort: Int = 8765,
    val serverToken: String = "",
    val autoSweep: Boolean = true,
    val keepScreenOn: Boolean = false,
)

/** SharedPreferences-backed settings. The token stays in app-private storage. */
class SettingsStore(context: Context) {
    private val prefs = context.getSharedPreferences("usee_settings", Context.MODE_PRIVATE)
    private val _state = MutableStateFlow(load())
    val state: StateFlow<AppSettings> = _state.asStateFlow()

    private fun load() = AppSettings(
        mode = runCatching { SensorMode.valueOf(prefs.getString("mode", "AUTO")!!) }.getOrDefault(SensorMode.AUTO),
        scanIntervalSec = prefs.getInt("scanIntervalSec", 30).coerceIn(5, 300),
        udpPort = prefs.getInt("udpPort", 5005).coerceIn(1024, 65535),
        serverHost = prefs.getString("serverHost", "") ?: "",
        serverWsPort = prefs.getInt("serverWsPort", 8765).coerceIn(1, 65535),
        serverToken = prefs.getString("serverToken", "") ?: "",
        autoSweep = prefs.getBoolean("autoSweep", true),
        keepScreenOn = prefs.getBoolean("keepScreenOn", false),
    )

    fun update(transform: (AppSettings) -> AppSettings) {
        val s = transform(_state.value).let {
            it.copy(
                scanIntervalSec = it.scanIntervalSec.coerceIn(5, 300),
                udpPort = it.udpPort.coerceIn(1024, 65535),
                serverWsPort = it.serverWsPort.coerceIn(1, 65535),
                serverHost = it.serverHost.trim(),
            )
        }
        prefs.edit {
            putString("mode", s.mode.name)
            putInt("scanIntervalSec", s.scanIntervalSec)
            putInt("udpPort", s.udpPort)
            putString("serverHost", s.serverHost)
            putInt("serverWsPort", s.serverWsPort)
            putString("serverToken", s.serverToken)
            putBoolean("autoSweep", s.autoSweep)
            putBoolean("keepScreenOn", s.keepScreenOn)
        }
        _state.value = s
    }
}
