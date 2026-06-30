package com.netdiag.core.net.mdns

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.net.Inet4Address
import java.util.concurrent.ConcurrentHashMap

/** A friendly name advertised over mDNS/Bonjour, mapped to its IPv4 address. */
data class MdnsHost(val ip: String, val name: String, val serviceType: String)

/**
 * Discovers devices that advertise common Bonjour/mDNS services (printers,
 * Chromecasts, AirPlay, SMB shares, etc.) and maps them to IPv4 addresses so
 * the scanner can show "Printer-Floor2" instead of a bare 192.168.x.x.
 */
class MdnsDiscovery(context: Context) {

    private val nsd = context.applicationContext
        .getSystemService(Context.NSD_SERVICE) as NsdManager

    // resolveService is single-flight on Android < 14; serialise to be safe.
    private val resolveLock = Mutex()

    suspend fun discover(durationMs: Long = 4000): List<MdnsHost> = withContext(Dispatchers.IO) {
        val found = ConcurrentHashMap<String, MdnsHost>() // key: "name@type"
        val listeners = ArrayList<NsdManager.DiscoveryListener>()
        val pending = Channel<NsdServiceInfo>(Channel.UNLIMITED)

        for (type in SERVICE_TYPES) {
            val listener = object : NsdManager.DiscoveryListener {
                override fun onStartDiscoveryFailed(s: String?, e: Int) {}
                override fun onStopDiscoveryFailed(s: String?, e: Int) {}
                override fun onDiscoveryStarted(s: String?) {}
                override fun onDiscoveryStopped(s: String?) {}
                override fun onServiceLost(info: NsdServiceInfo?) {}
                override fun onServiceFound(info: NsdServiceInfo?) {
                    if (info != null) pending.trySend(info)
                }
            }
            try {
                nsd.discoverServices(type, NsdManager.PROTOCOL_DNS_SD, listener)
                listeners.add(listener)
            } catch (_: Exception) {
                // type unsupported on this device; ignore
            }
        }

        // Drain and resolve whatever turns up within the time budget. The
        // channel iteration is a suspension point, so the timeout cancels
        // cleanly even while we are waiting for the next service.
        withTimeoutOrNull(durationMs) {
            for (info in pending) {
                val host = resolve(info)
                if (host != null) found["${host.name}@${host.serviceType}"] = host
            }
        }

        listeners.forEach { runCatching { nsd.stopServiceDiscovery(it) } }
        pending.close()
        found.values.toList()
    }

    private suspend fun resolve(info: NsdServiceInfo): MdnsHost? = resolveLock.withLock {
        val deferred = CompletableDeferred<MdnsHost?>()
        val listener = object : NsdManager.ResolveListener {
            override fun onResolveFailed(serviceInfo: NsdServiceInfo?, errorCode: Int) {
                deferred.complete(null)
            }
            override fun onServiceResolved(resolved: NsdServiceInfo?) {
                @Suppress("DEPRECATION")
                val addr = resolved?.host
                val ip = (addr as? Inet4Address)?.hostAddress
                if (ip != null) {
                    deferred.complete(
                        MdnsHost(ip, cleanName(resolved.serviceName), resolved.serviceType ?: "")
                    )
                } else {
                    deferred.complete(null)
                }
            }
        }
        try {
            @Suppress("DEPRECATION")
            nsd.resolveService(info, listener)
        } catch (_: Exception) {
            deferred.complete(null)
        }
        withTimeoutOrNull(2500) { deferred.await() }
    }

    private fun cleanName(name: String?): String =
        (name ?: "").replace("\\032", " ").trim().ifBlank { "(mDNS device)" }

    companion object {
        private val SERVICE_TYPES = listOf(
            "_http._tcp.",
            "_workstation._tcp.",
            "_printer._tcp.",
            "_ipp._tcp.",
            "_smb._tcp.",
            "_airplay._tcp.",
            "_googlecast._tcp.",
            "_raop._tcp.",
            "_ssh._tcp.",
            "_afpovertcp._tcp.",
        )
    }
}
