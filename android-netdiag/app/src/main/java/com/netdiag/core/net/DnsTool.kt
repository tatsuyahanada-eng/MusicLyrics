package com.netdiag.core.net

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import kotlin.random.Random

/** DNS record types the UI lets the user query for. */
enum class DnsRecordType(val label: String, val qtype: Int) {
    A("A", 1),
    AAAA("AAAA", 28),
    CNAME("CNAME", 5),
    MX("MX", 15),
    TXT("TXT", 16),
    NS("NS", 2),
}

/** One decoded resource record (its value already formatted for display). */
data class DnsRecord(val type: String, val value: String)

/** Result of resolving a name through the system resolver. */
data class SystemDnsResult(
    val host: String,
    val success: Boolean,
    val addresses: List<String>,
    val elapsedMs: Long,
    val error: String? = null,
)

/** Result of querying a specific DNS server directly over UDP/53 for a given type. */
data class DnsServerResult(
    val server: String,
    val host: String,
    val recordType: DnsRecordType,
    val success: Boolean,
    val records: List<DnsRecord>,
    val elapsedMs: Long,
    val error: String? = null,
) {
    /** Backwards-friendly view: the record values as a plain list of strings. */
    val addresses: List<String> get() = records.map { it.value }
}

/**
 * DNS reachability / resolution checks.
 *
 * - [resolveSystem] uses the configured resolver (answers "is DNS working at
 *   all" — connectivity is fine but websites won't open == DNS problem).
 * - [queryServer] sends a raw query of the requested [DnsRecordType] to a
 *   *specific* server, so you can compare, e.g., the router's DNS against
 *   8.8.8.8, or inspect CNAME / MX / TXT / NS records directly.
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
        type: DnsRecordType = DnsRecordType.A,
        timeoutMs: Int = 3000,
    ): DnsServerResult = withContext(Dispatchers.IO) {
        val start = System.nanoTime()
        try {
            val query = buildQuery(host, type.qtype)
            DatagramSocket().use { socket ->
                socket.soTimeout = timeoutMs
                socket.connect(InetSocketAddress(InetAddress.getByName(server), 53))
                socket.send(DatagramPacket(query, query.size))
                val buf = ByteArray(2048)
                val response = DatagramPacket(buf, buf.size)
                socket.receive(response)
                val records = parseAnswers(buf, response.length)
                DnsServerResult(server, host, type, records.isNotEmpty(), records, elapsed(start))
            }
        } catch (e: Exception) {
            DnsServerResult(server, host, type, false, emptyList(), elapsed(start), e.message)
        }
    }

    private fun elapsed(startNanos: Long) = (System.nanoTime() - startNanos) / 1_000_000

    /** Builds a minimal DNS query for the given QTYPE. */
    private fun buildQuery(host: String, qtype: Int): ByteArray {
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
        out.write(qtype shr 8); out.write(qtype and 0xFF) // QTYPE
        out.write(0x00); out.write(0x01)   // QCLASS = IN
        return out.toByteArray()
    }

    /** Decodes the answer section into typed, display-ready records. */
    private fun parseAnswers(data: ByteArray, length: Int): List<DnsRecord> {
        if (length < 12) return emptyList()
        val ancount = ((data[6].toInt() and 0xFF) shl 8) or (data[7].toInt() and 0xFF)
        if (ancount == 0) return emptyList()

        var pos = 12
        // Skip the question section: QNAME + QTYPE(2) + QCLASS(2)
        pos = skipName(data, pos)
        pos += 4

        val result = ArrayList<DnsRecord>()
        var answers = 0
        while (answers < ancount && pos + 10 <= length) {
            pos = skipName(data, pos)                       // NAME (may be compressed)
            if (pos + 10 > length) break
            val type = ((data[pos].toInt() and 0xFF) shl 8) or (data[pos + 1].toInt() and 0xFF)
            val rdLength = ((data[pos + 8].toInt() and 0xFF) shl 8) or (data[pos + 9].toInt() and 0xFF)
            val rdStart = pos + 10
            if (rdStart + rdLength > length) break
            decodeRecord(data, type, rdStart, rdLength, length)?.let(result::add)
            pos = rdStart + rdLength
            answers++
        }
        return result
    }

    private fun decodeRecord(
        data: ByteArray,
        type: Int,
        rdStart: Int,
        rdLength: Int,
        length: Int,
    ): DnsRecord? = when (type) {
        1 -> if (rdLength == 4) DnsRecord("A", ipv4(data, rdStart)) else null
        28 -> if (rdLength == 16) DnsRecord("AAAA", ipv6(data, rdStart)) else null
        5 -> DnsRecord("CNAME", readName(data, rdStart, length).first)
        2 -> DnsRecord("NS", readName(data, rdStart, length).first)
        15 -> {
            val pref = ((data[rdStart].toInt() and 0xFF) shl 8) or (data[rdStart + 1].toInt() and 0xFF)
            val exchange = readName(data, rdStart + 2, length).first
            DnsRecord("MX", "$pref $exchange")
        }
        16 -> DnsRecord("TXT", readTxt(data, rdStart, rdLength))
        else -> null
    }

    private fun ipv4(data: ByteArray, p: Int): String =
        "${data[p].toInt() and 0xFF}.${data[p + 1].toInt() and 0xFF}." +
            "${data[p + 2].toInt() and 0xFF}.${data[p + 3].toInt() and 0xFF}"

    private fun ipv6(data: ByteArray, p: Int): String {
        val groups = (0 until 8).map {
            ((data[p + it * 2].toInt() and 0xFF) shl 8) or (data[p + it * 2 + 1].toInt() and 0xFF)
        }
        // Compress the single longest run of zero groups into "::".
        var bestStart = -1; var bestLen = 0; var curStart = -1; var curLen = 0
        groups.forEachIndexed { i, g ->
            if (g == 0) {
                if (curStart < 0) { curStart = i; curLen = 1 } else curLen++
                if (curLen > bestLen) { bestLen = curLen; bestStart = curStart }
            } else { curStart = -1; curLen = 0 }
        }
        if (bestLen < 2) return groups.joinToString(":") { it.toString(16) }
        val head = groups.take(bestStart).joinToString(":") { it.toString(16) }
        val tail = groups.drop(bestStart + bestLen).joinToString(":") { it.toString(16) }
        return "$head::$tail"
    }

    private fun readTxt(data: ByteArray, rdStart: Int, rdLength: Int): String {
        val sb = StringBuilder()
        var p = rdStart
        val end = rdStart + rdLength
        while (p < end) {
            val len = data[p].toInt() and 0xFF
            p++
            if (p + len > end) break
            sb.append(String(data, p, len, Charsets.UTF_8))
            p += len
        }
        return sb.toString()
    }

    /**
     * Reads a DNS name starting at [start], following 0xC0 compression pointers,
     * and returns the decoded name plus the position just after the name in the
     * record stream (pointers do not advance the outer cursor).
     */
    private fun readName(data: ByteArray, start: Int, length: Int): Pair<String, Int> {
        val labels = ArrayList<String>()
        var pos = start
        var afterPointer = -1
        var guard = 0
        while (pos < length && guard++ < 128) {
            val len = data[pos].toInt() and 0xFF
            when {
                len == 0 -> { pos++; break }
                len and 0xC0 == 0xC0 -> {
                    val ptr = ((len and 0x3F) shl 8) or (data[pos + 1].toInt() and 0xFF)
                    if (afterPointer < 0) afterPointer = pos + 2
                    pos = ptr
                }
                else -> {
                    if (pos + 1 + len > length) break
                    labels.add(String(data, pos + 1, len, Charsets.US_ASCII))
                    pos += len + 1
                }
            }
        }
        val end = if (afterPointer >= 0) afterPointer else pos
        return labels.joinToString(".").ifEmpty { "." } to end
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
