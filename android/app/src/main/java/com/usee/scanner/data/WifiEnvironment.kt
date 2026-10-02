package com.usee.scanner.data

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.location.LocationManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.ScanResult
import android.net.wifi.WifiInfo
import android.net.wifi.WifiManager
import android.net.wifi.rtt.WifiRttManager
import android.os.Build
import android.os.SystemClock
import androidx.core.content.ContextCompat
import com.usee.scanner.core.Oui
import com.usee.scanner.core.WifiMath
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.net.Inet4Address

data class ScanStatus(
    val scanning: Boolean = false,
    val lastResultsMs: Long = 0,
    val nextAllowedMs: Long = 0,
    val throttledByOs: Boolean = false,
    val message: String? = null,
)

/**
 * Wraps [WifiManager] scanning and connected-link sampling.
 *
 * Android limits foreground apps to 4 `startScan()` calls per 2 minutes
 * (Android 9+). The scheduler below never exceeds that budget; results
 * produced by scans other apps or the OS trigger are also consumed, since the
 * results broadcast is system-wide.
 */
class WifiEnvironment(private val context: Context) {

    private val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
    private val connectivity = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    private val _accessPoints = MutableStateFlow<List<AccessPoint>>(emptyList())
    val accessPoints: StateFlow<List<AccessPoint>> = _accessPoints.asStateFlow()

    private val _link = MutableStateFlow<ConnectedLink?>(null)
    val link: StateFlow<ConnectedLink?> = _link.asStateFlow()

    private val _status = MutableStateFlow(ScanStatus())
    val status: StateFlow<ScanStatus> = _status.asStateFlow()

    /** Emits each fresh batch of scan results (bssid -> rssi) for the RSSI engine. */
    var onScanBatch: ((Map<String, Int>) -> Unit)? = null

