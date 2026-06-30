package com.netdiag.core.net

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import kotlin.random.Random

/** Result of resolving a name through the system resolver. */
data class SystemDnsResult(
    val host: String,
    val success: Boolean,
    val addresses: List<String>,
    val elapsedMs: Long,
    val error: String? = null,
)

/** Result of querying a specific DNS server directly over UDP/53. */
data class DnsServerResult(
    val server: String,
    val host: String,
    val success: Boolean,
    val addresses: List<String>,
    val elapsedMs: Long,
    val error: String? = null,
)

/**
 * DNS reachability / resolution checks.
 *
 * - [resolveSystem] uses the configured resolver (answers "is DNS working at
 *   all" — connectivity is fine but websites won't open == DNS problem).
 * - [queryServer] sends a raw A-record query to a *specific* server so you can
 *   compare, e.g., the router's DNS against 8.8.8.8.
 */
object DnsTool {

    suspend fun resolveSystem(host: String): SystemDnsResult = withContext(Dispatchers.IO) {
        val start = System.nanoTime()
        try {
            val addrs = InetAddress.getAllByName(host).mapNotNull { it.hostAddress }
            SystemDnsResult(host, true, addrs, elapsed(start))
        } catch (e: Exception) {
            SystemDnsResult(host, false, emptyList(), elapsed(start), e.message)
        }
    }

    suspend fun queryServer(
        server: String,
        host: String,
        timeoutMs: Int = 3000,
    ): DnsServerResult = withContext(Dispatchers.IO) {
        val start = System.nanoTime()
        try {
            val query = buildQuery(host)
            DatagramSocket().use { socket ->
                socket.soTimeout = timeoutMs
                socket.connect(InetSocketAddress(InetAddress.getByName(server), 53))
                socket.send(DatagramPacket(query, query.size))
                val buf = ByteArray(1500)
                val response = DatagramPacket(buf, buf.size)
                socket.receive(response)
                val addrs = parseAnswers(buf, response.length)
                DnsServerResult(server, host, addrs.isNotEmpty(), addrs, elapsed(start))
            }
        } catch (e: Exception) {
            DnsServerResult(server, host, false, emptyList(), elapsed(start), e.message)
        }
    }

    private fun elapsed(startNanos: Long) = (System.nanoTime() - startNanos) / 1_000_000

    /** Builds a minimal DNS query for an A record. */
    private fun buildQuery(host: String): ByteArray {
        val out = ByteArrayOutputStream()
        val id = Random.nextInt(0, 0xFFFF)
        out.write(id shr 8); out.write(id and 0xFF)
        out.write(0x01); out.write(0x00)   // flags: standard query, recursion desired
        out.write(0x00); out.write(0x01)   // QDCOUNT = 1
        out.write(0x00); out.write(0x00)   // ANCOUNT
        out.write(0x00); out.write(0x00)   // NSCOUNT
        out.write(0x00); out.write(0x00)   // ARCOUNT
        for (label in host.split(".")) {
            val bytes = label.toByteArray(Charsets.US_ASCII)
            out.write(bytes.size)
            out.write(bytes)
        }
        out.write(0x00)                    // end of QNAME
        out.write(0x00); out.write(0x01)   // QTYPE = A
        out.write(0x00); out.write(0x01)   // QCLASS = IN
        return out.toByteArray()
    }

    /** Extracts the A-record addresses from a DNS response. */
    private fun parseAnswers(data: ByteArray, length: Int): List<String> {
        if (length < 12) return emptyList()
        val ancount = ((data[6].toInt() and 0xFF) shl 8) or (data[7].toInt() and 0xFF)
        if (ancount == 0) return emptyList()

        var pos = 12
        // Skip the question section: QNAME + QTYPE(2) + QCLASS(2)
        pos = skipName(data, pos)
        pos += 4

        val result = ArrayList<String>()
        var answers = 0
        while (answers < ancount && pos + 12 <= length) {
            pos = skipName(data, pos)                       // NAME (may be compressed)
            if (pos + 10 > length) break
            val type = ((data[pos].toInt() and 0xFF) shl 8) or (data[pos + 1].toInt() and 0xFF)
            val rdLength = ((data[pos + 8].toInt() and 0xFF) shl 8) or (data[pos + 9].toInt() and 0xFF)
            pos += 10
            if (type == 1 && rdLength == 4 && pos + 4 <= length) {
                result.add(
                    "${data[pos].toInt() and 0xFF}.${data[pos + 1].toInt() and 0xFF}." +
                        "${data[pos + 2].toInt() and 0xFF}.${data[pos + 3].toInt() and 0xFF}"
                )
            }
            pos += rdLength
            answers++
        }
        return result
    }

    /** Advances past a DNS name, honouring 0xC0 compression pointers. */
    private fun skipName(data: ByteArray, start: Int): Int {
        var pos = start
        while (pos < data.size) {
            val len = data[pos].toInt() and 0xFF
            if (len == 0) return pos + 1
            if (len and 0xC0 == 0xC0) return pos + 2   // compression pointer ends the name
            pos += len + 1
        }
        return pos
    }
}
