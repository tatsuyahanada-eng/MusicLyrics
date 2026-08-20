package dev.hanada.tubevault.ytdlp

import android.content.Context
import android.util.Log
import com.yausername.ffmpeg.FFmpeg
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import dev.hanada.tubevault.browser.CookieExporter
import dev.hanada.tubevault.core.MediaKind
import dev.hanada.tubevault.core.SearchResult
import dev.hanada.tubevault.core.Storage
import dev.hanada.tubevault.core.VideoQuality
import dev.hanada.tubevault.core.YouTubeUrls
import dev.hanada.tubevault.data.PlayerClient
import dev.hanada.tubevault.data.SettingsStore
import dev.hanada.tubevault.potoken.PoTokenProvider
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
class YtDlpEngine(
    private val context: Context,
    private val settings: SettingsStore,
    private val poTokens: PoTokenProvider,
) {

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
        request.addOption("--ignore-errors")
        request.applyYouTubeOptions()

        val response = try {
            YoutubeDL.getInstance().execute(request)
        } catch (e: Exception) {
            throw YtDlpDownloadException(explain(e.message.orEmpty()))
        }
        response.out.lineSequence().mapNotNull(::parseEntry).toList()
    }

    /**
     * Full metadata for a single video. Used when a download starts from the
     * browser, where all we have is the URL the page happens to be on.
     */
    suspend fun fetchInfo(url: String): SearchResult = withContext(Dispatchers.IO) {
        ensureInit()
        val request = YoutubeDLRequest(url)
        request.addOption("--dump-single-json")
        request.addOption("--no-playlist")
        request.applyYouTubeOptions()

        val response = try {
            YoutubeDL.getInstance().execute(request)
        } catch (e: Exception) {
            throw YtDlpDownloadException(explain(e.message.orEmpty()))
        }
        val line = response.out.lineSequence().firstOrNull { it.trimStart().startsWith("{") }
            ?: throw YtDlpDownloadException("動画の情報を取得できませんでした")
        parseEntry(line) ?: throw YtDlpDownloadException("動画の情報を解釈できませんでした")
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

        try {
            runDownload(sourceUrl, videoId, targetDir, kind, quality, processId, forceNoToken = false, onProgress)
        } catch (e: YtDlpDownloadException) {
            // A PO Token that mints successfully can still turn out to be
            // rejected for this particular video's formats (yt-dlp then
            // drops them and reports the same "format not available" as an
            // empty format list would) — that failure mode is impossible to
            // tell apart from a real empty format list without already
            // having the fix in hand, so it is cheaper to just retry once
            // without the token than to diagnose which one happened.
            if (settings.current.usePoToken && "format" in e.message.orEmpty().lowercase()) {
                Log.w(TAG, "retrying $videoId without a PO Token after: ${e.message}")
                runDownload(sourceUrl, videoId, targetDir, kind, quality, processId, forceNoToken = true, onProgress)
            } else {
                throw e
            }
        }
    }

    private suspend fun runDownload(
        sourceUrl: String,
        videoId: String,
        targetDir: File,
        kind: MediaKind,
        quality: VideoQuality,
        processId: String,
        forceNoToken: Boolean,
        onProgress: (Float, Long, String) -> Unit,
    ): DownloadOutcome {
        val request = YoutubeDLRequest(sourceUrl)
        request.addOption("--no-playlist")
        request.addOption("--no-mtime")
        request.addOption("-o", File(targetDir, "%(id)s.%(ext)s").absolutePath)
        request.addOption("--write-thumbnail")
        request.addOption("--convert-thumbnails", "jpg")
        request.applyYouTubeOptions(forceNoToken)

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

        val response = try {
            YoutubeDL.getInstance().execute(
                request = request,
                processId = processId,
                callback = onProgress,
            )
        } catch (e: Exception) {
            throw YtDlpDownloadException(explain(e.message.orEmpty()))
        }

        if (response.exitCode != 0) {
            throw YtDlpDownloadException(explain(response.err))
        }

        val media = Storage.findMediaFile(targetDir, videoId)
            ?: throw YtDlpDownloadException("ダウンロードは完了しましたが、ファイルが見つかりませんでした")

        return DownloadOutcome(
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

    /**
     * yt-dlp takes one `youtube:` extractor-arg string, so every knob is
     * collected and joined rather than added separately — a second
     * `--extractor-args youtube:...` would replace the first.
     */
    private suspend fun YoutubeDLRequest.applyYouTubeOptions(forceNoToken: Boolean = false) {
        val current = settings.current

        if (current.useCookies) {
            CookieExporter.current(context)?.let { addOption("--cookies", it.absolutePath) }
        }

        val args = mutableListOf<String>()
        val token = if (current.usePoToken && !forceNoToken) {
            runCatching { poTokens.current() }.getOrNull()
        } else {
            null
        }

        if (token != null) {
            // web alone sometimes has an empty format list for a given video
            // (progressive formats are largely gone from it, and GVS-token
            // binding is picky) — restricting to just it, as this used to do,
            // could take a video from "many formats available" down to
            // "Requested format is not available". Requesting it alongside
            // the clients that already work without a token instead lets
            // yt-dlp merge format lists from all of them, so the po_token can
            // only add options, never remove the ones already working.
            args += "player_client=web,${PlayerClient.NO_POT.argument}"
            // The token is minted for the web client and bound to this visitor
            // id, so both have to travel with it or YouTube rejects it.
            args += "po_token=web.gvs+${token.token}"
            args += "visitor_data=${token.visitorData}"
        } else {
            current.playerClient.argument?.let { args += "player_client=$it" }
        }

        if (args.isNotEmpty()) {
            addOption("--extractor-args", "youtube:${args.joinToString(";")}")
        }
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
            Log.w(TAG, "unparsable entry", e)
            null
        }
    }

    private fun firstNonBlank(json: JSONObject, vararg keys: String): String? = keys
        .map { json.optString(it) }
        .firstOrNull { it.isNotBlank() && it != "null" }

    /**
     * yt-dlp's final stderr line names the failure, but WARNING lines just
     * above it often carry the actual reason — most notably
     * "... formats have been skipped as they are missing a url. Fix it by
     * adding --extractor-args youtube:formats=missing_pot", which fires
     * exactly when a supplied PO Token was rejected or missing for those
     * formats. Keeping only the last line, as this used to, discarded
     * exactly the detail needed to tell that apart from every other reason
     * "Requested format is not available" can happen.
     */
    private fun explain(stderr: String): String {
        val lines = stderr.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.toList()
        val errorLine = lines.lastOrNull()?.removePrefix("ERROR: ") ?: "ダウンロードに失敗しました"
        val warnings = lines
            .filter { it.startsWith("WARNING:") && "format" in it.lowercase() }
            .distinct()

        val message = if (warnings.isEmpty()) {
            errorLine
        } else {
            (warnings + errorLine).joinToString("\n")
        }

        val lowered = message.lowercase()
        return when {
            // Despite the wording, this is almost always a missing PO Token
            // rather than a missing account: YouTube mints those via BotGuard
            // to prove the request came from a real client, and cookies say
            // nothing about that. Signing in does not help here.
            "sign in" in lowered || "not a bot" in lowered ->
                "$message\n\nこれはログインの問題ではなく、YouTube の「PO Token」" +
                    "（本物のクライアントからの通信であることの証明）が無いために起きています。" +
                    "ログインしても直りません。\n\n" +
                    "設定タブの「PO Token の生成をテスト」を実行してください。" +
                    "失敗する場合は端末の WebView が古い可能性があるため、Play ストアで" +
                    "「Android System WebView」を更新してみてください。"

            "missing_pot" in lowered || "missing a url" in lowered ->
                "$message\n\n生成した PO Token が、この形式には無効と判断され、除外されています。" +
                    "PO Token 自体の生成には成功していても、渡し方や有効期限の問題で個別の形式が" +
                    "弾かれることがあります。設定タブで PO Token を一旦オフにして" +
                    "（「トークン不要を優先」のクライアントのみで）試すと切り分けになります。"

            "cookies" in lowered ->
                "$message\n\n「ホーム」タブで YouTube を開くとセッションが使われるようになります。"

            "unable to extract" in lowered || "player response" in lowered ->
                "$message\n\nYouTube 側の仕様変更の可能性があります。設定タブの「yt-dlp を更新」を試してください。"

            else -> message
        }
    }

    private companion object {
        const val TAG = "YtDlpEngine"
    }
}
