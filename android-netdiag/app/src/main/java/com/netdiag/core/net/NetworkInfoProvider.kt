package com.netdiag.core.net

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.NetworkCapabilities
import java.net.Inet4Address

/** Which physical/radio link a [NetInfo] snapshot came from. */
enum class NetworkTransport(val label: String) {
    WIFI("Wi-Fi"),
    ETHERNET("有線LAN（イーサネット）"),
    CELLULAR("モバイルデータ"),
    OTHER("その他"),
    UNKNOWN("不明"),
}

/** Snapshot of a network's current IPv4 configuration. */
data class NetInfo(
    val ipv4: String? = null,
    val prefixLength: Int? = null,
    val gateway: String? = null,
    val dnsServers: List<String> = emptyList(),
    val interfaceName: String? = null,
    val transport: NetworkTransport = NetworkTransport.UNKNOWN,
) {
    val hasIpv4: Boolean get() = ipv4 != null && prefixLength != null
    val subnetMask: String? get() = prefixLength?.let { NetUtils.prefixToMask(it) }
}

/**
 * Extracts IPv4 address/prefix/gateway/DNS from a network's [LinkProperties].
 * Shared by [NetworkInfoProvider] (the system's current default/bound network)
 * and [EthernetMonitor] (a specific wired adapter, which may not be the system
 * default — e.g. when it has no internet gateway).
 */
fun netInfoFrom(lp: LinkProperties, transport: NetworkTransport): NetInfo {
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
        transport = transport,
    )
}

fun transportOf(caps: NetworkCapabilities?): NetworkTransport = when {
    caps == null -> NetworkTransport.UNKNOWN
    caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> NetworkTransport.ETHERNET
    caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> NetworkTransport.WIFI
    caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> NetworkTransport.CELLULAR
    else -> NetworkTransport.OTHER
}

/**
 * Reads the current network's IPv4 address, prefix, gateway and DNS servers
 * using the non-deprecated [ConnectivityManager]/[LinkProperties] APIs.
 *
 * If this process is pinned to a specific network — see
 * [EthernetMonitor.bind], used so every tool in the app (ping/scan/DNS/speed
 * test) tests a plugged-in USB-C Ethernet adapter instead of Wi-Fi — that
 * network's info is read instead of the system-wide default, keeping this
 * card consistent with what the rest of the app is actually testing.
 */
class NetworkInfoProvider(private val context: Context) {

    fun current(): NetInfo {
        val cm = context.getSystemService(ConnectivityManager::class.java)
            ?: return NetInfo()
        val network = cm.boundNetworkForProcess ?: cm.activeNetwork ?: return NetInfo()
        val lp = cm.getLinkProperties(network) ?: return NetInfo()
        val transport = transportOf(cm.getNetworkCapabilities(network))
        return netInfoFrom(lp, transport)
    }
}
