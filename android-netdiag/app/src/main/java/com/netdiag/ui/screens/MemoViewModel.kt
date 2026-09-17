package com.netdiag.ui.screens

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import com.netdiag.core.DiagnosticsLog
import com.netdiag.core.ImageStore
import com.netdiag.core.MemoStore
import com.netdiag.core.monitor.AlertStore

/**
 * A simple scratchpad for jotting down router IPs, device names, SSIDs, etc.
 * Backed by [MemoStore], a persisted app-wide singleton, so a global action
 * (e.g. the header's オールクリア) is visible here immediately.
 */
class MemoViewModel(app: Application) : AndroidViewModel(app) {

    val text = MemoStore.text

    init {
        MemoStore.init(app)
        DiagnosticsLog.init(app)
        ImageStore.init(app)
        // So the PDF export can include monitoring alerts even if the 監視 tab
        // was never opened in this session.
        AlertStore.init(app)
    }

    fun setText(v: String) = MemoStore.setText(v)

    /** Appends a block of text (e.g. OCR result) on a new line. */
    fun append(v: String) = MemoStore.append(v)

    fun clear() = MemoStore.clear()
}
