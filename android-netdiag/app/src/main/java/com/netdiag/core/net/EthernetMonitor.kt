package com.netdiag.core.net

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

/** Whether a wired (USB-C-to-Ethernet) adapter is currently attached, and its config. */
data class EthernetState(
    val connected: Boolean,
    val network: Network? = null,
    val info: NetInfo? = null,
)

/**
 * Watches for a USB-C-to-Ethernet adapter's network independently of whichever
 * network Android currently treats as the system default.
 * [ConnectivityManager.activeNetwork] can keep preferring Wi-Fi even with a
 * cable plugged in — especially when the wired LAN has no internet gateway,
 * which is exactly the "just check this LAN" case this screen is for. The
 * request below explicitly drops the internet-capability requirement so an
 * isolated/offline LAN still gets detected and its DHCP result (or the lack
 * of one) shown, rather than being invisible to the app.
 */
class EthernetMonitor(context: Context) {

    private val cm = context.applicationContext
        .getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    fun observe(): Flow<EthernetState> = callbackFlow {
        fun emit(network: Network?, lp: LinkProperties?) {
            trySend(
                if (network == null) {
                    EthernetState(connected = false)
                } else {
                    EthernetState(
                        connected = true,
                        network = network,
                        info = lp?.let { netInfoFrom(it, NetworkTransport.ETHERNET) },
                    )
                }
            )
        }

        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                emit(network, cm.getLinkProperties(network))
            }

            override fun onLinkPropertiesChanged(network: Network, linkProperties: LinkProperties) {
                emit(network, linkProperties)
            }

            override fun onLost(network: Network) {
                emit(null, null)
            }
        }

        trySend(EthernetState(connected = false))
        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_ETHERNET)
            // Match the adapter even without internet access — the whole point
            // is to be able to check an isolated/offline LAN with just the phone.
            .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()
        runCatching { cm.registerNetworkCallback(request, callback) }

        awaitClose { runCatching { cm.unregisterNetworkCallback(callback) } }
    }

    /**
     * Pins this process's own network traffic to [network] (every socket this
     * app opens — scan/DNS/speed test — goes out over it; the shelled-out
     * `ping` binary runs as a separate process and is unaffected, see
     * [CableTest]), or clears the pin and returns to normal OS routing when
     * [network] is null.
     */
    fun bind(network: Network?): Boolean =
        runCatching { cm.bindProcessToNetwork(network) }.getOrDefault(false)

    /**
     * Best-effort negotiated link rate (downstream, upstream) in Mbps, from
     * [NetworkCapabilities]. Many Ethernet drivers report the adapter's real
     * negotiated PHY speed here (a cable with a damaged pair often shows up as
     * a 1000→100 Mbps downgrade), but some report a generic default — treat
     * this as a reference value, not a guaranteed measurement.
     */
    fun linkRateMbps(network: Network): Pair<Int?, Int?> {
        val caps = cm.getNetworkCapabilities(network) ?: return null to null
        val down = caps.linkDownstreamBandwidthKbps.takeIf { it > 0 }?.let { it / 1000 }
        val up = caps.linkUpstreamBandwidthKbps.takeIf { it > 0 }?.let { it / 1000 }
        return down to up
    }
}
