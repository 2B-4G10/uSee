package com.usee.scanner.data

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import com.usee.scanner.core.NetUtil
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URL
import java.util.concurrent.Executors

/** One read-only probe hit. */
sealed interface ProbeHit {
    data class Esp32(val ip: String, val firmware: String?) : ProbeHit
    data class Server(val ip: String, val httpPort: Int?, val wsPort: Int, val source: String?, val hostRejected: Boolean) : ProbeHit
}

data class SweepProgress(val running: Boolean = false, val done: Int = 0, val total: Int = 0, val message: String? = null)

/**
 * Finds RuView hardware on the local network using read-only requests only:
 *
 *  - mDNS browse for `_ruview._tcp` (advertised by the sensing server, see
 *    v2/crates/wifi-densepose-sensing-server/src/discovery.rs)
 *  - `GET :8032/ota/status` — the ESP32 node's unauthenticated status page
 *    (firmware/esp32-csi-node/main/ota_update.c)
 *  - `GET :8765/health` and `:8080/health` — the sensing server health route
 *
 * Nothing is ever POSTed and only private (RFC 1918) subnets are swept.
 */
class LanDiscovery(private val context: Context) {

    private val _progress = MutableStateFlow(SweepProgress())
    val progress: StateFlow<SweepProgress> = _progress.asStateFlow()

    var onMdnsServer: ((host: String, port: Int, name: String) -> Unit)? = null

    @OptIn(ExperimentalCoroutinesApi::class)
    private val probeDispatcher: CoroutineDispatcher = Dispatchers.IO.limitedParallelism(48)

    // ---- mDNS -------------------------------------------------------------------------------

    private val nsd: NsdManager? = context.getSystemService(Context.NSD_SERVICE) as? NsdManager
    private var discoveryListener: NsdManager.DiscoveryListener? = null
    private val resolveExecutor = Executors.newSingleThreadExecutor()

