package dev.hanada.tubevault.lyrics

import dev.hanada.tubevault.data.LyricsCacheDao
import dev.hanada.tubevault.data.LyricsCacheEntity
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
 *
 * A result that was ever found is kept in [cacheDao] and checked first on
 * every later lookup for the same artist/title, so a site outage or a flaky
 * connection can never take away lyrics that already worked once. Only a
 * successful result is worth remembering — [LyricsResult.NotFound] is left
 * uncached, since caching it would turn one bad network call into a
 * permanent "not found" even after a correction or a fixed connection.
 */
class LyricsRepository(private val cacheDao: LyricsCacheDao) {

    suspend fun fetch(artist: String, title: String): LyricsResult = withContext(Dispatchers.IO) {
        val artistKey = cacheKey(artist)
        val titleKey = cacheKey(title)

        runCatching { cacheDao.find(artistKey, titleKey) }.getOrNull()?.let { cached ->
            return@withContext if (cached.synced) {
                LyricsResult.Synced(parseLrc(cached.text))
            } else {
                LyricsResult.PlainOnly(cached.text)
            }
        }

        fetchFromNetwork(artist, title, artistKey, titleKey)
    }

    private suspend fun fetchFromNetwork(
        artist: String,
        title: String,
        artistKey: String,
        titleKey: String,
    ): LyricsResult {
        val url = "$SEARCH_URL?artist_name=${encode(artist)}&track_name=${encode(title)}"
        val body = get(url) ?: return LyricsResult.NotFound
        val results = runCatching { JSONArray(body) }.getOrNull() ?: return LyricsResult.NotFound

        for (i in 0 until results.length()) {
            val synced = results.getJSONObject(i).optString("syncedLyrics").takeIf { it.isNotBlank() } ?: continue
            val lines = parseLrc(synced)
            if (lines.isEmpty()) continue
            // Cached verbatim rather than reassembled from the parsed lines,
            // so a later read is byte-for-byte what LRCLIB actually returned.
            cache(artistKey, titleKey, synced = true, text = synced)
            return LyricsResult.Synced(lines)
        }
        for (i in 0 until results.length()) {
            val plain = results.getJSONObject(i).optString("plainLyrics").takeIf { it.isNotBlank() } ?: continue
            cache(artistKey, titleKey, synced = false, text = plain)
            return LyricsResult.PlainOnly(plain)
        }
        return LyricsResult.NotFound
    }

    private suspend fun cache(artistKey: String, titleKey: String, synced: Boolean, text: String) {
        runCatching {
            cacheDao.upsert(LyricsCacheEntity(artistKey = artistKey, titleKey = titleKey, synced = synced, text = text))
        }
    }

    private fun cacheKey(value: String) = value.trim().lowercase()

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

