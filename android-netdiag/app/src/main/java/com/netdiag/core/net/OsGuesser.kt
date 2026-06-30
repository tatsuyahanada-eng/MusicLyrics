package com.netdiag.core.net

/**
 * Very rough OS guess from cheap signals: the initial TTL (a ping reply's TTL,
 * which on a LAN is close to the sender's default of 64/128/255), the MAC
 * vendor and any open ports. Always a best-effort estimate, never definitive.
 */
object OsGuesser {

    fun guess(host: DiscoveredHost): String? {
        val ports = host.openPorts.toSet()
        val vendor = host.vendor?.lowercase() ?: ""
        val name = host.hostname?.lowercase() ?: ""

        // Strong hints first.
        when {
            3389 in ports || 445 in ports || 139 in ports || name.contains("win") -> return "Windows"
            vendor.contains("apple") || name.contains("iphone") -> return "iOS / macOS"
            vendor.contains("raspberry") -> return "Linux (Raspberry Pi)"
            vendor.contains("espressif") || vendor.contains("tuya") -> return "組込み (RTOS)"
            name.contains("android") || name.contains("pixel") || name.contains("galaxy") -> return "Android"
            22 in ports && (445 !in ports) -> return "Linux / Unix"
        }

        // Fall back to the initial-TTL bucket.
        return when (host.ttl) {
            null -> null
            in 1..64 -> "Linux / Android / iOS 系"
            in 65..128 -> "Windows 系"
            else -> "ネットワーク機器 / Unix 系"
        }
    }
}
