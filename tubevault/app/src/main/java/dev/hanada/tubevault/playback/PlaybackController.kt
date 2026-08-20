package dev.hanada.tubevault.playback

import android.content.ComponentName
import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture
import dev.hanada.tubevault.data.LibraryRepository
import dev.hanada.tubevault.data.MediaItemEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File

/**
 * Application-scoped bridge to [PlaybackService].
 *
 * Holding the controller here rather than in a composable means playback
 * survives navigation, and the mini player can stay on screen while the user
 * browses other folders.
 */
class PlaybackController(
    private val context: Context,
    private val library: LibraryRepository,
) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private var controllerFuture: ListenableFuture<MediaController>? = null
    private var progressJob: Job? = null

    private val _player = MutableStateFlow<MediaController?>(null)
    val player: StateFlow<MediaController?> = _player.asStateFlow()

    private val _currentItem = MutableStateFlow<MediaItemEntity?>(null)
    val currentItem: StateFlow<MediaItemEntity?> = _currentItem.asStateFlow()

    private val _isPlaying = MutableStateFlow(false)
    val isPlaying: StateFlow<Boolean> = _isPlaying.asStateFlow()

    private val _positionMs = MutableStateFlow(0L)
    val positionMs: StateFlow<Long> = _positionMs.asStateFlow()

    private val _durationMs = MutableStateFlow(0L)
    val durationMs: StateFlow<Long> = _durationMs.asStateFlow()

    /** Whether the full-screen player is open, as opposed to the mini bar. */
    private val _expanded = MutableStateFlow(false)
    val expanded: StateFlow<Boolean> = _expanded.asStateFlow()

    private val _shuffleEnabled = MutableStateFlow(false)
    val shuffleEnabled: StateFlow<Boolean> = _shuffleEnabled.asStateFlow()

    private val listener = object : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) {
            _isPlaying.value = isPlaying
            if (isPlaying) startProgressLoop() else persistPosition()
        }

        override fun onShuffleModeEnabledChanged(shuffleModeEnabled: Boolean) {
            _shuffleEnabled.value = shuffleModeEnabled
        }

        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            val id = mediaItem?.mediaId?.toLongOrNull() ?: return
            // Marks on every transition, not just changed ones, so tracks
            // reached by auto-advance and shuffle retire their badge too.
            scope.launch {
                library.markPlayed(id)
                if (_currentItem.value?.id != id) _currentItem.value = library.getItem(id)
            }
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            if (playbackState == Player.STATE_READY) {
                _durationMs.value = _player.value?.duration?.coerceAtLeast(0L) ?: 0L
            }
            if (playbackState == Player.STATE_ENDED) persistPosition(resetToStart = true)
        }
    }

    fun connect() {
        if (controllerFuture != null) return
        val token = SessionToken(context, ComponentName(context, PlaybackService::class.java))
        val future = MediaController.Builder(context, token).buildAsync()
        controllerFuture = future
        future.addListener(
            {
                runCatching { future.get() }
                    .onSuccess { controller ->
                        controller.addListener(listener)
                        _player.value = controller
                        _isPlaying.value = controller.isPlaying
                        _shuffleEnabled.value = controller.shuffleModeEnabled
                    }
                    .onFailure { Log.w(TAG, "could not connect to PlaybackService", it) }
            },
            ContextCompat.getMainExecutor(context),
        )
    }

    fun release() {
        progressJob?.cancel()
        _player.value?.removeListener(listener)
        controllerFuture?.let { MediaController.releaseFuture(it) }
        controllerFuture = null
        _player.value = null
    }

    fun play(item: MediaItemEntity) = playQueue(listOf(item), 0)

    /**
     * Playing a whole folder is what turns a category into a playlist.
     *
     * With [shuffle] on, the starting track is picked at random too — leaving
     * it at index 0 would make every shuffled run open with the same track,
     * which reads as "not actually shuffled" however random the rest is.
     */
    fun playQueue(items: List<MediaItemEntity>, startIndex: Int, shuffle: Boolean = false) {
        if (items.isEmpty()) return
        val index = if (shuffle) items.indices.random() else startIndex.coerceIn(0, items.lastIndex)
        connect()
        _currentItem.value = items[index]
        _expanded.value = true

        scope.launch {
            val controller = awaitController() ?: return@launch
            controller.shuffleModeEnabled = shuffle
            _shuffleEnabled.value = shuffle
            controller.setMediaItems(items.map(::toMediaItem), index, C.TIME_UNSET)
            controller.prepare()
            // Resuming mid-track fights the point of a shuffled run.
            val resumeFrom = items[index].playbackPosMs
            if (!shuffle && resumeFrom > RESUME_THRESHOLD_MS) controller.seekTo(resumeFrom)
            controller.play()
            library.markPlayed(items[index].id)
        }
    }

    fun toggleShuffle() {
        val controller = _player.value ?: return
        val next = !controller.shuffleModeEnabled
        controller.shuffleModeEnabled = next
        _shuffleEnabled.value = next
    }

    fun togglePlayPause() {
        val controller = _player.value ?: return
        if (controller.isPlaying) controller.pause() else controller.play()
    }

    fun seekTo(positionMs: Long) {
        _player.value?.seekTo(positionMs)
        _positionMs.value = positionMs
    }

    fun skipBy(deltaMs: Long) {
        val controller = _player.value ?: return
        seekTo((controller.currentPosition + deltaMs).coerceIn(0L, controller.duration.coerceAtLeast(0L)))
    }

    fun next() {
        _player.value?.seekToNextMediaItem()
    }

    fun previous() {
        _player.value?.seekToPreviousMediaItem()
    }

    fun setExpanded(value: Boolean) {
        _expanded.value = value
    }

    fun stop() {
        persistPosition()
        _player.value?.stop()
        _player.value?.clearMediaItems()
        _currentItem.value = null
        _expanded.value = false
        _isPlaying.value = false
    }

    /** Drops the mini player when its file was just deleted from the library. */
    fun stopIfPlaying(itemId: Long) {
        if (_currentItem.value?.id == itemId) stop()
    }

    private fun toMediaItem(item: MediaItemEntity): MediaItem {
        val metadata = MediaMetadata.Builder()
            .setTitle(item.title)
            .setArtist(item.uploader)
            .apply {
                item.thumbPath?.let { setArtworkUri(Uri.fromFile(File(it))) }
            }
            .build()
        return MediaItem.Builder()
            .setUri(Uri.fromFile(File(item.filePath)))
            .setMediaId(item.id.toString())
            .setMediaMetadata(metadata)
            .build()
    }

    private suspend fun awaitController(): MediaController? {
        _player.value?.let { return it }
        return withTimeoutOrNull(CONNECT_TIMEOUT_MS) { _player.filterNotNull().first() }
    }

    private fun startProgressLoop() {
        progressJob?.cancel()
        progressJob = scope.launch {
            var sinceSave = 0L
            while (true) {
                val controller = _player.value ?: break
                _positionMs.value = controller.currentPosition.coerceAtLeast(0L)
                _durationMs.value = controller.duration.coerceAtLeast(0L)
                sinceSave += TICK_MS
                if (sinceSave >= SAVE_INTERVAL_MS) {
                    sinceSave = 0L
                    persistPosition()
                }
                delay(TICK_MS)
            }
        }
    }

    private fun persistPosition(resetToStart: Boolean = false) {
        val item = _currentItem.value ?: return
        val position = if (resetToStart) 0L else _player.value?.currentPosition ?: return
        scope.launch { library.recordPlayback(item.id, position.coerceAtLeast(0L)) }
    }

    private companion object {
        const val TAG = "PlaybackController"
        const val TICK_MS = 500L
        const val SAVE_INTERVAL_MS = 5_000L
        const val RESUME_THRESHOLD_MS = 5_000L
        const val CONNECT_TIMEOUT_MS = 5_000L
    }
}
