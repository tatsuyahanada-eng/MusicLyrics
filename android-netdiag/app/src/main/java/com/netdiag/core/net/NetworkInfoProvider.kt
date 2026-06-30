package com.netdiag.core.net

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import java.net.Inet4Address

/** Snapshot of the device's current IPv4 network configuration. */
data class NetInfo(
    val ipv4: String? = null,
    val prefixLength: Int? = null,
    val gateway: String? = null,
    val dnsServers: List<String> = emptyList(),
    val interfaceName: String? = null,
) {
    val hasIpv4: Boolean get() = ipv4 != null && prefixLength != null
    val subnetMask: String? get() = prefixLength?.let { NetUtils.prefixToMask(it) }
}

/**
 * Reads the active network's IPv4 address, prefix, gateway and DNS servers
 * using the non-deprecated [ConnectivityManager]/[LinkProperties] APIs.
 */
class NetworkInfoProvider(private val context: Context) {

    fun current(): NetInfo {
        val cm = context.getSystemService(ConnectivityManager::class.java)
            ?: return NetInfo()
        val network = cm.activeNetwork ?: return NetInfo()
        val lp = cm.getLinkProperties(network) ?: return NetInfo()

        var ip: String? = null
        var prefix: Int? = null
        for (la in lp.linkAddresses) {
            val addr = la.address
            if (addr is Inet4Address && !addr.isLoopbackAddress) {
                ip = addr.hostAddress
                prefix = la.prefixLength
                break
            }
        }

        val gateway = lp.routes
            .firstOrNull { it.isDefaultRoute && it.gateway is Inet4Address }
            ?.gateway?.hostAddress

        val dns = lp.dnsServers
            .filterIsInstance<Inet4Address>()
            .mapNotNull { it.hostAddress }

        return NetInfo(
            ipv4 = ip,
            prefixLength = prefix,
            gateway = gateway,
            dnsServers = dns,
            interfaceName = lp.interfaceName,
        )
    }
}
