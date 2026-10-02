package com.usee.scanner.core

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.sqrt

/**
 * Decoders for the RuView ESP32 UDP wire formats (little-endian):
 *
 *  - 0xC5110001  ADR-018 raw CSI frame       (firmware/esp32-csi-node/main/csi_collector.c)
 *  - 0xC5110002  ADR-039 edge vitals, 32 B   (edge_processing.h)
 *  - 0xC5110004  ADR-063 fused vitals, 48 B  (first 32 B share the vitals layout)
 *  - other 0xC511xxxx magics are recognised as RuView traffic but not decoded.
 */
sealed interface RuViewPacket {
    val nodeId: Int
}

class CsiFrame(
    override val nodeId: Int,
    val antennas: Int,
    val subcarriers: Int,
    val freqMhz: Int,
    val sequence: Long,
    val rssi: Int,
    val noiseFloor: Int,
    /** |H| per subcarrier for the first antenna. */
    val amplitudes: FloatArray,
) : RuViewPacket

data class VitalsPacket(
    override val nodeId: Int,
    val presence: Boolean,
    val fall: Boolean,
    val motion: Boolean,
    val breathingBpm: Double,
    val heartRateBpm: Double,
    val rssi: Int,
    val persons: Int,
    val motionEnergy: Float,
    val presenceScore: Float,
    val timestampMs: Long,
    val fused: Boolean,
) : RuViewPacket

data class OtherRuViewPacket(override val nodeId: Int, val magic: Long) : RuViewPacket

object CsiPackets {
    const val MAGIC_CSI = 0xC5110001L
    const val MAGIC_VITALS = 0xC5110002L
    const val MAGIC_FUSED = 0xC5110004L
    private const val FAMILY_MASK = 0xFFFF0000L
    private const val FAMILY = 0xC5110000L
    const val CSI_HEADER = 20
    /** Upper bound on subcarriers we accept; guards against malformed headers. */
    const val MAX_SUBCARRIERS = 1024

    /** Returns null when the datagram is not RuView traffic or is malformed. */
    fun parse(data: ByteArray, length: Int = data.size): RuViewPacket? {
        if (length < 8 || length > data.size) return null
        val buf = ByteBuffer.wrap(data, 0, length).order(ByteOrder.LITTLE_ENDIAN)
        val magic = buf.getInt(0).toLong() and 0xFFFFFFFFL
        if (magic and FAMILY_MASK != FAMILY) return null
        val nodeId = data[4].toInt() and 0xFF
        return when (magic) {
            MAGIC_CSI -> parseCsi(buf, data, length, nodeId)
            MAGIC_VITALS -> if (length >= 32) parseVitals(buf, nodeId, fused = false) else null
            MAGIC_FUSED -> if (length >= 48) parseVitals(buf, nodeId, fused = true) else null
            else -> OtherRuViewPacket(nodeId, magic)
        }
    }

    private fun parseCsi(buf: ByteBuffer, data: ByteArray, length: Int, nodeId: Int): CsiFrame? {
        if (length < CSI_HEADER + 2) return null
        val antennas = (data[5].toInt() and 0xFF).coerceAtLeast(1)
        val declared = buf.getShort(6).toInt() and 0xFFFF
        val freq = buf.getInt(8)
        val seq = buf.getInt(12).toLong() and 0xFFFFFFFFL
        val rssi = data[16].toInt()
        val noise = data[17].toInt()
        // Never trust the header beyond what the datagram actually carries.
        val available = (length - CSI_HEADER) / (2 * antennas)
        val n = minOf(declared, available, MAX_SUBCARRIERS)
        if (n <= 0) return null
        val amps = FloatArray(n)
        for (k in 0 until n) {
            val o = CSI_HEADER + 2 * k
            val a = data[o].toFloat()      // int8 (imag on ESP-IDF)
            val b = data[o + 1].toFloat()  // int8 (real on ESP-IDF)
            amps[k] = sqrt(a * a + b * b)
        }
        return CsiFrame(nodeId, antennas, n, freq, seq, rssi, noise, amps)
    }

    private fun parseVitals(buf: ByteBuffer, nodeId: Int, fused: Boolean): VitalsPacket {
        val flags = buf.get(5).toInt() and 0xFF
        val br = (buf.getShort(6).toInt() and 0xFFFF) / 100.0
        val hr = (buf.getInt(8).toLong() and 0xFFFFFFFFL) / 10000.0
        return VitalsPacket(
            nodeId = nodeId,
            presence = flags and 0x1 != 0,
            fall = flags and 0x2 != 0,
            motion = flags and 0x4 != 0,
            breathingBpm = br,
            heartRateBpm = hr,
            rssi = buf.get(12).toInt(),
            persons = buf.get(13).toInt() and 0xFF,
            motionEnergy = buf.getFloat(16),
            presenceScore = buf.getFloat(20),
            timestampMs = buf.getInt(24).toLong() and 0xFFFFFFFFL,
            fused = fused,
        )
    }
}
