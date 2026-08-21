package dev.hanada.tubevault.download

import android.content.Context
import android.util.Log
import dev.hanada.tubevault.core.MediaKind
import dev.hanada.tubevault.core.SearchResult
import dev.hanada.tubevault.core.VideoQuality
import dev.hanada.tubevault.data.LibraryRepository
import dev.hanada.tubevault.data.MediaItemEntity
import dev.hanada.tubevault.ytdlp.YtDlpEngine
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.UUID

enum class JobState { QUEUED, RUNNING, COMPLETED, FAILED, CANCELED }

data class DownloadJob(
    val id: String,
    val videoId: String,
    val title: String,
    val uploader: String?,
    val durationSec: Long,
    val sourceUrl: String,
    val kind: MediaKind,
    val quality: VideoQuality,
    val categoryId: Long,
    val state: JobState = JobState.QUEUED,
    val progress: Float = 0f,
    val etaSeconds: Long = -1L,
    val statusLine: String = "",
    val error: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
    /** Queued from the browser, so title/duration still need resolving. */
    val needsMetadata: Boolean = false,
) {
    val isActive: Boolean get() = state == JobState.QUEUED || state == JobState.RUNNING
    val thumbnailUrl: String get() = "https://i.ytimg.com/vi/$videoId/hqdefault.jpg"
}

/**
 * Application-scoped serial download queue.
 *
 * One worker at a time on purpose: yt-dlp spawns ffmpeg for merging, and running
 * several of those in parallel on a phone costs more than it gains. The UI reads
 * [jobs] directly, so nothing has to bind to the foreground service.
 */
class DownloadCenter(
    private val context: Context,
    private val engine: YtDlpEngine,
    private val library: LibraryRepository,
) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val queue = Channel<String>(Channel.UNLIMITED)

    private val _jobs = MutableStateFlow<List<DownloadJob>>(emptyList())
    val jobs: StateFlow<List<DownloadJob>> = _jobs.asStateFlow()

    init {
        scope.launch {
            for (jobId in queue) {
                runJob(jobId)
            }
        }
    }

    fun enqueue(
        result: SearchResult,
        kind: MediaKind,
        quality: VideoQuality,
        categoryId: Long,
    ): String = enqueue(
        DownloadJob(
            id = UUID.randomUUID().toString(),
            videoId = result.videoId,
            title = result.title,
            uploader = result.uploader,
            durationSec = result.durationSec,
            sourceUrl = result.watchUrl,
            kind = kind,
            quality = quality,
            categoryId = categoryId,
        ),
    )

    /**
     * Queues a video the browser is sitting on. All the page gives us is a URL
     * and a tab title; the real metadata is resolved from yt-dlp when the job
     * starts, so tapping download stays instant.
     */
    fun enqueueUrl(
        videoId: String,
        sourceUrl: String,
        provisionalTitle: String,
        kind: MediaKind,
        quality: VideoQuality,
        categoryId: Long,
    ): String = enqueue(
        DownloadJob(
            id = UUID.randomUUID().toString(),
            videoId = videoId,
            title = provisionalTitle,
            uploader = null,
            durationSec = 0L,
            sourceUrl = sourceUrl,
            kind = kind,
            quality = quality,
            categoryId = categoryId,
            needsMetadata = true,
        ),
    )

    private fun enqueue(job: DownloadJob): String {
        _jobs.update { it + job }
        queue.trySend(job.id)
        DownloadService.start(context)
        return job.id
    }

    fun cancel(jobId: String) {
        val job = _jobs.value.firstOrNull { it.id == jobId } ?: return
        if (!job.isActive) return
        updateJob(jobId) { it.copy(state = JobState.CANCELED, statusLine = "キャンセルしました") }
        if (job.state == JobState.RUNNING) engine.cancel(jobId)
    }

    fun retry(jobId: String) {
        val job = _jobs.value.firstOrNull { it.id == jobId } ?: return
        if (job.isActive) return
        updateJob(jobId) {
            it.copy(state = JobState.QUEUED, progress = 0f, error = null, statusLine = "")
        }
        queue.trySend(jobId)
        DownloadService.start(context)
    }

    fun clearFinished() {
        _jobs.update { jobs -> jobs.filter { it.isActive } }
    }

    fun remove(jobId: String) {
        cancel(jobId)
        _jobs.update { jobs -> jobs.filterNot { it.id == jobId } }
    }

    private suspend fun runJob(jobId: String) {
        var job = _jobs.value.firstOrNull { it.id == jobId } ?: return
        if (job.state == JobState.CANCELED) return

        updateJob(jobId) { it.copy(state = JobState.RUNNING, statusLine = "準備中…") }

        try {
            if (job.needsMetadata) {
                val info = engine.fetchInfo(job.sourceUrl)
                updateJob(jobId) {
                    it.copy(
                        title = info.title,
                        uploader = info.uploader,
                        durationSec = info.durationSec,
                        needsMetadata = false,
                    )
                }
                job = _jobs.value.firstOrNull { it.id == jobId } ?: return
                if (job.state == JobState.CANCELED) return
            }

            val categoryId = library.getCategory(job.categoryId)?.id
                ?: library.getCategories().firstOrNull()?.id
                ?: error("保存先のカテゴリがありません")
            val targetDir = library.categoryDir(categoryId) ?: error("保存先のフォルダを解決できませんでした")

            val outcome = engine.download(
                sourceUrl = job.sourceUrl,
                videoId = job.videoId,
                targetDir = targetDir,
                kind = job.kind,
                quality = job.quality,
                processId = jobId,
            ) { percent, eta, line ->
                updateJob(jobId) { current ->
                    if (current.state != JobState.RUNNING) {
                        current
                    } else {
                        current.copy(
                            progress = (percent / 100f).coerceIn(0f, 1f),
                            etaSeconds = eta,
                            statusLine = line.take(120),
                        )
                    }
                }
            }

            library.addDownloaded(
                MediaItemEntity(
                    videoId = job.videoId,
                    title = job.title,
                    uploader = job.uploader,
                    durationSec = job.durationSec,
                    kind = job.kind.name,
                    categoryId = categoryId,
                    filePath = outcome.mediaFile.absolutePath,
                    thumbPath = outcome.thumbFile?.absolutePath,
                    fileSizeBytes = outcome.sizeBytes,
                    sourceUrl = job.sourceUrl,
                    downloadedAt = System.currentTimeMillis(),
                ),
            )

            updateJob(jobId) {
                it.copy(state = JobState.COMPLETED, progress = 1f, statusLine = "完了")
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "download failed for ${job.videoId}", e)
            updateJob(jobId) { current ->
                if (current.state == JobState.CANCELED) {
                    current
                } else {
                    current.copy(
                        state = JobState.FAILED,
                        error = e.message ?: "不明なエラー",
                        statusLine = "失敗",
                    )
                }
            }
        }
    }

    private fun updateJob(jobId: String, transform: (DownloadJob) -> DownloadJob) {
        _jobs.update { jobs ->
            jobs.map { if (it.id == jobId) transform(it) else it }
        }
    }

    private companion object {
        const val TAG = "DownloadCenter"
    }
}
