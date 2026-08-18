package dev.hanada.tubevault.browser

import android.content.Context
import android.util.Log
import android.webkit.CookieManager
import java.io.File

/**
 * YouTube now answers most anonymous extraction attempts with
 * "Please sign in", and yt-dlp's documented way around that is a real browser
 * session. The in-app browser is exactly that, so its cookie jar gets written
 * out in the Netscape format yt-dlp reads.
 *
 * Always call [export] from the main thread — [CookieManager] belongs to the
 * WebView, so the browser refreshes this file as pages finish loading and the
 * download engine only ever reads what is already on disk.
 */
object CookieExporter {

    private const val TAG = "CookieExporter"
    private const val FILE_NAME = "youtube-cookies.txt"
    private const val ONE_YEAR_SECONDS = 365L * 24 * 60 * 60

    /** Auth cookies live on google.com; playback/consent ones on youtube.com. */
    private val SOURCES = listOf(
        "https://www.youtube.com" to ".youtube.com",
        "https://m.youtube.com" to ".youtube.com",
        "https://www.google.com" to ".google.com",
        "https://accounts.google.com" to ".google.com",
    )

    fun cookieFile(context: Context): File = File(context.filesDir, FILE_NAME)

    /** The exported file, or null when the browser has no cookies worth sending. */
    fun current(context: Context): File? =
        cookieFile(context).takeIf { it.isFile && it.length() > 0 }

    fun export(context: Context): File? {
        val manager = runCatching { CookieManager.getInstance() }.getOrNull() ?: return null
        val expiry = System.currentTimeMillis() / 1000 + ONE_YEAR_SECONDS

        // Keyed by domain+name so the same cookie seen on two hosts is written once.
        val rows = LinkedHashMap<String, String>()
        for ((url, domain) in SOURCES) {
            val raw = runCatching { manager.getCookie(url) }.getOrNull() ?: continue
            for (pair in raw.split(';')) {
                val separator = pair.indexOf('=')
                if (separator <= 0) continue
                val name = pair.substring(0, separator).trim()
                val value = pair.substring(separator + 1).trim()
                if (name.isEmpty()) continue
                rows["$domain\t$name"] = listOf(
                    domain, "TRUE", "/", "TRUE", expiry.toString(), name, value,
                ).joinToString("\t")
            }
        }

        val file = cookieFile(context)
        if (rows.isEmpty()) {
            file.delete()
            return null
        }

        return try {
            file.writeText(
                buildString {
                    appendLine("# Netscape HTTP Cookie File")
                    appendLine("# Written by TubeVault from the in-app browser.")
                    rows.values.forEach { appendLine(it) }
                },
            )
            file
        } catch (e: Exception) {
            Log.w(TAG, "could not write the cookie file", e)
            null
        }
    }

    fun clear(context: Context) {
        runCatching { CookieManager.getInstance().removeAllCookies(null) }
        cookieFile(context).delete()
    }

    /** Rough "are we signed in" check, for showing status in settings. */
    fun cookieCount(context: Context): Int =
        current(context)
            ?.readLines()
            ?.count { it.isNotBlank() && !it.startsWith("#") }
            ?: 0
}