    private val scanTimes = ArrayDeque<Long>()
    private var registered = false

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            val fresh = intent.getBooleanExtra(WifiManager.EXTRA_RESULTS_UPDATED, true)
            readResults(fresh)
        }
    }

    fun hasScanPermission(): Boolean {
        val fine = ContextCompat.checkSelfPermission(context, android.Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED
        return fine
    }

    fun isWifiEnabled(): Boolean = wifi.isWifiEnabled

    fun isLocationEnabled(): Boolean {
        val lm = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            lm.isLocationEnabled
        } else {
            @Suppress("DEPRECATION")
            android.provider.Settings.Secure.getInt(
                context.contentResolver,
                android.provider.Settings.Secure.LOCATION_MODE,
                0,
            ) != 0
        }
    }

    fun start() {
        if (registered) return
        val filter = IntentFilter(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION)
        // System broadcasts reach non-exported receivers on API 33+. Below that,
        // ContextCompat's NOT_EXPORTED emulation adds an app-private permission
        // that the WiFi service does not hold, so register directly.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            context.registerReceiver(receiver, filter)
        }
        registered = true
        readResults(fresh = false)
    }

    fun stop() {
        if (!registered) return
        runCatching { context.unregisterReceiver(receiver) }
        registered = false
    }

    /** Requests a scan if the OS budget allows. Returns false when deferred. */
    fun requestScan(): Boolean {
        val now = SystemClock.elapsedRealtime()
        while (scanTimes.isNotEmpty() && now - scanTimes.first() > WINDOW_MS) scanTimes.removeFirst()
        // Developer option "WiFi scan throttling" off → the OS budget does not apply.
        val budget = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && !wifi.isScanThrottleEnabled) {
            Int.MAX_VALUE
        } else {
            BUDGET
        }
        if (scanTimes.size >= budget) {
            val next = scanTimes.first() + WINDOW_MS
            _status.value = _status.value.copy(nextAllowedMs = next, message = "OS scan budget used; waiting")
            return false
        }
        if (!wifi.isWifiEnabled) {
            _status.value = _status.value.copy(message = "WiFi is off")
            return false
        }
        val started = try {
            @Suppress("DEPRECATION")
            wifi.startScan()
        } catch (e: SecurityException) {
            _status.value = _status.value.copy(message = "Permission denied")
            false
        }
        scanTimes.addLast(now)
        _status.value = _status.value.copy(
            scanning = started,
            throttledByOs = !started,
            nextAllowedMs = if (scanTimes.size >= budget) scanTimes.first() + WINDOW_MS else now,
            message = if (started) null else "Scan throttled by Android — showing cached results",
        )
        if (!started) readResults(fresh = false)
        return started
    }

    @SuppressLint("MissingPermission")
    private fun readResults(fresh: Boolean) {
        val results: List<ScanResult> = try {
            wifi.scanResults ?: emptyList()
        } catch (e: SecurityException) {
            _status.value = _status.value.copy(scanning = false, message = "Location permission required")
            return
        }
        val link = sampleLink()
        val now = System.currentTimeMillis()
        val bootNowUs = SystemClock.elapsedRealtimeNanos() / 1000
        val aps = results.mapNotNull { r -> toAccessPoint(r, link, now, bootNowUs) }
            .sortedByDescending { it.rssi }
        _accessPoints.value = aps
        _status.value = _status.value.copy(scanning = false, lastResultsMs = now)
        if (fresh && aps.isNotEmpty()) {
            // Only reasonably fresh observations feed the motion engine.
            onScanBatch?.invoke(aps.filter { now - it.seenAtMs < 15_000 }.associate { it.bssid to it.rssi })
        }
    }

    private fun toAccessPoint(r: ScanResult, link: ConnectedLink?, now: Long, bootNowUs: Long): AccessPoint? {
        val bssid = r.BSSID ?: return null
        @Suppress("DEPRECATION")
        val ssid = r.SSID?.takeIf { it.isNotBlank() } ?: ""
        val band = WifiMath.band(r.frequency)
        val standard = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            WifiMath.standardLabel(r.wifiStandard, band)
        } else {
            "—"
        }
        val ageMs = ((bootNowUs - r.timestamp) / 1000).coerceAtLeast(0)
        val security = WifiMath.security(r.capabilities ?: "")
        val hint = Oui.vendorHint(bssid, ssid)
        return AccessPoint(
            bssid = Oui.normalize(bssid),
            ssid = ssid,
            rssi = r.level,
            frequencyMhz = r.frequency,
            channel = WifiMath.channel(r.frequency),
            band = band,
            widthMhz = WifiMath.widthMhz(r.channelWidth),
            standard = standard,
            security = security,
            vendorHint = hint,
            sensorCandidate = Oui.isEspressif(bssid) || Oui.looksLikeSensorSsid(ssid),
            distanceMeters = WifiMath.fsplDistanceMeters(r.level, r.frequency),
            connected = link?.bssid != null && link.bssid.equals(bssid, ignoreCase = true),
            seenAtMs = now - ageMs,
        )
    }

    /** Samples the connected link. Cheap; safe to call at a few Hz. */
    @SuppressLint("MissingPermission")
    fun sampleLink(): ConnectedLink? {
        val info: WifiInfo = try {
            @Suppress("DEPRECATION")
            wifi.connectionInfo
        } catch (e: SecurityException) {
            null
        } ?: run { _link.value = null; return null }
        if (info.networkId == -1 && info.rssi <= -127) {
            _link.value = null
            return null
        }
        @Suppress("DEPRECATION")
        val ssid = info.ssid?.removeSurrounding("\"")?.takeIf { it != "<unknown ssid>" }
        val bssid = info.bssid?.takeIf { it != "02:00:00:00:00:00" }?.let(Oui::normalize)
        val (ip, prefix) = ipv4()
        val band = WifiMath.band(info.frequency)
        val link = ConnectedLink(
            ssid = ssid,
            bssid = bssid,
            rssi = info.rssi,
            frequencyMhz = info.frequency,
            linkSpeedMbps = info.linkSpeed,
            txLinkMbps = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) info.txLinkSpeedMbps.takeIf { it > 0 } else null,
            rxLinkMbps = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) info.rxLinkSpeedMbps.takeIf { it > 0 } else null,
            standard = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) WifiMath.standardLabel(info.wifiStandard, band) else null,
            ipv4 = ip,
            prefixLength = prefix,
        )
        _link.value = link
        return link
    }

    private var cachedIp: Pair<String?, Int?> = null to null
    private var cachedIpAt = 0L

    /** IPv4 address and prefix of the WiFi network, cached for a few seconds. */
    fun ipv4(): Pair<String?, Int?> {
        val now = SystemClock.elapsedRealtime()
        if (cachedIpAt != 0L && now - cachedIpAt < 5_000) return cachedIp
        cachedIp = lookupIpv4()
        cachedIpAt = now
        return cachedIp
    }

    private fun lookupIpv4(): Pair<String?, Int?> {
        val network = connectivity.allNetworksCompat().firstOrNull { n ->
            connectivity.getNetworkCapabilities(n)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
        } ?: return null to null
        val lp = connectivity.getLinkProperties(network) ?: return null to null
        val la = lp.linkAddresses.firstOrNull { it.address is Inet4Address } ?: return null to null
        return la.address.hostAddress to la.prefixLength
    }

    @Suppress("DEPRECATION")
    private fun ConnectivityManager.allNetworksCompat() = allNetworks.toList()

    fun phoneProfile(): PhoneProfile {
        val pm = context.packageManager
        val standards = buildList {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                if (wifi.isWifiStandardSupported(ScanResult.WIFI_STANDARD_11N)) add("Wi-Fi 4")
                if (wifi.isWifiStandardSupported(ScanResult.WIFI_STANDARD_11AC)) add("Wi-Fi 5")
                if (wifi.isWifiStandardSupported(ScanResult.WIFI_STANDARD_11AX)) add("Wi-Fi 6")
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                    wifi.isWifiStandardSupported(ScanResult.WIFI_STANDARD_11BE)
                ) add("Wi-Fi 7")
            }
        }
        val rttFeature = Build.VERSION.SDK_INT >= Build.VERSION_CODES.P &&
            pm.hasSystemFeature(PackageManager.FEATURE_WIFI_RTT)
        val rttAvailable = rttFeature && runCatching {
            (context.getSystemService(Context.WIFI_RTT_RANGING_SERVICE) as? WifiRttManager)?.isAvailable == true
        }.getOrDefault(false)
        val maxTx = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            @Suppress("DEPRECATION")
            wifi.connectionInfo?.maxSupportedTxLinkSpeedMbps?.takeIf { it > 0 }
        } else null
        val maxRx = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            @Suppress("DEPRECATION")
            wifi.connectionInfo?.maxSupportedRxLinkSpeedMbps?.takeIf { it > 0 }
        } else null
        return PhoneProfile(
            manufacturer = Build.MANUFACTURER.replaceFirstChar { it.uppercase() },
            model = Build.MODEL,
            soc = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                listOf(Build.SOC_MANUFACTURER, Build.SOC_MODEL)
                    .filter { it.isNotBlank() && it != Build.UNKNOWN }
                    .joinToString(" ")
                    .ifBlank { null }
            } else null,
            androidVersion = Build.VERSION.RELEASE,
            sdkInt = Build.VERSION.SDK_INT,
            wifiEnabled = wifi.isWifiEnabled,
            band5 = wifi.is5GHzBandSupported,
            band6 = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && wifi.is6GHzBandSupported,
            band60 = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && wifi.is60GHzBandSupported,
            standards = standards,
            rtt = rttAvailable,
            aware = pm.hasSystemFeature(PackageManager.FEATURE_WIFI_AWARE),
            p2p = pm.hasSystemFeature(PackageManager.FEATURE_WIFI_DIRECT),
            scanThrottled = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) wifi.isScanThrottleEnabled else null,
            maxTxMbps = maxTx,
            maxRxMbps = maxRx,
        )
    }

    companion object {
        const val BUDGET = 4
        const val WINDOW_MS = 120_000L
    }
}
