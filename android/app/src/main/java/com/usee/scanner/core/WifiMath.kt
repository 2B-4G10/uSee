package com.usee.scanner.core

import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.pow

enum class Band(val label: String) {
    GHZ_2_4("2.4 GHz"),
    GHZ_5("5 GHz"),
    GHZ_6("6 GHz"),
    GHZ_60("60 GHz"),
    UNKNOWN("—"),
}

/** Pure radio helpers. No Android dependencies so they run in JVM unit tests. */
object WifiMath {

    fun band(freqMhz: Int): Band = when (freqMhz) {
        in 2400..2500 -> Band.GHZ_2_4
        in 4900..5899 -> Band.GHZ_5
        in 5925..7125 -> Band.GHZ_6
        in 57000..71000 -> Band.GHZ_60
        else -> Band.UNKNOWN
    }

    /** IEEE 802.11 channel number for a primary centre frequency, or -1. */
    fun channel(freqMhz: Int): Int = when {
        freqMhz == 2484 -> 14
        freqMhz in 2412..2472 -> (freqMhz - 2407) / 5
        freqMhz in 4915..4980 -> (freqMhz - 4000) / 5
        freqMhz in 5035..5895 -> (freqMhz - 5000) / 5
        freqMhz == 5935 -> 2
        freqMhz in 5955..7115 -> (freqMhz - 5950) / 5
        freqMhz in 58320..70200 -> (freqMhz - 56160) / 2160
        else -> -1
    }

    /** Channel width in MHz from the `ScanResult.CHANNEL_WIDTH_*` constant. */
    fun widthMhz(channelWidthConst: Int): Int = when (channelWidthConst) {
        0 -> 20
        1 -> 40
        2 -> 80
        3 -> 160
        4 -> 160 // 80+80
        5 -> 320
        else -> 20
    }

    /** Marketing generation from `ScanResult.getWifiStandard()` (API 30+). */
    fun standardLabel(wifiStandard: Int, band: Band): String = when (wifiStandard) {
        1 -> "Legacy (a/b/g)"
        4 -> "Wi-Fi 4 (n)"
        5 -> "Wi-Fi 5 (ac)"
        6 -> if (band == Band.GHZ_6) "Wi-Fi 6E (ax)" else "Wi-Fi 6 (ax)"
        7 -> "WiGig (ad)"
        8 -> "Wi-Fi 7 (be)"
        else -> "Unknown"
    }

    /** Coarse security label from the `ScanResult.capabilities` string. */
    fun security(capabilities: String): String {
        val c = capabilities.uppercase()
        return when {
            "EAP" in c && ("WPA3" in c || "SUITE_B" in c || "SUITE-B" in c) -> "WPA3-Enterprise"
            "EAP" in c -> "WPA2-Enterprise"
            "SAE" in c || "WPA3" in c -> if ("PSK" in c) "WPA2/WPA3" else "WPA3"
            "OWE" in c -> "Enhanced Open"
            "RSN" in c || "WPA2" in c -> "WPA2"
            "WPA" in c -> "WPA"
            "WEP" in c -> "WEP"
            else -> "Open"
        }
    }

    fun isOpen(security: String) = security == "Open"

    /**
     * Free-space path-loss distance in metres. This ignores walls, antenna
     * gain and transmit power, so it is an order-of-magnitude ESTIMATE only.
     */
    fun fsplDistanceMeters(rssiDbm: Int, freqMhz: Int): Double {
        if (freqMhz <= 0) return Double.NaN
        val exp = (27.55 - 20.0 * log10(freqMhz.toDouble()) + abs(rssiDbm.toDouble())) / 20.0
        return 10.0.pow(exp).coerceIn(0.1, 9999.0)
    }

    /** 0..100 signal quality using the common linear mapping −100 dBm → 0, −50 dBm → 100. */
    fun qualityPercent(rssiDbm: Int): Int = (2 * (rssiDbm + 100)).coerceIn(0, 100)

    /** Linear amplitude used by the RuView multi-BSSID pipeline: 10^((rssi+100)/20). */
    fun rssiToAmplitude(rssiDbm: Double): Double = 10.0.pow((rssiDbm + 100.0) / 20.0)
}
