package com.usee.scanner.data

import com.usee.scanner.core.NetUtil
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import java.util.concurrent.TimeUnit

data class ServerLink(
    val target: String? = null,
    val connected: Boolean = false,
    val error: String? = null,
    val lastFrame: ServerFrame? = null,
)

/**
 * Streams `ws://host:8765/ws/sensing` from a RuView sensing server with
 * capped exponential back-off. An optional bearer token is sent in the
 * `Authorization` header (ADR-272).
 */
class SensingServerClient(private val scope: CoroutineScope) {

    private val http = OkHttpClient.Builder()
        .connectTimeout(4, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .pingInterval(15, TimeUnit.SECONDS)
        .build()

    private val _state = MutableStateFlow(ServerLink())
    val state: StateFlow<ServerLink> = _state.asStateFlow()

    var onFrame: ((ServerFrame) -> Unit)? = null

    private var socket: WebSocket? = null
    private var loop: Job? = null
    private var generation = 0

    fun connect(host: String, wsPort: Int, token: String?) {
        if (!NetUtil.isValidHost(host) || wsPort !in 1..65535) {
            _state.value = ServerLink(error = "Invalid server address")
            return
        }
        val target = "ws://${NetUtil.hostForUrl(host)}:$wsPort/ws/sensing"
        if (_state.value.target == target && loop?.isActive == true) return
        disconnect()
        val gen = ++generation
        _state.value = ServerLink(target = target)
        loop = scope.launch {
            var backoff = 1_000L
            while (gen == generation) {
                val closed = kotlinx.coroutines.CompletableDeferred<Unit>()
                val req = Request.Builder().url(target).apply {
                    if (!token.isNullOrBlank()) header("Authorization", "Bearer ${token.trim()}")
                }.build()
                socket = http.newWebSocket(req, object : WebSocketListener() {
                    override fun onOpen(webSocket: WebSocket, response: Response) {
                        backoff = 1_000L
                        if (gen == generation) _state.value = _state.value.copy(connected = true, error = null)
                    }

                    override fun onMessage(webSocket: WebSocket, text: String) {
                        if (gen != generation) return
                        val frame = parseFrame(text, System.currentTimeMillis()) ?: return
                        _state.value = _state.value.copy(connected = true, lastFrame = frame)
                        onFrame?.invoke(frame)
                    }

                    override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                        webSocket.close(1000, null)
                    }

                    override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                        closed.complete(Unit)
                    }

                    override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                        if (gen == generation) {
                            _state.value = _state.value.copy(connected = false, error = describe(t, response))
                        }
                        closed.complete(Unit)
                    }
                })
                closed.await()
                if (gen != generation) break
                _state.value = _state.value.copy(connected = false)
                delay(backoff)
                backoff = (backoff * 2).coerceAtMost(30_000L)
            }
        }
    }

    fun disconnect() {
        generation++
        loop?.cancel()
        loop = null
        socket?.close(1000, "bye")
        socket = null
        _state.value = ServerLink()
    }

    private fun describe(t: Throwable, r: Response?): String = when (r?.code) {
        401 -> "Server requires a token (set it in Settings)"
        403 -> "Token rejected or lacks the stream scope"
        421 -> "Server rejected this address — start it with --allowed-host <ip>"
        null -> t.message ?: t.javaClass.simpleName
        else -> "HTTP ${r.code} from server"
    }

    companion object {
        /** Parses a SensingUpdate JSON (v2/crates/wifi-densepose-sensing-server/src/types.rs). */
        fun parseFrame(text: String, now: Long): ServerFrame? {
            val o = runCatching { JSONObject(text) }.getOrNull() ?: return null
            val cls = o.optJSONObject("classification") ?: return null
            val feats = o.optJSONObject("features")
            val vs = o.optJSONObject("vital_signs")
            val vitals = vs?.let {
                Vitals(
                    breathingBpm = it.optDoubleOrNull("breathing_rate_bpm"),
                    heartRateBpm = it.optDoubleOrNull("heart_rate_bpm"),
                    persons = o.optIntOrNull("estimated_persons"),
                )
            } ?: o.optIntOrNull("estimated_persons")?.let { Vitals(null, null, it) }
            return ServerFrame(
                source = o.optString("source").ifBlank { null },
                presence = cls.optBoolean("presence", false),
                motionLevel = cls.optString("motion_level", "unknown"),
                confidence = cls.optDouble("confidence", 0.0).takeIf { !it.isNaN() } ?: 0.0,
                meanRssi = feats?.optDoubleOrNull("mean_rssi"),
                motionBandPower = feats?.optDoubleOrNull("motion_band_power"),
                vitals = vitals,
                nodeCount = o.optJSONArray("nodes")?.length() ?: 0,
                receivedAtMs = now,
            )
        }

        private fun JSONObject.optDoubleOrNull(key: String): Double? =
            if (has(key) && !isNull(key)) optDouble(key).takeIf { !it.isNaN() } else null

        private fun JSONObject.optIntOrNull(key: String): Int? =
            if (has(key) && !isNull(key)) optInt(key) else null
    }
}
