package com.usee.scanner.core

/**
 * Hardware hints derived from a BSSID / SSID. These are HINTS, not proof:
 * MAC prefixes can be spoofed and locally-administered addresses carry no
 * vendor information.
 */
object Oui {

    /** IEEE OUIs assigned to Espressif Systems (ESP8266 / ESP32 family). */
    private val ESPRESSIF = setOf(
        "08:3A:8D", "08:3A:F2", "08:B6:1F", "08:D1:F9", "08:F9:E0", "0C:8B:95",
        "0C:B8:15", "0C:DC:7E", "10:52:1C", "10:91:A8", "10:97:BD", "18:FE:34",
        "1C:9D:C2", "24:0A:C4", "24:62:AB", "24:6F:28", "24:A1:60", "24:B2:DE",
        "24:D7:EB", "24:DC:C3", "24:EC:4A", "2C:3A:E8", "2C:F4:32", "30:30:F9",
        "30:83:98", "30:AE:A4", "30:C6:F7", "34:85:18", "34:86:5D", "34:94:54",
        "34:AB:95", "34:B4:72", "3C:61:05", "3C:71:BF", "3C:84:27", "3C:E9:0E",
        "40:22:D8", "40:4C:CA", "40:91:51", "44:17:93", "48:27:E2", "48:31:B7",
        "48:3F:DA", "48:55:19", "48:CA:43", "48:E7:29", "4C:11:AE", "4C:75:25",
        "4C:EB:D6", "50:02:91", "54:32:04", "54:43:B2", "58:BF:25", "58:CF:79",
        "5C:CF:7F", "60:01:94", "60:55:F9", "64:B7:08", "64:E8:33", "68:67:25",
        "68:B6:B3", "68:C6:3A", "70:03:9F", "70:04:1D", "70:B8:F6", "74:4D:BD",
        "78:21:84", "78:E3:6D", "7C:73:98", "7C:87:CE", "7C:9E:BD", "7C:DF:A1",
        "80:64:6F", "80:7D:3A", "84:0D:8E", "84:CC:A8", "84:F3:EB", "84:F7:03",
        "84:FC:E6", "8C:4B:14", "8C:AA:B5", "8C:CE:4E", "90:38:0C", "90:97:D5",
        "94:3C:C6", "94:B5:55", "94:B9:7E", "94:E6:86", "98:CD:AC", "98:F4:AB",
        "A0:20:A6", "A0:76:4E", "A0:A3:B3", "A0:B7:65", "A4:7B:9D", "A4:CF:12",
        "A4:E5:7C", "A8:03:2A", "A8:42:E3", "A8:48:FA", "AC:0B:FB", "AC:67:B2",
        "AC:D0:74", "B0:A7:32", "B4:8A:0A", "B4:E6:2D", "B8:D6:1A", "B8:F0:09",
        "BC:DD:C2", "BC:FF:4D", "C0:49:EF", "C4:4F:33", "C4:5B:BE", "C4:D8:D5",
        "C4:DD:57", "C8:2B:96", "C8:C9:A3", "C8:F0:9E", "CC:50:E3", "CC:7B:5C",
        "CC:8D:A2", "CC:DB:A7", "D4:8A:FC", "D4:D4:DA", "D4:F9:8D", "D8:13:2A",
        "D8:A0:1D", "D8:BF:C0", "D8:F1:5B", "DC:06:75", "DC:4F:22", "DC:54:75",
        "DC:DA:0C", "E0:5A:1B", "E0:98:06", "E4:65:B8", "E8:06:90", "E8:31:CD",
        "E8:68:E7", "E8:9F:6D", "E8:DB:84", "EC:62:60", "EC:64:C9", "EC:94:CB",
        "EC:DA:3B", "EC:FA:BC", "F0:08:D1", "F0:9E:9E", "F4:12:FA", "F4:CF:A2",
        "FC:F5:C4",
    )

    /** SSID prefixes used by RuView firmware or by stock ESP-IDF soft-APs. */
    private val SENSOR_SSID_PREFIXES = listOf("ruview", "wifi-densepose", "esp_", "esp32", "espressif")

    fun normalize(bssid: String): String = bssid.trim().uppercase().replace('-', ':')

    fun prefix(bssid: String): String = normalize(bssid).take(8)

    /** Bit 1 of the first octet marks a locally administered (often randomised) MAC. */
    fun isLocallyAdministered(bssid: String): Boolean {
        val first = normalize(bssid).take(2).toIntOrNull(16) ?: return false
        return first and 0x02 != 0
    }

    fun isEspressif(bssid: String): Boolean = prefix(bssid) in ESPRESSIF

    fun looksLikeSensorSsid(ssid: String): Boolean {
        val s = ssid.lowercase()
        return SENSOR_SSID_PREFIXES.any { s.startsWith(it) }
    }

    fun vendorHint(bssid: String, ssid: String): String? = when {
        isEspressif(bssid) -> "Espressif (ESP32/ESP8266)"
        looksLikeSensorSsid(ssid) -> "Sensor-style SSID"
        isLocallyAdministered(bssid) -> "Randomised / virtual MAC"
        else -> null
    }
}
