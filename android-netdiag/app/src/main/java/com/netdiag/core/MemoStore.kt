package com.netdiag.core

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * App-wide, persisted memo text (router IPs, device names, SSIDs, OCR results,
 * etc). A top-level singleton — like [DiagnosticsLog] / [ImageStore] — rather
 * than ViewModel-local state, so a global action such as "オールクリア" can
 * clear it and have every open screen see the change immediately.
 */
object MemoStore {

    private const val KEY = "memo_text"

    private var prefs: android.content.SharedPreferences? = null
    private val _text = MutableStateFlow("")
    val text: StateFlow<String> = _text.asStateFlow()

    /** Idempotent; safe to call from every ViewModel that touches the memo. */
    fun init(context: Context) {
        if (prefs != null) return
        val p = context.applicationContext.getSharedPreferences("netdiag_memo", Context.MODE_PRIVATE)
        prefs = p
        _text.value = p.getString(KEY, "") ?: ""
    }

    fun setText(v: String) {
        _text.value = v
        prefs?.edit()?.putString(KEY, v)?.apply()
    }

    /** Appends a block of text (e.g. OCR result) on a new line. */
    fun append(v: String) {
        if (v.isBlank()) return
        val current = _text.value
        setText(if (current.isBlank()) v else "$current\n$v")
    }

    fun clear() = setText("")
}
