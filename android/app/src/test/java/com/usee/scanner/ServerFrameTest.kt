package com.usee.scanner

import com.usee.scanner.data.SensingServerClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ServerFrameTest {

    @Test
    fun parsesSensingUpdate() {
        val json = """
            {"type":"sensing_update","timestamp":1.0,"source":"esp32","tick":5,
             "nodes":[{"node_id":1,"rssi_dbm":-50,"position":[0,0,0],"amplitude":[],"subcarrier_count":64}],
             "features":{"mean_rssi":-51.5,"variance":1,"motion_band_power":0.4,"breathing_band_power":0.1,
                         "dominant_freq_hz":0.2,"change_points":0,"spectral_power":1},
             "classification":{"motion_level":"present_still","presence":true,"confidence":0.82},
             "signal_field":{"grid_size":[1,1,1],"values":[0]},
             "vital_signs":{"breathing_rate_bpm":14.2,"heart_rate_bpm":null,"breathing_confidence":0.6,
                            "heartbeat_confidence":0,"signal_quality":0.7},
             "estimated_persons":1}
        """.trimIndent()
        val f = SensingServerClient.parseFrame(json, 99)!!
        assertEquals("esp32", f.source)
        assertTrue(f.presence)
        assertEquals("present_still", f.motionLevel)
        assertEquals(0.82, f.confidence, 1e-9)
        assertEquals(-51.5, f.meanRssi!!, 1e-9)
        assertEquals(14.2, f.vitals!!.breathingBpm!!, 1e-9)
        assertNull(f.vitals.heartRateBpm)
        assertEquals(1, f.vitals.persons)
        assertEquals(1, f.nodeCount)
    }

    @Test
    fun ignoresNonSensingMessages() {
        assertNull(SensingServerClient.parseFrame("""{"type":"hello"}""", 0))
        assertNull(SensingServerClient.parseFrame("not json", 0))
    }
}
