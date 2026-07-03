package com.netdiag.ui.screens

import android.app.Application
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import com.netdiag.core.DiagnosticsLog
import com.netdiag.core.ImageStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * A simple scratchpad for jotting down router IPs, device names, SSIDs, etc.
 * Persisted in SharedPreferences so it survives app restarts until cleared.
 */
class MemoViewModel(app: Application) : AndroidViewModel(app) {

    private val prefs = app.getSharedPreferences("netdiag_memo", Context.MODE_PRIVATE)
    private val _text = MutableStateFlow(prefs.getString(KEY, "") ?: "")
    val text: StateFlow<String> = _text.asStateFlow()

    init {
        DiagnosticsLog.init(app)
        ImageStore.init(app)
    }

    fun setText(v: String) {
        _text.value = v
        prefs.edit().putString(KEY, v).apply()
    }

    /** Appends a block of text (e.g. OCR result) on a new line. */
    fun append(v: String) {
        if (v.isBlank()) return
        val current = _text.value
        setText(if (current.isBlank()) v else "$current\n$v")
    }

    fun clear() = setText("")

    private companion object {
        const val KEY = "memo_text"
    }
}
