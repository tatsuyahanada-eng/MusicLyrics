package com.netdiag.core.net

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.InetSocketAddress
import java.net.Socket

/** Result of a WHOIS lookup for a domain or IP address. */
data class WhoisResult(
    val query: String,
    val server: String,      // the WHOIS server that produced the shown text
    val success: Boolean,
    val text: String,
    val elapsedMs: Long,
    val error: String? = null,
)

/**
 * Minimal WHOIS client (TCP/43, RFC 3912). It starts at IANA's WHOIS server,
 * which reports the authoritative server for the domain's TLD (or the IP's RIR),
 * then follows the referral so the final answer comes from the right registry.
 *
 * WHOIS is public registration data only — it never touches credentials or
 * anything private, and is purely read-only.
 */
object WhoisTool {

    private const val IANA = "whois.iana.org"
    private const val PORT = 43
    private const val MAX_REFERRALS = 3

    suspend fun lookup(rawQuery: String): WhoisResult = withContext(Dispatchers.IO) {
        val query = rawQuery.trim()
        val start = System.nanoTime()
        try {
            var server = IANA
            var text = ask(server, query)
            var hops = 0
            // Follow "whois:"/"refer:"/"Registrar WHOIS Server:" referrals to the
            // registry that actually holds the record.
            while (hops < MAX_REFERRALS) {
                val next = referralServer(text)?.takeIf { it != server } ?: break
                val deeper = runCatching { ask(next, query) }.getOrNull()
                if (deeper.isNullOrBlank()) break
                server = next
                text = deeper
                hops++
            }
            WhoisResult(query, server, text.isNotBlank(), text.trim(), elapsed(start))
        } catch (e: Exception) {
            WhoisResult(query, IANA, false, "", elapsed(start), e.message)
        }
    }

    private fun ask(server: String, query: String, timeoutMs: Int = 6000): String {
        Socket().use { socket ->
            socket.connect(InetSocketAddress(server, PORT), timeoutMs)
            socket.soTimeout = timeoutMs
            socket.getOutputStream().apply {
                write((query + "\r\n").toByteArray(Charsets.UTF_8))
                flush()
            }
            return socket.getInputStream().bufferedReader(Charsets.UTF_8).readText()
        }
    }

    private fun referralServer(text: String): String? {
        val keys = listOf("registrar whois server:", "whois:", "refer:")
        for (line in text.lineSequence()) {
            val lower = line.trim().lowercase()
            for (key in keys) {
                if (lower.startsWith(key)) {
                    val value = line.substring(line.indexOf(':') + 1).trim()
                    if (value.isNotBlank()) return value
                }
            }
        }
        return null
    }

    private fun elapsed(startNanos: Long) = (System.nanoTime() - startNanos) / 1_000_000
}
