package com.voicetranscriber.app

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Which recording interaction is currently active. */
enum class Mode { NONE, HOLD, CONTINUOUS }

/** One finalized chunk of transcript plus which mode produced it. */
data class TranscriptSegment(val text: String, val mode: Mode)

data class TranscriptionState(
    /** Finalized transcript segments, each tagged with its source mode. */
    val segments: List<TranscriptSegment> = emptyList(),
    /** Live partial text for the current utterance (shown but not yet committed). */
    val partial: String = "",
    val mode: Mode = Mode.NONE,
    val isListening: Boolean = false,
    val error: String? = null,
) {
    /** Plain joined text (for copy / save / edit). */
    val transcript: String get() = segments.joinToString("\n") { it.text }
}

class TranscriptionViewModel(app: Application) : AndroidViewModel(app),
    SpeechRecognizerManager.Callbacks {

    private val manager = SpeechRecognizerManager(app.applicationContext)

    private val _state = MutableStateFlow(TranscriptionState())
    val state: StateFlow<TranscriptionState> = _state.asStateFlow()

    val isRecognitionAvailable: Boolean = manager.isAvailable()

    /** Mode captured at recognition start — used to tag results even after the
     *  mode has reset (e.g. hold-to-talk delivers its result after release). */
    private var capturedMode: Mode = Mode.NONE

    init {
        manager.setCallbacks(this)
    }

    // ----- UI intents -------------------------------------------------------

    /** Begin "hold to talk": call on button press. */
    fun startHold() {
        if (_state.value.mode != Mode.NONE) return
        capturedMode = Mode.HOLD
        _state.value = _state.value.copy(mode = Mode.HOLD, error = null)
        manager.start(continuous = false)
    }

    /** End "hold to talk": call on button release. */
    fun stopHold() {
        if (_state.value.mode != Mode.HOLD) return
        manager.stop()
    }

    /** Toggle continuous transcription on/off. */
    fun toggleContinuous() {
        when (_state.value.mode) {
            Mode.CONTINUOUS -> manager.stop()
            Mode.NONE -> {
                capturedMode = Mode.CONTINUOUS
                _state.value = _state.value.copy(mode = Mode.CONTINUOUS, error = null)
                manager.start(continuous = true)
            }
            Mode.HOLD -> Unit // ignore while hold-to-talk is in progress
        }
    }

    /** Rebuild segments from manually edited text, keeping each line's mode by index. */
    fun updateTranscript(text: String) {
        val old = _state.value.segments
        val newSegments = text.split("\n").mapIndexed { i, line ->
            TranscriptSegment(line, old.getOrNull(i)?.mode ?: Mode.NONE)
        }
        _state.value = _state.value.copy(segments = newSegments)
    }

    fun clearTranscript() {
        _state.value = _state.value.copy(segments = emptyList(), partial = "")
    }

    fun dismissError() {
        _state.value = _state.value.copy(error = null)
    }

    // ----- SpeechRecognizerManager.Callbacks --------------------------------

    override fun onFinalText(text: String) {
        val segment = TranscriptSegment(text, capturedMode)
        _state.value = _state.value.copy(
            segments = _state.value.segments + segment,
            partial = "",
        )
    }

    override fun onPartialText(text: String) {
        _state.value = _state.value.copy(partial = text)
    }

    override fun onListeningStarted() {
        _state.value = _state.value.copy(isListening = true)
    }

    override fun onListeningStopped() {
        _state.value = _state.value.copy(
            isListening = false,
            mode = Mode.NONE,
            partial = "",
        )
    }

    override fun onError(message: String) {
        _state.value = _state.value.copy(error = message)
    }

    override fun onCleared() {
        super.onCleared()
        manager.setCallbacks(null)
        manager.destroy()
    }
}
