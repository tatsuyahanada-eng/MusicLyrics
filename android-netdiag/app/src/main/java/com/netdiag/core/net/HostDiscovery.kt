package com.netdiag.core.net

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket

/** A device found on the local network. */
data class DiscoveredHost(
    val ip: String,
    val hostname: String? = null,
    val mac: String? = null,
    val vendor: String? = null,
    val ttl: Int? = null,
    val openPorts: List<Int> = emptyList(),
    val isGateway: Boolean = false,
    val isSelf: Boolean = false,
)

/** Progress events emitted while scanning the local subnet. */
sealed interface ScanEvent {
    data class Progress(val scanned: Int, val total: Int) : ScanEvent
    data class Found(val host: DiscoveredHost) : ScanEvent
    data class Done(val aliveCount: Int, val total: Int) : ScanEvent
}

/**
 * Discovers live hosts on the local subnet using a combination of ICMP echo
 * ([InetAddress.isReachable]) and TCP probes against common ports. A TCP
 * "connection refused" still proves the host exists.
 */
object HostDiscovery {

    private val PROBE_PORTS = intArrayOf(80, 443, 22, 445, 139, 53, 8080, 7, 9100, 62078)

    /** Scans the whole subnet the device is attached to. */
    fun scan(
        info: NetInfo,
        parallelism: Int = 64,
        reachableTimeoutMs: Int = 600,
    ): Flow<ScanEvent> {
        val ip = info.ipv4
        val prefix = info.prefixLength
        if (ip == null || prefix == null) return flow { emit(ScanEvent.Done(0, 0)) }
        return sweep(NetUtils.hostAddresses(ip, prefix), info, parallelism, reachableTimeoutMs)
    }

    /** Scans an explicit, user-specified IPv4 range (inclusive). */
    fun scanRange(
        info: NetInfo,
        startIp: String,
        endIp: String,
        parallelism: Int = 64,
        reachableTimeoutMs: Int = 600,
    ): Flow<ScanEvent> =
        sweep(NetUtils.rangeAddresses(startIp, endIp), info, parallelism, reachableTimeoutMs)

    private fun sweep(
        hosts: List<String>,
        info: NetInfo,
        parallelism: Int,
        reachableTimeoutMs: Int,
    ): Flow<ScanEvent> = flow {
        val total = hosts.size
        emit(ScanEvent.Progress(0, total))
        if (total == 0) {
            emit(ScanEvent.Done(0, 0))
            return@flow
        }

        val gate = Semaphore(parallelism)
        var scanned = 0
        var alive = 0

        coroutineScope {
            val deferred = hosts.map { target ->
                async {
                    gate.withPermit { probe(target, reachableTimeoutMs) }
                }
            }
            for ((idx, d) in deferred.withIndex()) {
                val alivePort = d.await()
                scanned++
                if (alivePort != PROBE_DEAD) {
                    alive++
                    // Read ARP per alive host: the cache is populated only
                    // after we have just exchanged packets with it.
                    val host = enrich(hosts[idx], info, ArpTable.lookup(hosts[idx]), alivePort)
                    emit(ScanEvent.Found(host))
                }
                emit(ScanEvent.Progress(scanned, total))
            }
        }
        emit(ScanEvent.Done(alive, total))
    }.flowOn(Dispatchers.IO)

    private const val PROBE_DEAD = -1
    private const val PROBE_ICMP = 0

    /**
     * Returns [PROBE_DEAD] if the host did not respond, [PROBE_ICMP] if it
     * answered ICMP, or the TCP port number that proved it alive. The port is
     * later used as a free hint for device classification.
     */
    private fun probe(ip: String, timeoutMs: Int): Int {
        try {
            if (InetAddress.getByName(ip).isReachable(timeoutMs)) return PROBE_ICMP
        } catch (_: Exception) {
            // fall through to TCP probing
        }
        // Per-port budget so a host with several filtered ports still
        // resolves quickly.
        val perPort = (timeoutMs / 2).coerceIn(120, 400)
        for (port in PROBE_PORTS) {
            try {
                Socket().use { s ->
                    s.connect(InetSocketAddress(ip, port), perPort)
                }
                return port // handshake completed -> host up
            } catch (e: java.net.ConnectException) {
                // "Connection refused" means the host is up but the port is closed.
                if (e.message?.contains("refused", ignoreCase = true) == true) return PROBE_ICMP
            } catch (_: Exception) {
                // timeout / unreachable -> try next port
            }
        }
        return PROBE_DEAD
    }

    private fun enrich(ip: String, info: NetInfo, mac: String?, alivePort: Int): DiscoveredHost {
        val hostname = try {
            val name = InetAddress.getByName(ip).canonicalHostName
            if (name == ip) null else name
        } catch (_: Exception) {
            null
        }
        return DiscoveredHost(
            ip = ip,
            hostname = hostname,
            mac = mac,
            vendor = OuiVendors.lookup(mac),
            openPorts = if (alivePort > 0) listOf(alivePort) else emptyList(),
            isGateway = ip == info.gateway,
            isSelf = ip == info.ipv4,
        )
    }
}
