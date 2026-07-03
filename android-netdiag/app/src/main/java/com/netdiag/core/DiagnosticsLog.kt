package com.netdiag.core

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** One recorded diagnostic result with the exact time it happened. */
data class LogEntry(val timeMillis: Long, val text: String)

/**
 * App-wide, persisted log of diagnostic results (ping / traceroute / DNS /
 * reachability). Written by the Diagnose screen, shown/exported by the Records
 * screen. Each entry keeps a precise timestamp.
 */
object DiagnosticsLog {

    private const val MAX_ENTRIES = 1000
    private val timeFmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())

    private var prefs: android.content.SharedPreferences? = null
    private val _entries = MutableStateFlow<List<LogEntry>>(emptyList())
    val entries: StateFlow<List<LogEntry>> = _entries.asStateFlow()

    /** Idempotent; safe to call from every ViewModel that uses the log. */
    fun init(context: Context) {
        if (prefs != null) return
        val p = context.applicationContext.getSharedPreferences("netdiag_log", Context.MODE_PRIVATE)
        prefs = p
        _entries.value = deserialize(p.getString("entries", "") ?: "")
    }

    fun add(text: String) {
        val clean = text.replace("\t", " ").replace("\n", " ").trim()
        if (clean.isEmpty()) return
        val next = (_entries.value + LogEntry(System.currentTimeMillis(), clean)).takeLast(MAX_ENTRIES)
        _entries.value = next
        persist()
    }

    fun clear() {
        _entries.value = emptyList()
        persist()
    }

    fun formatTime(millis: Long): String = timeFmt.format(Date(millis))

    /** Whole log as plain text (oldest first), for copy / PDF. */
    fun asPlainText(): String =
        _entries.value.joinToString("\n") { "${formatTime(it.timeMillis)}  ${it.text}" }

    private fun persist() {
        prefs?.edit()?.putString("entries", serialize(_entries.value))?.apply()
    }

    private fun serialize(list: List<LogEntry>): String =
        list.joinToString("\n") { "${it.timeMillis}\t${it.text}" }

    private fun deserialize(raw: String): List<LogEntry> =
        raw.lineSequence()
            .mapNotNull { line ->
                val i = line.indexOf('\t')
                if (i <= 0) return@mapNotNull null
                val t = line.substring(0, i).toLongOrNull() ?: return@mapNotNull null
                LogEntry(t, line.substring(i + 1))
            }
            .toList()
}
