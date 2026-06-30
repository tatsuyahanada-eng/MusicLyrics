package com.netdiag.core.net

import java.io.File

/**
 * Best-effort reader of the kernel ARP cache (`/proc/net/arp`).
 *
 * NOTE: On Android 10+ this file is usually unreadable for normal apps, so
 * callers must treat a missing MAC as "unknown" rather than "absent". A MAC
 * here means the device very recently exchanged traffic with that IP.
 */
object ArpTable {

    /** Returns a map of IPv4 -> MAC (lower-case, colon separated). */
    fun snapshot(): Map<String, String> {
        val file = File("/proc/net/arp")
        if (!file.canRead()) return emptyMap()
        return try {
            val result = LinkedHashMap<String, String>()
            file.readLines().drop(1).forEach { line ->
                val cols = line.trim().split(Regex("\\s+"))
                if (cols.size >= 4) {
                    val ip = cols[0]
                    val mac = cols[3].lowercase()
                    if (mac != "00:00:00:00:00:00" && mac.matches(MAC_REGEX)) {
                        result[ip] = mac
                    }
                }
            }
            result
        } catch (_: Exception) {
            emptyMap()
        }
    }

    private val MAC_REGEX = Regex("([0-9a-f]{2}:){5}[0-9a-f]{2}")
}
