package com.usee.scanner.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.usee.scanner.core.CsiFrame
import com.usee.scanner.core.MotionEstimator
import com.usee.scanner.core.MotionLevel
import com.usee.scanner.core.MotionReading
import com.usee.scanner.core.NetUtil
import com.usee.scanner.core.OtherRuViewPacket
import com.usee.scanner.core.RuViewPacket
import com.usee.scanner.core.VitalsPacket
import com.usee.scanner.data.AccessPoint
import com.usee.scanner.data.AppSettings
import com.usee.scanner.data.Discovery
import com.usee.scanner.data.Esp32Node
import com.usee.scanner.data.LanDiscovery
import com.usee.scanner.data.PhoneProfile
import com.usee.scanner.data.ProbeHit
import com.usee.scanner.data.RuViewServer
import com.usee.scanner.data.SensingServerClient
import com.usee.scanner.data.SensingState
import com.usee.scanner.data.SensorKind
import com.usee.scanner.data.SensorMode
import com.usee.scanner.data.SettingsStore
import com.usee.scanner.data.UdpCsiReceiver
import com.usee.scanner.data.Vitals
import com.usee.scanner.data.WifiEnvironment
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Owns every sensor source and decides which one drives the Sense screen.
 *
 * Auto priority: RuView sensing server → ESP32 CSI node streaming to this
 * phone → the phone's own WiFi RSSI. A source counts as live only while it
 * has delivered data within [LIVE_WINDOW_MS].
 */
class AppViewModel(app: Application) : AndroidViewModel(app) {

    val settingsStore = SettingsStore(app)
    val settings: StateFlow<AppSettings> = settingsStore.state

    val wifi = WifiEnvironment(app)
    val discovery = LanDiscovery(app)
    private val udp = UdpCsiReceiver()
    val server = SensingServerClient(viewModelScope)

    val accessPoints = wifi.accessPoints
    val link = wifi.link
    val scanStatus = wifi.status
    val sweep = discovery.progress
    val serverLink = server.state
    val udpError = udp.error

    private val _profile = MutableStateFlow<PhoneProfile?>(null)
    val profile: StateFlow<PhoneProfile?> = _profile.asStateFlow()

    private val _nodes = MutableStateFlow<List<Esp32Node>>(emptyList())
    val nodes: StateFlow<List<Esp32Node>> = _nodes.asStateFlow()

    private val _servers = MutableStateFlow<List<RuViewServer>>(emptyList())
    val servers: StateFlow<List<RuViewServer>> = _servers.asStateFlow()

    private val _sensing = MutableStateFlow(SensingState())
    val sensing: StateFlow<SensingState> = _sensing.asStateFlow()

    private val _permissionsGranted = MutableStateFlow(false)
    val permissionsGranted: StateFlow<Boolean> = _permissionsGranted.asStateFlow()

    // ---- engine state (guarded by lock) ----
    private val lock = Any()
    private val rssiEngine = MotionEstimator.forRssi()
    private val csiEngine = MotionEstimator.forCsi()
    private var rssiReading = MotionReading.EMPTY
    private var csiReading = MotionReading.EMPTY
    private var csiNodeKey: String? = null
    private var lastCsiMs = 0L
    private var lastVitals: VitalsPacket? = null
    private var lastVitalsMs = 0L
    private val waterfall = ArrayDeque<FloatArray>()
    private var lastWaterfallMs = 0L
    private val nodeMap = LinkedHashMap<String, Esp32Node>()
    private val nodeFrameTimes = HashMap<String, ArrayDeque<Long>>()
    private val serverMap = LinkedHashMap<String, RuViewServer>()
    private val history = ArrayDeque<Float>()
    private var lastRssiValue = Int.MIN_VALUE
    private val rssiChangeTimes = ArrayDeque<Long>()

    private var running = false
    private val jobs = mutableListOf<Job>()

    init {
        wifi.onScanBatch = { batch -> onScanBatch(batch) }
        udp.onPacket = { p, ip -> onPacket(p, ip) }
        discovery.onMdnsServer = { host, port, name -> onMdnsServer(host, port, name) }
        _profile.value = runCatching { wifi.phoneProfile() }.getOrNull()
    }

