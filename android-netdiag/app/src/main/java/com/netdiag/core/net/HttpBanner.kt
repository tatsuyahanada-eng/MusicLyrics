package com.netdiag.core.net

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

/**
 * Grabs a cheap HTTP fingerprint from a host that serves a web page (router,
 * printer, NAS, IP camera, IoT hub…). The `Server` header and the page
 * `<title>` are often enough to identify the device/model on a LAN where the
 * MAC (and thus vendor) is unavailable.
 */
object HttpBanner {

    /** Ports worth trying, in order. Plain HTTP only (keeps it dependency-free). */
    val WEB_PORTS = listOf(80, 8080, 8000, 5000, 631, 9000)

    suspend fun probe(ip: String, port: Int, timeoutMs: Int = 1500): String? =
        withContext(Dispatchers.IO) {
            var conn: HttpURLConnection? = null
            try {
                conn = (URL("http://$ip:$port/").openConnection() as HttpURLConnection).apply {
                    connectTimeout = timeoutMs
                    readTimeout = timeoutMs
                    instanceFollowRedirects = true
                    requestMethod = "GET"
                    setRequestProperty("User-Agent", "NetScope")
                }
                conn.connect()
                val server = conn.getHeaderField("Server")
                val title = readTitle(conn)
                val parts = buildList {
                    if (!server.isNullOrBlank()) add("Server: ${server.take(40)}")
                    if (!title.isNullOrBlank()) add("Title: ${title.take(40)}")
                }
                if (parts.isEmpty()) null else parts.joinToString(" ・ ")
            } catch (_: Exception) {
                null
            } finally {
                conn?.disconnect()
            }
        }

    private fun readTitle(conn: HttpURLConnection): String? {
        return try {
            val stream = if (conn.responseCode in 200..399) conn.inputStream else conn.errorStream
            stream ?: return null
            val text = stream.bufferedReader().use { reader ->
                val buf = CharArray(8192)
                val n = reader.read(buf)
                if (n <= 0) "" else String(buf, 0, n)
            }
            Regex("<title[^>]*>(.*?)</title>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
                .find(text)?.groupValues?.get(1)?.trim()
                ?.replace(Regex("\\s+"), " ")
                ?.takeIf { it.isNotBlank() }
        } catch (_: Exception) {
            null
        }
    }
}
