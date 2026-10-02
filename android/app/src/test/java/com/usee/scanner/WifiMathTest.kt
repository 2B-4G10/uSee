package com.usee.scanner

import com.usee.scanner.core.Band
import com.usee.scanner.core.NetUtil
import com.usee.scanner.core.Oui
import com.usee.scanner.core.WifiMath
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WifiMathTest {

    @Test
    fun channelsAndBands() {
        assertEquals(1, WifiMath.channel(2412))
        assertEquals(6, WifiMath.channel(2437))
        assertEquals(13, WifiMath.channel(2472))
        assertEquals(14, WifiMath.channel(2484))
        assertEquals(36, WifiMath.channel(5180))
        assertEquals(165, WifiMath.channel(5825))
        assertEquals(1, WifiMath.channel(5955))
        assertEquals(233, WifiMath.channel(7115))
        assertEquals(Band.GHZ_2_4, WifiMath.band(2437))
        assertEquals(Band.GHZ_5, WifiMath.band(5180))
        assertEquals(Band.GHZ_6, WifiMath.band(5955))
    }

    @Test
    fun securityLabels() {
        assertEquals("WPA2", WifiMath.security("[WPA2-PSK-CCMP][RSN-PSK-CCMP][ESS]"))
        assertEquals("WPA3", WifiMath.security("[RSN-SAE-CCMP][ESS]"))
        assertEquals("WPA2/WPA3", WifiMath.security("[RSN-PSK+SAE-CCMP][ESS]"))
        assertEquals("WPA2-Enterprise", WifiMath.security("[WPA2-EAP-CCMP][ESS]"))
        assertEquals("Enhanced Open", WifiMath.security("[RSN-OWE-CCMP][ESS]"))
        assertEquals("Open", WifiMath.security("[ESS]"))
    }

    @Test
    fun fsplDistanceIsMonotonic() {
        val near = WifiMath.fsplDistanceMeters(-40, 2437)
        val far = WifiMath.fsplDistanceMeters(-80, 2437)
        assertTrue(far > near)
        // -40 dBm at 2437 MHz ≈ 0.98 m in free space
        assertEquals(0.98, near, 0.05)
    }

    @Test
    fun ouiHints() {
        assertTrue(Oui.isEspressif("24:0a:c4:12:34:56"))
        assertTrue(Oui.isEspressif("A4-CF-12-00-00-01"))
        assertFalse(Oui.isEspressif("00:11:22:33:44:55"))
        assertTrue(Oui.isLocallyAdministered("DA:A1:19:00:00:00"))
        assertTrue(Oui.looksLikeSensorSsid("ruview-c6-twt"))
    }

    @Test
    fun sweepTargetsArePrivateAndBounded() {
        val t = NetUtil.sweepTargets("192.168.1.42", 24)
        assertEquals(253, t.size)
        assertFalse("192.168.1.42" in t)
        assertFalse("192.168.1.0" in t)
        assertFalse("192.168.1.255" in t)
        assertEquals(253, NetUtil.sweepTargets("10.0.5.9", 8).size) // narrowed to /24
        assertTrue(NetUtil.sweepTargets("8.8.8.8", 24).isEmpty())
        assertTrue(NetUtil.sweepTargets("not-an-ip", 24).isEmpty())
        assertEquals(1021, NetUtil.sweepTargets("172.16.2.3", 22).size)
    }

    @Test
    fun hostValidation() {
        assertTrue(NetUtil.isValidHost("192.168.0.10"))
        assertTrue(NetUtil.isValidHost("ruview-home.local"))
        assertFalse(NetUtil.isValidHost("bad host"))
        assertFalse(NetUtil.isValidHost("http://x"))
        assertFalse(NetUtil.isValidHost(""))
    }
}
