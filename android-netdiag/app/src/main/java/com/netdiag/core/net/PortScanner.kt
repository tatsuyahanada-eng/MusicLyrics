package com.netdiag.core.net

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.net.InetSocketAddress
import java.net.Socket

/** TCP connect port scanner (no raw sockets, works on non-rooted Android). */
object PortScanner {

    /** Commonly interesting ports and their service labels. */
    val COMMON_PORTS: Map<Int, String> = linkedMapOf(
        20 to "FTP-data", 21 to "FTP", 22 to "SSH", 23 to "Telnet",
        25 to "SMTP", 53 to "DNS", 80 to "HTTP", 110 to "POP3",
        111 to "RPC", 135 to "MS-RPC", 139 to "NetBIOS", 143 to "IMAP",
        443 to "HTTPS", 445 to "SMB", 465 to "SMTPS", 587 to "SMTP",
        631 to "IPP/印刷", 993 to "IMAPS", 995 to "POP3S",
        1883 to "MQTT", 1900 to "SSDP/UPnP", 3306 to "MySQL",
        3389 to "RDP", 5000 to "UPnP/HTTP", 5060 to "SIP",
        5353 to "mDNS", 5900 to "VNC", 8000 to "HTTP-alt",
        8080 to "HTTP-proxy", 8443 to "HTTPS-alt", 9100 to "プリンタ(RAW)",
        62078 to "iPhone-sync",
    )

    fun serviceName(port: Int): String = COMMON_PORTS[port] ?: "unknown"

    /**
     * Scans [ports] on [host] and returns the sorted list of open ports.
     * A "connection refused" is treated as closed; only a completed TCP
     * handshake counts as open.
     */
    suspend fun scan(
        host: String,
        ports: Collection<Int> = COMMON_PORTS.keys,
        timeoutMs: Int = 500,
        parallelism: Int = 64,
    ): List<Int> = withContext(Dispatchers.IO) {
        val gate = Semaphore(parallelism)
        coroutineScope {
            ports.map { port ->
                async {
                    gate.withPermit { if (isOpen(host, port, timeoutMs)) port else null }
                }
            }.awaitAll().filterNotNull().sorted()
        }
    }

    fun isOpen(host: String, port: Int, timeoutMs: Int): Boolean {
        return try {
            Socket().use { s ->
                s.connect(InetSocketAddress(host, port), timeoutMs)
                true
            }
        } catch (_: Exception) {
            false
        }
    }
}
