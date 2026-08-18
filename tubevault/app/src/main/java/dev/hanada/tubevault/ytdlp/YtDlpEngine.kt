package dev.hanada.tubevault.ytdlp

import android.content.Context
import android.util.Log
import com.yausername.ffmpeg.FFmpeg
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import dev.hanada.tubevault.core.MediaKind
import dev.hanada.tubevault.core.SearchResult
import dev.hanada.tubevault.core.Storage
import dev.hanada.tubevault.core.VideoQuality
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File

class YtDlpInitException(message: String, cause: Throwable?) : Exception(message, cause)

class YtDlpDownloadException(message: String) : Exception(message)

data class DownloadOutcome(
    val mediaFile: File,
    val thumbFile: File?,
    val sizeBytes: Long,
)

/**
 * Thin coroutine wrapper over the bundled yt-dlp + ffmpeg binaries.
 *
 * Every entry point calls [ensureInit] first: unpacking the native payload is
 * lazy so a cold start does not pay for it, and it must happen exactly once.
 */
class YtDlpEngine(private val context: Context) {

    private val initMutex = Mutex()

    @Volatile
    private var initialized = false

    suspend fun ensureInit() {
        if (initialized) return
        initMutex.withLock {
            if (initialized) return
            withContext(Dispatchers.IO) {
                try {
                    YoutubeDL.getInstance().init(context)
                    FFmpeg.getInstance().init(context)
                } catch (e: Exception) {
                    throw YtDlpInitException("yt-dlp の初期化に失敗しました: ${e.message}", e)
                }
            }
            initialized = true
        }
    }

    suspend fun version(): String = withContext(Dispatchers.IO) {
        runCatching {
            ensureInit()
            YoutubeDL.getInstance().version(context) ?: "unknown"
        }.getOrElse { "unavailable" }
    }

    /** Pulls the newest yt-dlp release — YouTube changes often enough to need this. */
    suspend fun updateYtDlp(): String = withContext(Dispatchers.IO) {
        ensureInit()
        val status = YoutubeDL.getInstance().updateYoutubeDL(context)
        status?.toString() ?: "UP_TO_DATE"
    }

    /**
     * Runs `ytsearch<N>:<query>`, or treats [query] as a URL when it looks like
     * one. `--flat-playlist` keeps this to a single network round trip instead
     * of resolving every result's formats up front.
     */
    suspend fun search(query: String, limit: Int): List<SearchResult> = withContext(Dispatchers.IO) {
        ensureInit()
        val trimmed = query.trim()
        if (trimmed.isEmpty()) return@withContext emptyList()

        val target = if (isUrl(trimmed)) trimmed else "ytsearch$limit:$trimmed"
        val request = YoutubeDLRequest(target)
        request.addOption("--flat-playlist")
        request.addOption("--dump-json")
        request.addOption("--no-warnings")
        request.addOption("--ignore-errors")

        val response = YoutubeDL.getInstance().execute(request)
        response.out.lineSequence().mapNotNull(::parseEntry).toList()
    }

    /**
     * Downloads into [targetDir] as `<videoId>.<ext>` plus a `<videoId>.jpg`
     * thumbnail. Naming by id sidesteps every filename-escaping problem that
     * arbitrary video titles bring with them.
     */
    suspend fun download(
        sourceUrl: String,
        videoId: String,
        targetDir: File,
        kind: MediaKind,
        quality: VideoQuality,
        processId: String,
        onProgress: (Float, Long, String) -> Unit,
    ): DownloadOutcome = withContext(Dispatchers.IO) {
        ensureInit()
        targetDir.mkdirs()

        val request = YoutubeDLRequest(sourceUrl)
        request.addOption("--no-playlist")
        request.addOption("--no-mtime")
        request.addOption("--no-warnings")
        request.addOption("-o", File(targetDir, "%(id)s.%(ext)s").absolutePath)
        request.addOption("--write-thumbnail")
        request.addOption("--convert-thumbnails", "jpg")

        when (kind) {
            MediaKind.AUDIO -> {
                request.addOption("-f", "bestaudio/best")
                request.addOption("-x")
                request.addOption("--audio-format", "m4a")
            }

            MediaKind.VIDEO -> {
                val h = quality.maxHeight
                request.addOption(
                    "-f",
                    "bv*[height<=?$h][ext=mp4]+ba[ext=m4a]/bv*[height<=?$h]+ba/b[height<=?$h]/b",
                )
                request.addOption("--merge-output-format", "mp4")
            }
        }

        val response = YoutubeDL.getInstance().execute(
            request = request,
            processId = processId,
            callback = onProgress,
        )

        if (response.exitCode != 0) {
            throw YtDlpDownloadException(lastMeaningfulLine(response.err))
        }

        val media = Storage.findMediaFile(targetDir, videoId)
            ?: throw YtDlpDownloadException("ダウンロードは完了しましたが、ファイルが見つかりませんでした")

        DownloadOutcome(
            mediaFile = media,
            thumbFile = Storage.findThumbnailFile(targetDir, videoId),
            sizeBytes = media.length(),
        )
    }

    fun cancel(processId: String): Boolean = runCatching {
        YoutubeDL.getInstance().destroyProcessById(processId)
    }.getOrElse {
        Log.w(TAG, "failed to cancel process $processId", it)
        false
    }

    private fun isUrl(query: String): Boolean =
        query.startsWith("http://") || query.startsWith("https://")

    private fun parseEntry(rawLine: String): SearchResult? {
        val line = rawLine.trim()
        if (!line.startsWith("{")) return null
        return try {
            val json = JSONObject(line)
            val id = json.optString("id")
            if (id.isBlank() || id == "null") return null
            SearchResult(
                videoId = id,
                title = json.optString("title").ifBlank { "(タイトル不明)" },
                uploader = firstNonBlank(json, "channel", "uploader", "playlist_uploader"),
                durationSec = json.optDouble("duration", 0.0).let { d ->
                    if (d.isNaN() || d < 0) 0L else d.toLong()
                },
                viewCount = json.optLong("view_count", 0L),
            )
        } catch (e: Exception) {
            Log.w(TAG, "unparsable search entry", e)
            null
        }
    }

    private fun firstNonBlank(json: JSONObject, vararg keys: String): String? = keys
        .map { json.optString(it) }
        .firstOrNull { it.isNotBlank() && it != "null" }

    /** yt-dlp's stderr ends with the line that actually explains the failure. */
    private fun lastMeaningfulLine(stderr: String): String = stderr
        .lineSequence()
        .map { it.trim() }
        .lastOrNull { it.isNotEmpty() }
        ?.removePrefix("ERROR: ")
        ?: "ダウンロードに失敗しました"

    private companion object {
        const val TAG = "YtDlpEngine"
    }
}
