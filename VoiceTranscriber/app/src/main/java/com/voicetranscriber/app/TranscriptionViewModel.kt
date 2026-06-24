package com.voicetranscriber.app

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Which recording interaction is currently active. */
enum class Mode { NONE, HOLD, CONTINUOUS }

data class TranscriptionState(
    /** Finalized transcript built up from completed recognition sessions. */
    val transcript: String = "",
    /** Live partial text for the current utterance (shown but not yet committed). */
    val partial: String = "",
    val mode: Mode = Mode.NONE,
    val isListening: Boolean = false,
    val error: String? = null,
)

class TranscriptionViewModel(app: Application) : AndroidViewModel(app),
    SpeechRecognizerManager.Callbacks {

    private val manager = SpeechRecognizerManager(app.applicationContext)

    private val _state = MutableStateFlow(TranscriptionState())
    val state: StateFlow<TranscriptionState> = _state.asStateFlow()

    val isRecognitionAvailable: Boolean = manager.isAvailable()

    init {
        manager.setCallbacks(this)
    }

    // ----- UI intents -------------------------------------------------------

    /** Begin "hold to talk": call on button press. */
    fun startHold() {
        if (_state.value.mode != Mode.NONE) return
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
                _state.value = _state.value.copy(mode = Mode.CONTINUOUS, error = null)
                manager.start(continuous = true)
            }
            Mode.HOLD -> Unit // ignore while hold-to-talk is in progress
        }
    }

    fun updateTranscript(text: String) {
        _state.value = _state.value.copy(transcript = text)
    }

    fun clearTranscript() {
        _state.value = _state.value.copy(transcript = "", partial = "")
    }

    fun dismissError() {
        _state.value = _state.value.copy(error = null)
    }

    // ----- SpeechRecognizerManager.Callbacks --------------------------------

    override fun onFinalText(text: String) {
        val current = _state.value.transcript
        val merged = if (current.isBlank()) text else "$current\n$text"
        _state.value = _state.value.copy(transcript = merged, partial = "")
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