    fun startMdns() {
        val manager = nsd ?: return
        if (discoveryListener != null) return
        val listener = object : NsdManager.DiscoveryListener {
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) { discoveryListener = null }
            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {}
            override fun onDiscoveryStarted(serviceType: String) {}
            override fun onDiscoveryStopped(serviceType: String) {}
            override fun onServiceLost(serviceInfo: NsdServiceInfo) {}
            override fun onServiceFound(serviceInfo: NsdServiceInfo) { resolve(manager, serviceInfo) }
        }
        discoveryListener = listener
        runCatching { manager.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, listener) }
            .onFailure { discoveryListener = null }
    }

    fun stopMdns() {
        val l = discoveryListener ?: return
        runCatching { nsd?.stopServiceDiscovery(l) }
        discoveryListener = null
    }

    @Suppress("DEPRECATION")
    private fun resolve(manager: NsdManager, info: NsdServiceInfo) {
        val cb = object : NsdManager.ResolveListener {
            override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {}
            override fun onServiceResolved(serviceInfo: NsdServiceInfo) {
                val host = if (Build.VERSION.SDK_INT >= 34) {
                    serviceInfo.hostAddresses.firstOrNull { it is java.net.Inet4Address }?.hostAddress
                        ?: serviceInfo.hostAddresses.firstOrNull()?.hostAddress
                } else {
                    serviceInfo.host?.hostAddress
                } ?: return
                onMdnsServer?.invoke(host, serviceInfo.port, serviceInfo.serviceName)
            }
        }
        runCatching {
            if (Build.VERSION.SDK_INT >= 34) manager.resolveService(info, resolveExecutor, cb)
            else manager.resolveService(info, cb)
        }
    }

    // ---- Subnet sweep -------------------------------------------------------------------------

    /** Sweeps the WiFi subnet. [ownIp]/[prefix] come from the WiFi link properties. */
    suspend fun sweep(ownIp: String?, prefix: Int?, onHit: (ProbeHit) -> Unit) {
        val hosts = if (ownIp != null && prefix != null) NetUtil.sweepTargets(ownIp, prefix) else emptyList()
        if (hosts.isEmpty()) {
            _progress.value = SweepProgress(
                message = if (ownIp == null) "Not on a WiFi network" else "Subnet is not private or too large; sweep skipped",
            )
            return
        }
        _progress.value = SweepProgress(running = true, done = 0, total = hosts.size)
        var done = 0
        coroutineScope {
            hosts.map { ip ->
                async(probeDispatcher) {
                    probeHost(ip).forEach(onHit)
                    synchronized(this@LanDiscovery) {
                        done++
                        if (done % 8 == 0 || done == hosts.size) {
                            _progress.value = SweepProgress(running = true, done = done, total = hosts.size)
                        }
                    }
                }
            }.awaitAll()
        }
        _progress.value = SweepProgress(running = false, done = hosts.size, total = hosts.size)
    }

    /** Probes a single host (used for manual entry and mDNS verification). */
    suspend fun probeHost(ip: String, extraServerPorts: List<Int> = emptyList()): List<ProbeHit> =
        withContext(probeDispatcher) {
            val hits = mutableListOf<ProbeHit>()
            if (portOpen(ip, ESP32_PORT)) {
                val body = httpGet("http://${NetUtil.hostForUrl(ip)}:$ESP32_PORT/ota/status")
                val json = body?.second?.let { runCatching { JSONObject(it) }.getOrNull() }
                if (json != null && json.has("running_partition")) {
                    hits += ProbeHit.Esp32(ip, json.optString("version").ifBlank { null })
                }
            }
            for (port in (SERVER_PORTS + extraServerPorts).distinct()) {
                if (!portOpen(ip, port)) continue
                val res = httpGet("http://${NetUtil.hostForUrl(ip)}:$port/health") ?: continue
                val (code, body) = res
                val json = runCatching { JSONObject(body) }.getOrNull()
                val isServer = json != null && json.optString("status") == "ok" && json.has("source")
                val rejected = code == 421 && body.contains("DNS-rebinding")
                if (isServer || rejected) {
                    hits += ProbeHit.Server(
                        ip = ip,
                        httpPort = if (port == WS_PORT) null else port,
                        wsPort = if (port == WS_PORT) port else WS_PORT,
                        source = json?.optString("source")?.ifBlank { null },
                        hostRejected = rejected,
                    )
                }
            }
            hits
        }

    private fun portOpen(ip: String, port: Int): Boolean = try {
        Socket().use { it.connect(InetSocketAddress(ip, port), CONNECT_TIMEOUT_MS); true }
    } catch (e: Exception) {
        false
    }

    /** Returns (status, body≤8 KiB) or null on network failure. */
    private fun httpGet(url: String): Pair<Int, String>? = try {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = CONNECT_TIMEOUT_MS
        c.readTimeout = READ_TIMEOUT_MS
        c.instanceFollowRedirects = false
        c.requestMethod = "GET"
        c.setRequestProperty("Accept", "application/json")
        try {
            val code = c.responseCode
            val stream = if (code >= 400) c.errorStream else c.inputStream
            val body = stream?.use { s ->
                val buf = ByteArray(MAX_BODY)
                var total = 0
                while (total < MAX_BODY) {
                    val n = s.read(buf, total, MAX_BODY - total)
                    if (n <= 0) break
                    total += n
                }
                String(buf, 0, total, Charsets.UTF_8)
            } ?: ""
            code to body
        } finally {
            c.disconnect()
        }
    } catch (e: Exception) {
        null
    }

    companion object {
        const val SERVICE_TYPE = "_ruview._tcp."
        const val ESP32_PORT = 8032
        const val WS_PORT = 8765
        val SERVER_PORTS = listOf(WS_PORT, 8080)
        const val CONNECT_TIMEOUT_MS = 350
        const val READ_TIMEOUT_MS = 1200
        const val MAX_BODY = 8 * 1024
    }
}
