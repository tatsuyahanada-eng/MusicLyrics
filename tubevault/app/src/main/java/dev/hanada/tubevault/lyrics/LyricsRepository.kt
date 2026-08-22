package dev.hanada.tubevault.lyrics

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

data class LyricLine(val timeMs: Long, val text: String)

sealed interface LyricsResult {
    data class Synced(val lines: List<LyricLine>) : LyricsResult
    data class PlainOnly(val text: String) : LyricsResult
    data object NotFound : LyricsResult
}

/**
 * Looks up lyrics from LRCLIB — free, keyless, and it hands back both a
 * time-synced LRC track and plain lyrics in the same response, so one call
 * covers the "can overlay on the video" case and the "found but can't sync"
 * fallback.
 */
class LyricsRepository {

    suspend fun fetch(artist: String, title: String): LyricsResult = withContext(Dispatchers.IO) {
        val url = "$SEARCH_URL?artist_name=${encode(artist)}&track_name=${encode(title)}"
        val body = get(url) ?: return@withContext LyricsResult.NotFound
        val results = runCatching { JSONArray(body) }.getOrNull() ?: return@withContext LyricsResult.NotFound

        for (i in 0 until results.length()) {
            val synced = results.getJSONObject(i).optString("syncedLyrics").takeIf { it.isNotBlank() } ?: continue
            val lines = parseLrc(synced)
            if (lines.isNotEmpty()) return@withContext LyricsResult.Synced(lines)
        }
        for (i in 0 until results.length()) {
            val plain = results.getJSONObject(i).optString("plainLyrics").takeIf { it.isNotBlank() } ?: continue
            return@withContext LyricsResult.PlainOnly(plain)
        }
        LyricsResult.NotFound
    }

    private fun get(urlString: String): String? {
        val connection = URL(urlString).openConnection() as HttpURLConnection
        connection.connectTimeout = TIMEOUT_MS
        connection.readTimeout = TIMEOUT_MS
        // LRCLIB asks integrators to identify themselves with a descriptive
        // User-Agent rather than requiring an API key.
        connection.setRequestProperty("User-Agent", "YDPlayer (personal use, non-commercial)")
        return try {
            if (connection.responseCode !in 200..299) return null
            connection.inputStream.bufferedReader().use { it.readText() }
        } catch (_: Exception) {
            null
        } finally {
            connection.disconnect()
        }
    }

    private fun encode(value: String) = URLEncoder.encode(value, "UTF-8")

    private fun parseLrc(text: String): List<LyricLine> {
        val lines = mutableListOf<LyricLine>()
        text.lineSequence().forEach { line ->
            val m = LRC_LINE.find(line) ?: return@forEach
            val minutes = m.groupValues[1].toLongOrNull() ?: return@forEach
            val seconds = m.groupValues[2].toDoubleOrNull() ?: return@forEach
            val body = m.groupValues[3].trim()
            if (body.isNotEmpty()) lines += LyricLine(minutes * 60_000L + (seconds * 1000).toLong(), body)
        }
        return lines.sortedBy { it.timeMs }
    }

    private companion object {
        const val SEARCH_URL = "https://lrclib.net/api/search"
        const val TIMEOUT_MS = 10_000
        val LRC_LINE = Regex("""^\[(\d+):(\d+(?:\.\d+)?)]\s*(.*)$""")
    }
}