    fun setPermissionsGranted(granted: Boolean) {
        val changed = _permissionsGranted.value != granted
        _permissionsGranted.value = granted
        if (changed && granted && running) {
            wifi.stop(); wifi.start(); wifi.requestScan()
        }
    }

    /** Called when the UI becomes visible. All radios stop when it is hidden. */
    fun start() {
        if (running) return
        running = true
        _profile.value = runCatching { wifi.phoneProfile() }.getOrNull()
        wifi.start()
        discovery.startMdns()
        udp.start(viewModelScope, settings.value.udpPort)
        jobs += viewModelScope.launch { scanLoop() }
        jobs += viewModelScope.launch { linkLoop() }
        jobs += viewModelScope.launch { arbitrationLoop() }
        jobs += viewModelScope.launch { settings.collect { applyServerSettings(it) } }
        if (settings.value.autoSweep) sweepLan()
    }

    fun stop() {
        if (!running) return
        running = false
        jobs.forEach { it.cancel() }
        jobs.clear()
        wifi.stop()
        discovery.stopMdns()
        udp.stop()
        server.disconnect()
    }

    override fun onCleared() {
        stop()
    }

    fun refreshScan() = wifi.requestScan()

    fun recalibrate() {
        synchronized(lock) {
            rssiEngine.reset(); csiEngine.reset()
            rssiReading = MotionReading.EMPTY; csiReading = MotionReading.EMPTY
            history.clear()
        }
    }

    fun updateSettings(transform: (AppSettings) -> AppSettings) {
        val oldPort = settings.value.udpPort
        settingsStore.update(transform)
        if (running && settings.value.udpPort != oldPort) udp.start(viewModelScope, settings.value.udpPort)
    }

    fun sweepLan() {
        if (sweep.value.running) return
        viewModelScope.launch {
            val (ip, prefix) = wifi.ipv4()
            discovery.sweep(ip, prefix) { hit -> onProbeHit(hit, Discovery.HTTP) }
        }
    }

    /** Probes a user-typed host and, if it is a sensing server, connects to it. */
    fun probeManual(host: String, onResult: (String) -> Unit) {
        val h = host.trim()
        if (!NetUtil.isValidHost(h)) { onResult("Not a valid host name or IPv4 address"); return }
        viewModelScope.launch {
            val hits = discovery.probeHost(h, listOf(settings.value.serverWsPort))
            hits.forEach { onProbeHit(it, Discovery.MANUAL) }
            onResult(
                when {
                    hits.isEmpty() -> "No RuView server or ESP32 node answered at $h"
                    else -> hits.joinToString { if (it is ProbeHit.Esp32) "ESP32 node found" else "Sensing server found" }
                },
            )
        }
    }

    fun connectServer(s: RuViewServer) {
        updateSettings { it.copy(serverHost = s.host, serverWsPort = s.wsPort, mode = if (it.mode == SensorMode.PHONE) SensorMode.AUTO else it.mode) }
    }

    // ---- loops -------------------------------------------------------------------------------

    private suspend fun scanLoop() {
        while (running) {
            if (_permissionsGranted.value) wifi.requestScan()
            delay(settings.value.scanIntervalSec * 1000L)
        }
    }

    private suspend fun linkLoop() {
        while (running) {
            val link = if (_permissionsGranted.value) runCatching { wifi.sampleLink() }.getOrNull() else null
            if (link != null && link.rssi > -127) {
                val now = System.currentTimeMillis()
                synchronized(lock) {
                    if (link.rssi != lastRssiValue) {
                        lastRssiValue = link.rssi
                        rssiChangeTimes.addLast(now)
                    }
                    while (rssiChangeTimes.isNotEmpty() && now - rssiChangeTimes.first() > 10_000) rssiChangeTimes.removeFirst()
                    rssiReading = rssiEngine.update(mapOf("link" to link.rssi.toDouble()), now)
                }
            }
            delay(LINK_POLL_MS)
        }
    }

