package com.netdiag.core.net

/** Small helpers for IPv4 <-> Int conversion and subnet enumeration. */
object NetUtils {

    fun ipToInt(ip: String): Int {
        val parts = ip.split(".")
        require(parts.size == 4) { "Not an IPv4 address: $ip" }
        var result = 0
        for (p in parts) {
            result = (result shl 8) or (p.toInt() and 0xFF)
        }
        return result
    }

    fun intToIp(value: Int): String {
        return "${(value ushr 24) and 0xFF}.${(value ushr 16) and 0xFF}." +
            "${(value ushr 8) and 0xFF}.${value and 0xFF}"
    }

    /**
     * Returns every usable host address inside the subnet that [ipv4]/[prefix]
     * belongs to, excluding the network and broadcast addresses.
     * The result is capped at [maxHosts] to avoid enumerating huge ranges.
     */
    fun hostAddresses(ipv4: String, prefix: Int, maxHosts: Int = 1024): List<String> {
        if (prefix !in 1..32) return emptyList()
        val base = ipToInt(ipv4)
        val mask = if (prefix == 0) 0 else (-1 shl (32 - prefix))
        val network = base and mask
        val broadcast = network or mask.inv()
        // /31 and /32 have no "usable host range" in the classic sense.
        if (prefix >= 31) return listOf(ipv4)
        val first = network + 1
        val last = broadcast - 1
        val result = ArrayList<String>()
        var addr = first
        while (addr <= last && result.size < maxHosts) {
            result.add(intToIp(addr))
            addr++
        }
        return result
    }

    /** Human readable subnet mask for a CIDR prefix, e.g. 24 -> 255.255.255.0 */
    fun prefixToMask(prefix: Int): String {
        val mask = if (prefix == 0) 0 else (-1 shl (32 - prefix))
        return intToIp(mask)
    }
}
