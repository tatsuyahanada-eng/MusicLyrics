package dev.hanada.tubevault.lyrics

import dev.hanada.tubevault.data.LibraryRepository
import dev.hanada.tubevault.data.MediaItemEntity
import dev.hanada.tubevault.playback.PlaybackController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

sealed interface LyricsUiState {
    data object Idle : LyricsUiState
    data object Loading : LyricsUiState
    data class Synced(val lines: List<LyricLine>) : LyricsUiState
    data class PlainOnly(val text: String) : LyricsUiState
    data object NotFound : LyricsUiState
}

/** The current artist/title query, so the correction dialog can show what was searched for. */
data class LyricsQuery(val artist: String, val title: String)

/**
 * Fetches lyrics for whatever [PlaybackController] is currently playing.
 *
 * Application-scoped like [PlaybackController] itself: it watches
 * [PlaybackController.currentItem] for the life of the process rather than
 * being recreated per screen, so lyrics keep tracking playback across
 * navigation the same way the mini player does.
 */
class LyricsController(
    private val playback: PlaybackController,
    private val library: LibraryRepository,
    private val repository: LyricsRepository = LyricsRepository(),
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val _state = MutableStateFlow<LyricsUiState>(LyricsUiState.Idle)
    val state: StateFlow<LyricsUiState> = _state.asStateFlow()

    private val _query = MutableStateFlow<LyricsQuery?>(null)
    val query: StateFlow<LyricsQuery?> = _query.asStateFlow()

    private var fetchJob: Job? = null
    private var currentItemId: Long? = null

    init {
        scope.launch {
            playback.currentItem.collect(::onItemChanged)
        }
    }

    private fun onItemChanged(item: MediaItemEntity?) {
        if (item == null) {
            currentItemId = null
            _query.value = null
            _state.value = LyricsUiState.Idle
            fetchJob?.cancel()
            return
        }
        if (item.id == currentItemId) return
        currentItemId = item.id
        val guess = resolveQuery(item)
        _query.value = guess
        fetch(guess.artist, guess.title)
    }

    private fun resolveQuery(item: MediaItemEntity): LyricsQuery {
        val savedArtist = item.lyricsArtist?.trim().orEmpty()
        val savedTitle = item.lyricsTitle?.trim().orEmpty()
        if (savedArtist.isNotEmpty() && savedTitle.isNotEmpty()) return LyricsQuery(savedArtist, savedTitle)
        val (guessArtist, guessTitle) = LyricsGuess.guess(item.title, item.uploader)
        return LyricsQuery(
            savedArtist.ifEmpty { guessArtist },
            savedTitle.ifEmpty { guessTitle },
        )
    }

    private fun fetch(artist: String, title: String) {
        fetchJob?.cancel()
        if (artist.isBlank() || title.isBlank()) {
            _state.value = LyricsUiState.NotFound
            return
        }
        _state.value = LyricsUiState.Loading
        fetchJob = scope.launch {
            val result = runCatching { repository.fetch(artist, title) }.getOrElse { LyricsResult.NotFound }
            _state.value = when (result) {
                is LyricsResult.Synced -> LyricsUiState.Synced(result.lines)
                is LyricsResult.PlainOnly -> LyricsUiState.PlainOnly(result.text)
                LyricsResult.NotFound -> LyricsUiState.NotFound
            }
        }
    }

    /** The user corrected the artist/title from the player UI — re-search and remember it. */
    fun applyCorrection(artist: String, title: String) {
        val id = currentItemId ?: return
        val cleanArtist = artist.trim()
        val cleanTitle = title.trim()
        _query.value = LyricsQuery(cleanArtist, cleanTitle)
        fetch(cleanArtist, cleanTitle)
        scope.launch {
            runCatching { library.updateLyricsInfo(id, cleanArtist, cleanTitle) }
        }
    }
}

/** The last line whose timestamp has passed, or -1 before the first line. */
fun currentLyricLineIndex(lines: List<LyricLine>, positionMs: Long): Int {
    var index = -1
    for (i in lines.indices) {
        if (lines[i].timeMs > positionMs) break
        index = i
    }
    return index
}
