package com.usee.scanner.data

import com.usee.scanner.core.Band
import com.usee.scanner.core.MotionReading

data class AccessPoint(
    val bssid: String,
    val ssid: String,
    val rssi: Int,
    val frequencyMhz: Int,
    val channel: Int,
    val band: Band,
    val widthMhz: Int,
    val standard: String,
    val security: String,
    val vendorHint: String?,
    val sensorCandidate: Boolean,
    val distanceMeters: Double,
    val connected: Boolean,
    val seenAtMs: Long,
)

data class ConnectedLink(
    val ssid: String?,
    val bssid: String?,
    val rssi: Int,
    val frequencyMhz: Int,
    val linkSpeedMbps: Int,
    val txLinkMbps: Int?,
    val rxLinkMbps: Int?,
    val standard: String?,
    val ipv4: String?,
    val prefixLength: Int?,
)

/** What this phone's WiFi radio can (and cannot) do for sensing. */
data class PhoneProfile(
    val manufacturer: String,
    val model: String,
    val soc: String?,
    val androidVersion: String,
    val sdkInt: Int,
    val wifiEnabled: Boolean,
    val band5: Boolean,
    val band6: Boolean,
    val band60: Boolean,
    val standards: List<String>,
    val rtt: Boolean,
    val aware: Boolean,
    val p2p: Boolean,
    val scanThrottled: Boolean?,
    val maxTxMbps: Int?,
    val maxRxMbps: Int?,
)

enum class Discovery(val label: String) { UDP("UDP stream"), HTTP("HTTP probe"), MDNS("mDNS"), MANUAL("Manual"), SOFTAP("Soft-AP beacon") }

data class Esp32Node(
    val key: String,
    val ip: String?,
    val nodeId: Int?,
    val firmware: String?,
    val discovery: Set<Discovery>,
    val lastSeenMs: Long,
    val csiRateHz: Double,
    val subcarriers: Int?,
    val channelFreqMhz: Int?,
    val rssi: Int?,
    val streamsCsi: Boolean,
    val streamsVitals: Boolean,
    val bssid: String? = null,
    val ssid: String? = null,
)

data class RuViewServer(
    val host: String,
    val httpPort: Int?,
    val wsPort: Int,
    val name: String?,
    val source: String?,
    val discovery: Set<Discovery>,
    val hostRejected: Boolean,
    val lastSeenMs: Long,
)

enum class SensorKind(val title: String, val tier: String, val detail: String) {
    SERVER("RuView sensing server", "Server", "Server-side RuView pipeline over WebSocket"),
    ESP32("ESP32 CSI node", "CSI", "Channel State Information per subcarrier"),
    PHONE("This phone's WiFi radio", "RSSI only", "Received signal strength per access point"),
}

enum class SensorMode(val label: String) { AUTO("Auto"), PHONE("Phone"), ESP32("ESP32"), SERVER("Server") }

data class Vitals(
    val breathingBpm: Double?,
    val heartRateBpm: Double?,
    val persons: Int?,
    val fall: Boolean = false,
)

/** One frame decoded from the sensing server WebSocket. */
data class ServerFrame(
    val source: String?,
    val presence: Boolean,
    val motionLevel: String,
    val confidence: Double,
    val meanRssi: Double?,
    val motionBandPower: Double?,
    val vitals: Vitals?,
    val nodeCount: Int,
    val receivedAtMs: Long,
)

data class SensingState(
    val active: SensorKind = SensorKind.PHONE,
    val reason: String = "Starting…",
    val motion: MotionReading = MotionReading.EMPTY,
    val presence: Boolean = false,
    val confidence: Double = 0.0,
    val headline: String = "Calibrating",
    val vitals: Vitals? = null,
    val history: List<Float> = emptyList(),
    val updateRateHz: Double = 0.0,
    val csiWaterfall: List<FloatArray> = emptyList(),
    val serverSource: String? = null,
)