    private fun onScanBatch(batch: Map<String, Int>) {
        if (batch.size < 3) return // the RuView multi-BSSID gate needs ≥3 BSSIDs
        val now = System.currentTimeMillis()
        val values = batch.mapValues { it.value.toDouble() }
        // Strong APs are less noisy, so they get more weight.
        val weights = batch.mapValues { ((it.value + 100) / 60.0).coerceIn(0.1, 1.0) }
        synchronized(lock) { rssiReading = rssiEngine.update(values, now, weights) }
        updateSoftApCandidates(accessPoints.value)
    }

    private fun onPacket(p: RuViewPacket, ip: String) {
        val now = System.currentTimeMillis()
        val key = "udp:$ip#${p.nodeId}"
        synchronized(lock) {
            val times = nodeFrameTimes.getOrPut(key) { ArrayDeque() }
            val prev = nodeMap[key]
            when (p) {
                is CsiFrame -> {
                    times.addLast(now)
                    while (times.isNotEmpty() && now - times.first() > 2_000) times.removeFirst()
                    if (csiNodeKey == null || now - lastCsiMs > LIVE_WINDOW_MS) csiNodeKey = key
                    if (csiNodeKey == key) {
                        lastCsiMs = now
                        val mean = p.amplitudes.average().toFloat().coerceAtLeast(1e-3f)
                        val norm = FloatArray(p.amplitudes.size) { p.amplitudes[it] / mean }
                        val values = HashMap<String, Double>(norm.size)
                        norm.forEachIndexed { i, v -> if (p.amplitudes[i] > 0.5f) values[i.toString()] = v.toDouble() }
                        csiReading = csiEngine.update(values, now)
                        if (now - lastWaterfallMs >= 80) {
                            lastWaterfallMs = now
                            waterfall.addLast(norm)
                            while (waterfall.size > WATERFALL_ROWS) waterfall.removeFirst()
                        }
                    }
                }
                is VitalsPacket -> { lastVitals = p; lastVitalsMs = now }
                is OtherRuViewPacket -> Unit
            }
            nodeMap[key] = Esp32Node(
                key = key,
                ip = ip,
                nodeId = p.nodeId,
                firmware = prev?.firmware ?: nodeMap["http:$ip"]?.firmware,
                discovery = (prev?.discovery ?: emptySet()) + Discovery.UDP,
                lastSeenMs = now,
                csiRateHz = times.size / 2.0,
                subcarriers = (p as? CsiFrame)?.subcarriers ?: prev?.subcarriers,
                channelFreqMhz = (p as? CsiFrame)?.freqMhz ?: prev?.channelFreqMhz,
                rssi = (p as? CsiFrame)?.rssi ?: (p as? VitalsPacket)?.rssi ?: prev?.rssi,
                streamsCsi = (prev?.streamsCsi ?: false) || p is CsiFrame,
                streamsVitals = (prev?.streamsVitals ?: false) || p is VitalsPacket,
            )
            nodeMap.remove("http:$ip") // merge HTTP-probed entry into the live one
        }
    }

    private fun onProbeHit(hit: ProbeHit, via: Discovery) {
        val now = System.currentTimeMillis()
        synchronized(lock) {
            when (hit) {
                is ProbeHit.Esp32 -> {
                    val live = nodeMap.entries.firstOrNull { it.value.ip == hit.ip && it.key.startsWith("udp:") }
                    if (live != null) {
                        nodeMap[live.key] = live.value.copy(firmware = hit.firmware, discovery = live.value.discovery + via)
                    } else {
                        val key = "http:${hit.ip}"
                        val prev = nodeMap[key]
                        nodeMap[key] = Esp32Node(
                            key = key, ip = hit.ip, nodeId = null, firmware = hit.firmware,
                            discovery = (prev?.discovery ?: emptySet()) + via, lastSeenMs = now,
                            csiRateHz = 0.0, subcarriers = null, channelFreqMhz = null, rssi = null,
                            streamsCsi = false, streamsVitals = false,
                        )
                    }
                }
                is ProbeHit.Server -> {
                    val prev = serverMap[hit.ip]
                    serverMap[hit.ip] = RuViewServer(
                        host = hit.ip,
                        httpPort = hit.httpPort ?: prev?.httpPort,
                        wsPort = hit.wsPort,
                        name = prev?.name,
                        source = hit.source ?: prev?.source,
                        discovery = (prev?.discovery ?: emptySet()) + via,
                        hostRejected = hit.hostRejected && (prev?.hostRejected ?: true),
                        lastSeenMs = now,
                    )
                }
            }
            publishDevices()
        }
    }

