package com.voicetranscriber.app

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import java.util.Locale

/**
 * Thin wrapper around Android's [SpeechRecognizer].
 *
 * It supports two usage patterns that the UI exposes as separate buttons:
 *  - **Hold to talk:** [start] on press, [stop] on release.
 *  - **Continuous:** [start] with `continuous = true`; the manager automatically
 *    restarts the recognizer whenever it ends, so transcription never stops until
 *    [stop] is called.
 *
 * All callbacks are invoked on the main thread.
 */
class SpeechRecognizerManager(
    private val context: Context,
    private val languageTag: String = Locale.getDefault().toLanguageTag(),
) {

    interface Callbacks {
        /** A finalized chunk of recognized text (one recognition session). */
        fun onFinalText(text: String)

        /** Live, not-yet-finalized text for the current utterance. */
        fun onPartialText(text: String)

        /** Listening (microphone) actually started. */
        fun onListeningStarted()

        /** Listening fully stopped and no automatic restart will happen. */
        fun onListeningStopped()

        /** A non-fatal problem occurred. */
        fun onError(message: String)
    }

    private var recognizer: SpeechRecognizer? = null
    private var callbacks: Callbacks? = null

    /** True while the user wants transcription to keep going (continuous mode). */
    private var continuous = false

    /** True between a start request and the corresponding stop. */
    private var active = false

    fun setCallbacks(callbacks: Callbacks?) {
        this.callbacks = callbacks
    }

    fun isAvailable(): Boolean = SpeechRecognizer.isRecognitionAvailable(context)

    fun start(continuous: Boolean) {
        this.continuous = continuous
        this.active = true
        ensureRecognizer()
        beginListening()
    }

    fun stop() {
        active = false
        continuous = false
        // Use stopListening() (not cancel()) so any in-flight utterance is still
        // finalized and delivered through onResults — important for hold-to-talk,
        // where the whole result arrives only after the button is released.
        recognizer?.stopListening()
        callbacks?.onListeningStopped()
    }

    fun destroy() {
        active = false
        continuous = false
        recognizer?.destroy()
        recognizer = null
    }

    private fun ensureRecognizer() {
        if (recognizer == null) {
            recognizer = SpeechRecognizer.createSpeechRecognizer(context).apply {
                setRecognitionListener(listener)
            }
        }
    }

    private fun beginListening() {
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(
                RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                RecognizerIntent.LANGUAGE_MODEL_FREE_FORM,
            )
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, languageTag)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            // Keep the mic open a little longer through natural pauses.
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 2000L)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 2000L)
        }
        recognizer?.startListening(intent)
    }

    /** Restart the recognizer for the next utterance when in continuous mode. */
    private fun restartIfNeeded() {
        if (active && continuous) {
            beginListening()
        } else {
            active = false
            callbacks?.onListeningStopped()
        }
    }

    private val listener = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {
            callbacks?.onListeningStarted()
        }

        override fun onBeginningOfSpeech() {}

        override fun onRmsChanged(rmsdB: Float) {}

        override fun onBufferReceived(buffer: ByteArray?) {}

        override fun onEndOfSpeech() {}

        override fun onPartialResults(partialResults: Bundle?) {
            val text = partialResults
                ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                ?.firstOrNull()
                .orEmpty()
            if (text.isNotEmpty()) {
                callbacks?.onPartialText(text)
            }
        }

        override fun onResults(results: Bundle?) {
            val text = results
                ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                ?.firstOrNull()
                .orEmpty()
            if (text.isNotEmpty()) {
                callbacks?.onFinalText(text)
            }
            restartIfNeeded()
        }

        override fun onError(error: Int) {
            // In continuous mode "no match" / "speech timeout" are expected during
            // silence — just restart quietly instead of surfacing an error.
            val recoverable = error == SpeechRecognizer.ERROR_NO_MATCH ||
                error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT
            if (continuous && active && recoverable) {
                beginListening()
                return
            }
            if (!recoverable) {
                callbacks?.onError(errorMessage(error))
            }
            restartIfNeeded()
        }

        override fun onEvent(eventType: Int, params: Bundle?) {}
    }

    private fun errorMessage(error: Int): String = when (error) {
        SpeechRecognizer.ERROR_AUDIO -> "音声の取得に失敗しました"
        SpeechRecognizer.ERROR_CLIENT -> "クライアントエラーが発生しました"
        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "マイクの権限がありません"
        SpeechRecognizer.ERROR_NETWORK -> "ネットワークエラーが発生しました"
        SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "ネットワークがタイムアウトしました"
        SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "認識エンジンが使用中です"
        SpeechRecognizer.ERROR_SERVER -> "サーバーエラーが発生しました"
        else -> "音声認識エラー (code=$error)"
    }
}
