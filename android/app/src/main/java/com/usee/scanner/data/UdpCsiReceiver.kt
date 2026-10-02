package com.usee.scanner.data

import com.usee.scanner.core.CsiPackets
import com.usee.scanner.core.RuViewPacket
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetSocketAddress
import java.net.SocketTimeoutException

/**
 * Listens for RuView ESP32 datagrams on UDP (default 5005). A node streams
 * here only when provisioned with this phone's IP as its target, e.g.
 * `python provision.py --port COM7 --target-ip <phone-ip> --target-port 5005`.
 * Datagrams that do not carry a RuView magic are dropped.
 */
class UdpCsiReceiver {

    private val _error = MutableStateFlow<String?>(null)
    /** null while listening fine; otherwise a human-readable bind error. */
    val error: StateFlow<String?> = _error.asStateFlow()

    private var job: Job? = null
    private var socket: DatagramSocket? = null
    var onPacket: ((RuViewPacket, String) -> Unit)? = null

    fun start(scope: CoroutineScope, port: Int) {
        stop()
        job = scope.launch(Dispatchers.IO) {
            val s = try {
                DatagramSocket(null).apply {
                    reuseAddress = true
                    soTimeout = 1000
                    bind(InetSocketAddress(port))
                }
            } catch (e: Exception) {
                _error.value = "UDP :$port unavailable (${e.message})"
                return@launch
            }
            socket = s
            _error.value = null
            val buf = ByteArray(4096)
            val packet = DatagramPacket(buf, buf.size)
            while (isActive) {
                try {
                    packet.length = buf.size
                    s.receive(packet)
                    val parsed = CsiPackets.parse(buf, packet.length) ?: continue
                    onPacket?.invoke(parsed, packet.address?.hostAddress ?: "?")
                } catch (_: SocketTimeoutException) {
                    // loop to observe cancellation
                } catch (e: Exception) {
                    if (!isActive || s.isClosed) break
                }
            }
            s.close()
        }
    }

    fun stop() {
        job?.cancel()
        job = null
        socket?.close()
        socket = null
    }
}
