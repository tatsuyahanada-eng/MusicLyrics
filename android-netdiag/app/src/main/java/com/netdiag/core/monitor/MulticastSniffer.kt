package com.netdiag.core.monitor

import android.content.Context
import android.net.wifi.WifiManager
import com.netdiag.core.net.NetworkInfoProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOn
import java.net.DatagramPacket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.MulticastSocket
import java.net.NetworkInterface
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/** One multicast datagram actually seen on the wire. */
data class ObservedPacket(
    val timeMillis: Long,
    val sourceIp: String,
    val port: Int,
    val sizeBytes: Int,
    val protocol: String,      // "mDNS" / "SSDP"
    val summary: String,
    val malformed: Boolean,
)

/**
 * Listens to the LAN's multicast service-discovery traffic — mDNS (UDP 5353)
 * and SSDP (UDP 1900).
 *
 * These datagrams are addressed to *every* host on the segment by design, so
 * receiving them is ordinary participation in the network, not interception:
 * no unicast traffic belonging to other devices is ever read, and no root or
 * monitor mode is involved. What they give us is a live view of which devices
 * are announcing themselves and how loudly — enough to spot a new device
 * appearing or a broadcast storm.
 */
class MulticastSniffer(context: Context) {

    private val appContext = context.applicationContext
    private val infoProvider = NetworkInfoProvider(appContext)

    fun observe(): Flow<ObservedPacket> = callbackFlow {
        val wifi = runCatching {
            appContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
        }.getOrNull()
        // Without a multicast lock Android filters multicast frames in hardware
        // to save power, and we would simply never see a packet.
        val lock = runCatching {
            wifi?.createMulticastLock("netscope-monitor")?.apply {
                setReferenceCounted(false)
                acquire()
            }
        }.getOrNull()

        val iface = runCatching {
            infoProvider.current().interfaceName?.let { NetworkInterface.getByName(it) }
        }.getOrNull()

        val running = AtomicBoolean(true)
        val threads = GROUPS.map { group ->
            thread(name = "netscope-${group.protocol}", isDaemon = true) {
                var socket: MulticastSocket? = null
                try {
                    socket = MulticastSocket(group.port).apply {
                        reuseAddress = true
                        soTimeout = SOCKET_TIMEOUT_MS
                    }
                    joinGroup(socket, group, iface)
                    val buffer = ByteArray(4096)
                    while (running.get()) {
                        val packet = DatagramPacket(buffer, buffer.size)
                        try {
                            socket.receive(packet)
                        } catch (_: java.net.SocketTimeoutException) {
                            continue          // just a tick; re-check `running`
                        } catch (_: Exception) {
                            break             // socket closed or interface gone
                        }
                        val observed = parse(group, packet)
                        trySend(observed)
                    }
                } catch (_: Exception) {
                    // Port already held exclusively, or no multicast support —
                    // the other group may still work, so fail quietly.
                } finally {
                    runCatching { socket?.close() }
                }
            }
        }

        awaitClose {
            running.set(false)
            threads.forEach { runCatching { it.interrupt() } }
            runCatching { lock?.release() }
        }
    }.flowOn(Dispatchers.IO)

    private fun joinGroup(socket: MulticastSocket, group: Group, iface: NetworkInterface?) {
        val address = InetAddress.getByName(group.address)
        if (iface != null) {
            runCatching {
                socket.joinGroup(InetSocketAddress(address, group.port), iface)
            }.onSuccess { return }
        }
        @Suppress("DEPRECATION")
        socket.joinGroup(address)
    }

    private fun parse(group: Group, packet: DatagramPacket): ObservedPacket {
        val data = packet.data
        val length = packet.length
        val source = packet.address?.hostAddress ?: "?"
        val (summary, malformed) = when (group.protocol) {
            "mDNS" -> parseMdns(data, length)
            else -> parseSsdp(data, length)
        }
        return ObservedPacket(
            timeMillis = System.currentTimeMillis(),
            sourceIp = source,
            port = group.port,
            sizeBytes = length,
            protocol = group.protocol,
            summary = summary,
            malformed = malformed,
        )
    }

    /** Light DNS-wire parse: query vs response plus the first question name. */
    private fun parseMdns(data: ByteArray, length: Int): Pair<String, Boolean> {
        if (length < 12) return "短すぎるmDNSパケット ($length B)" to true
        val flags = ((data[2].toInt() and 0xFF) shl 8) or (data[3].toInt() and 0xFF)
        val isResponse = (flags and 0x8000) != 0
        val qdCount = ((data[4].toInt() and 0xFF) shl 8) or (data[5].toInt() and 0xFF)
        val anCount = ((data[6].toInt() and 0xFF) shl 8) or (data[7].toInt() and 0xFF)
        if (qdCount > 64 || anCount > 64) {
            return "異常なレコード数 (Q=$qdCount A=$anCount)" to true
        }
        val name = runCatching { readName(data, 12, length) }.getOrNull()
        val kind = if (isResponse) "応答" else "問い合わせ"
        return if (name.isNullOrBlank()) "mDNS $kind" to false
        else "mDNS $kind: $name" to false
    }

    /** SSDP is HTTP-ish text; pull the request line and the most useful header. */
    private fun parseSsdp(data: ByteArray, length: Int): Pair<String, Boolean> {
        val text = runCatching { String(data, 0, length, Charsets.UTF_8) }.getOrNull()
            ?: return "解読できないSSDPパケット" to true
        val lines = text.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.toList()
        val request = lines.firstOrNull() ?: return "空のSSDPパケット" to true
        val known = request.startsWith("NOTIFY") || request.startsWith("M-SEARCH") ||
            request.startsWith("HTTP/")
        val descriptor = lines.firstOrNull {
            it.startsWith("NT:", true) || it.startsWith("ST:", true) ||
                it.startsWith("SERVER:", true)
        }?.take(80)
        val summary = listOfNotNull(request.take(40), descriptor).joinToString("  ")
        return summary to !known
    }

    /** Reads a DNS name, following 0xC0 compression pointers. */
    private fun readName(data: ByteArray, start: Int, length: Int): String {
        val labels = ArrayList<String>()
        var pos = start
        var guard = 0
        while (pos < length && guard++ < 64) {
            val len = data[pos].toInt() and 0xFF
            when {
                len == 0 -> break
                len and 0xC0 == 0xC0 -> {
                    if (pos + 1 >= length) break
                    pos = ((len and 0x3F) shl 8) or (data[pos + 1].toInt() and 0xFF)
                }
                else -> {
                    if (pos + 1 + len > length) break
                    labels.add(String(data, pos + 1, len, Charsets.UTF_8))
                    pos += len + 1
                }
            }
        }
        return labels.joinToString(".")
    }

    private data class Group(val protocol: String, val address: String, val port: Int)

    private companion object {
        const val SOCKET_TIMEOUT_MS = 1000

        val GROUPS = listOf(
            Group("mDNS", "224.0.0.251", 5353),
            Group("SSDP", "239.255.255.250", 1900),
        )
    }
}
