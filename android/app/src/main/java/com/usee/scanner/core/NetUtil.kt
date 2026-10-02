package com.usee.scanner.core

/** IPv4 helpers for the read-only LAN sweep. Pure Kotlin for unit testing. */
object NetUtil {

    /** Largest sweep we will run (a /22). Bigger networks fall back to the caller's /24. */
    const val MAX_HOSTS = 1022

    fun parseIpv4(ip: String): Long? {
        val parts = ip.trim().split('.')
        if (parts.size != 4) return null
        var v = 0L
        for (p in parts) {
            val o = p.toIntOrNull() ?: return null
            if (o !in 0..255 || (p.length > 1 && p.startsWith('0'))) return null
            v = (v shl 8) or o.toLong()
        }
        return v
    }

    fun formatIpv4(v: Long): String =
        "${(v shr 24) and 255}.${(v shr 16) and 255}.${(v shr 8) and 255}.${v and 255}"

    /** RFC 1918 private ranges only. */
    fun isPrivate(ip: String): Boolean {
        val v = parseIpv4(ip) ?: return false
        val a = (v shr 24) and 255
        val b = (v shr 16) and 255
        return a == 10L || (a == 172L && b in 16..31) || (a == 192L && b == 168L)
    }

    /**
     * Hosts to probe on the phone's subnet, excluding the network, broadcast
     * and own address. Subnets larger than [MAX_HOSTS] are narrowed to the
     * phone's /24. Public address space is never swept.
     */
    fun sweepTargets(ownIp: String, prefix: Int): List<String> {
        val own = parseIpv4(ownIp) ?: return emptyList()
        if (!isPrivate(ownIp) || prefix !in 8..30) return emptyList()
        var p = prefix
        if ((1L shl (32 - p)) - 2 > MAX_HOSTS) p = 24
        val mask = (0xFFFFFFFFL shl (32 - p)) and 0xFFFFFFFFL
        val network = own and mask
        val broadcast = network or (mask.inv() and 0xFFFFFFFFL)
        return ((network + 1) until broadcast).filter { it != own }.map(::formatIpv4)
    }

    /** Validates a user-typed host (IPv4 literal or DNS name) without resolving it. */
    fun isValidHost(host: String): Boolean {
        val h = host.trim()
        if (h.isEmpty() || h.length > 253) return false
        if (parseIpv4(h) != null) return true
        return h.split('.').all { label ->
            label.isNotEmpty() && label.length <= 63 &&
                label.all { it.isLetterOrDigit() || it == '-' } &&
                !label.startsWith('-') && !label.endsWith('-')
        }
    }

    fun hostForUrl(host: String): String = if (':' in host) "[$host]" else host
}