    private fun onMdnsServer(host: String, port: Int, name: String) {
        synchronized(lock) {
            val prev = serverMap[host]
            serverMap[host] = RuViewServer(
                host = host, httpPort = port, wsPort = prev?.wsPort ?: LanDiscovery.WS_PORT, name = name,
                source = prev?.source, discovery = (prev?.discovery ?: emptySet()) + Discovery.MDNS,
                hostRejected = prev?.hostRejected ?: false, lastSeenMs = System.currentTimeMillis(),
            )
            publishDevices()
        }
        viewModelScope.launch { discovery.probeHost(host).forEach { onProbeHit(it, Discovery.MDNS) } }
    }

    private fun updateSoftApCandidates(aps: List<AccessPoint>) {
        synchronized(lock) {
            nodeMap.keys.filter { it.startsWith("ap:") }.forEach(nodeMap::remove)
            aps.filter { it.sensorCandidate }.forEach { ap ->
                nodeMap["ap:${ap.bssid}"] = Esp32Node(
                    key = "ap:${ap.bssid}", ip = null, nodeId = null, firmware = null,
                    discovery = setOf(Discovery.SOFTAP), lastSeenMs = ap.seenAtMs, csiRateHz = 0.0,
                    subcarriers = null, channelFreqMhz = ap.frequencyMhz, rssi = ap.rssi,
                    streamsCsi = false, streamsVitals = false, bssid = ap.bssid, ssid = ap.ssid,
                )
            }
            publishDevices()
        }
    }

    private fun publishDevices() {
        _nodes.value = nodeMap.values.sortedWith(compareByDescending<Esp32Node> { it.streamsCsi || it.streamsVitals }.thenByDescending { it.lastSeenMs })
        _servers.value = serverMap.values.sortedByDescending { it.lastSeenMs }
    }

    private fun applyServerSettings(s: AppSettings) {
        if (!running) return
        val wantServer = s.mode == SensorMode.AUTO || s.mode == SensorMode.SERVER
        val host = s.serverHost.ifBlank {
            if (wantServer) _servers.value.firstOrNull { !it.hostRejected }?.host ?: "" else ""
        }
        if (wantServer && host.isNotBlank()) {
            val port = if (s.serverHost.isNotBlank()) s.serverWsPort else _servers.value.first { it.host == host }.wsPort
            server.connect(host, port, s.serverToken)
        } else {
            server.disconnect()
        }
    }

    // ---- arbitration ----------------------------------------------------------------------------

    private suspend fun arbitrationLoop() {
        var tick = 0
        while (running) {
            // Auto-connect to a server discovered after start-up.
            if (tick % 4 == 0 && settings.value.serverHost.isBlank() && serverLink.value.target == null) {
                applyServerSettings(settings.value)
            }
            synchronized(lock) {
                publishDevices()
                _sensing.value = computeState(System.currentTimeMillis())
            }
            tick++
            delay(500)
        }
    }

