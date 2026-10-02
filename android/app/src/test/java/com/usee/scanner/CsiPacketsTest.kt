package com.usee.scanner

import com.usee.scanner.core.CsiFrame
import com.usee.scanner.core.CsiPackets
import com.usee.scanner.core.OtherRuViewPacket
import com.usee.scanner.core.VitalsPacket
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class CsiPacketsTest {

    private fun csiFrame(nodeId: Int, subcarriers: Int, iq: ByteArray, declaredSc: Int = subcarriers): ByteArray {
        val b = ByteBuffer.allocate(20 + iq.size).order(ByteOrder.LITTLE_ENDIAN)
        b.putInt(0xC5110001.toInt())
        b.put(nodeId.toByte())
        b.put(1)
        b.putShort(declaredSc.toShort())
        b.putInt(2437)
        b.putInt(42)
        b.put((-55).toByte())
        b.put((-92).toByte())
        b.putShort(0)
        b.put(iq)
        return b.array()
    }

    @Test
    fun decodesCsiHeaderAndAmplitudes() {
        val iq = byteArrayOf(3, 4, 0, 0, -6, 8)
        val p = CsiPackets.parse(csiFrame(7, 3, iq)) as CsiFrame
        assertEquals(7, p.nodeId)
        assertEquals(3, p.subcarriers)
        assertEquals(2437, p.freqMhz)
        assertEquals(42L, p.sequence)
        assertEquals(-55, p.rssi)
        assertEquals(-92, p.noiseFloor)
        assertEquals(5f, p.amplitudes[0], 1e-6f)
        assertEquals(0f, p.amplitudes[1], 1e-6f)
        assertEquals(10f, p.amplitudes[2], 1e-6f)
    }

    @Test
    fun truncatedPayloadNeverOverreads() {
        // Header claims 64 subcarriers but only 2 are present.
        val p = CsiPackets.parse(csiFrame(1, 2, byteArrayOf(1, 1, 2, 2), declaredSc = 64)) as CsiFrame
        assertEquals(2, p.subcarriers)
    }

    @Test
    fun decodesVitalsPacket() {
        val b = ByteBuffer.allocate(32).order(ByteOrder.LITTLE_ENDIAN)
        b.putInt(0xC5110002.toInt())
        b.put(3)                       // node
        b.put(0b101)                   // presence + motion
        b.putShort(1550)               // 15.50 bpm
        b.putInt(720000)               // 72.0000 bpm
        b.put((-61).toByte())
        b.put(2)
        b.putShort(0)
        b.putFloat(0.25f)
        b.putFloat(0.8f)
        b.putInt(123456)
        b.putInt(0)
        val v = CsiPackets.parse(b.array()) as VitalsPacket
        assertEquals(3, v.nodeId)
        assertTrue(v.presence)
        assertTrue(v.motion)
        assertTrue(!v.fall)
        assertEquals(15.5, v.breathingBpm, 1e-9)
        assertEquals(72.0, v.heartRateBpm, 1e-9)
        assertEquals(-61, v.rssi)
        assertEquals(2, v.persons)
        assertEquals(0.8f, v.presenceScore, 1e-6f)
        assertEquals(123456L, v.timestampMs)
    }

    @Test
    fun rejectsForeignAndShortDatagrams() {
        assertNull(CsiPackets.parse(byteArrayOf(1, 2, 3)))
        assertNull(CsiPackets.parse(ByteArray(40) { 0x11 }))
        val shortVitals = ByteBuffer.allocate(16).order(ByteOrder.LITTLE_ENDIAN).putInt(0xC5110002.toInt()).array()
        assertNull(CsiPackets.parse(shortVitals))
    }

    @Test
    fun recognisesOtherRuViewMagics() {
        val b = ByteBuffer.allocate(48).order(ByteOrder.LITTLE_ENDIAN).putInt(0xC5110006.toInt()).put(9).array()
        val p = CsiPackets.parse(b) as OtherRuViewPacket
        assertEquals(9, p.nodeId)
    }
}