    private fun computeState(now: Long): SensingState {
        val mode = settings.value.mode
        val frame = serverLink.value.lastFrame
        val serverLive = serverLink.value.connected && frame != null && now - frame.receivedAtMs < LIVE_WINDOW_MS
        val csiLive = now - lastCsiMs < LIVE_WINDOW_MS
        val vitalsLive = now - lastVitalsMs < LIVE_WINDOW_MS
        val espLive = csiLive || vitalsLive

        val kind = when (mode) {
            SensorMode.SERVER -> SensorKind.SERVER
            SensorMode.ESP32 -> SensorKind.ESP32
            SensorMode.PHONE -> SensorKind.PHONE
            SensorMode.AUTO -> when {
                serverLive -> SensorKind.SERVER
                espLive -> SensorKind.ESP32
                else -> SensorKind.PHONE
            }
        }

        val prevHistory = history
        val state = when (kind) {
            SensorKind.SERVER -> {
                val f = frame?.takeIf { serverLive }
                // Server labels: present_moving / present_still / absent (main.rs).
                val score = when (levelFromServer(f?.motionLevel)) {
                    MotionLevel.HIGH -> 4.0
                    MotionLevel.MODERATE -> 2.6
                    MotionLevel.MINIMAL -> 1.6
                    MotionLevel.NONE -> 0.8
                    MotionLevel.CALIBRATING -> 0.0
                }
                SensingState(
                    active = kind,
                    reason = if (f != null) "Streaming ${serverLink.value.target?.removePrefix("ws://")} · source ${f.source ?: "?"}"
                    else serverLink.value.error ?: if (serverLink.value.target == null) "No server configured" else "Connecting…",
                    motion = MotionReading(score, levelFromServer(f?.motionLevel), f?.presence == true, f?.confidence ?: 0.0, f?.nodeCount ?: 0, 0),
                    presence = f?.presence == true,
                    confidence = f?.confidence ?: 0.0,
                    headline = when {
                        f == null -> "Waiting for server"
                        f.motionLevel == "present_moving" -> "Person moving"
                        f.motionLevel == "present_still" -> "Person still"
                        f.presence -> "Presence detected"
                        else -> "Room clear"
                    },
                    vitals = f?.vitals,
                    updateRateHz = 0.0,
                    serverSource = f?.source,
                )
            }
            SensorKind.ESP32 -> {
                val v = lastVitals?.takeIf { vitalsLive }
                val node = csiNodeKey?.let { nodeMap[it] }
                val presence = v?.presence ?: csiReading.activity
                SensingState(
                    active = kind,
                    reason = when {
                        node != null && csiLive -> "Node #${node.nodeId} · ${node.ip} · ${"%.0f".format(node.csiRateHz)} frames/s · ${node.subcarriers ?: "?"} subcarriers"
                        v != null -> "Edge vitals from node #${v.nodeId}"
                        else -> "No ESP32 is streaming to this phone (UDP :${settings.value.udpPort})"
                    },
                    motion = csiReading,
                    presence = presence,
                    confidence = if (v != null) v.presenceScore.toDouble().coerceIn(0.0, 1.0) else csiReading.confidence,
                    headline = when {
                        !espLive -> "Waiting for node"
                        v?.fall == true -> "Possible fall"
                        presence -> "Presence detected"
                        csiReading.level == MotionLevel.CALIBRATING -> "Calibrating"
                        else -> "Room calm"
                    },
                    vitals = v?.let { Vitals(it.breathingBpm.takeIf { b -> b > 0 }, it.heartRateBpm.takeIf { h -> h > 0 }, it.persons, it.fall) },
                    updateRateHz = node?.csiRateHz ?: 0.0,
                    csiWaterfall = waterfall.toList(),
                )
            }
            SensorKind.PHONE -> {
                val l = link.value
                val apCount = accessPoints.value.size
                SensingState(
                    active = kind,
                    reason = buildString {
                        if (mode == SensorMode.AUTO) append("No external sensor live · ")
                        append(if (l != null) "link ${l.ssid ?: "AP"} ${l.rssi} dBm" else "not connected")
                        append(" · $apCount APs")
                    },
                    motion = rssiReading,
                    presence = rssiReading.activity,
                    confidence = rssiReading.confidence,
                    headline = when (rssiReading.level) {
                        MotionLevel.CALIBRATING -> "Calibrating"
                        else -> if (rssiReading.activity) "Movement detected" else "No movement"
                    },
                    updateRateHz = rssiChangeTimes.size / 10.0,
                )
            }
        }
        prevHistory.addLast(state.motion.score.toFloat())
        while (prevHistory.size > HISTORY_POINTS) prevHistory.removeFirst()
        return state.copy(history = prevHistory.toList())
    }

    private fun levelFromServer(level: String?): MotionLevel = when (level?.lowercase()) {
        null -> MotionLevel.CALIBRATING
        "high", "active" -> MotionLevel.HIGH
        "present_moving", "moderate", "medium" -> MotionLevel.MODERATE
        "present_still", "low", "minimal" -> MotionLevel.MINIMAL
        else -> MotionLevel.NONE
    }

    companion object {
        const val LIVE_WINDOW_MS = 5_000L
        const val LINK_POLL_MS = 300L
        const val HISTORY_POINTS = 120
        const val WATERFALL_ROWS = 72
    }
}
