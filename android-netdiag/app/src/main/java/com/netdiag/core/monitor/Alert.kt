package com.netdiag.core.monitor

import android.content.Context
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow

/** How serious a detection is, used for colour-coding and notification level. */
enum class AlertSeverity { INFO, WARN, CRITICAL }

/** What kind of problem was detected, so the UI can group/filter. */
enum class AlertCategory(val label: String) {
    DHCP("DHCP"),
    GATEWAY("ゲートウェイ"),
    DNS("DNS"),
    DEVICE("機器"),
    TRAFFIC("トラフィック"),
    PROTOCOL("プロトコル"),
}

/**
 * One detection raised by [LanMonitor].
 *
 * [key] identifies the *kind* of finding (e.g. "gateway-changed") so repeated
 * detections of the same condition can be suppressed instead of spamming.
 */
data class Alert(
    val key: String,
    val timeMillis: Long,
    val severity: AlertSeverity,
    val category: AlertCategory,
    val title: String,
    val detail: String,
)

/**
 * App-wide, persisted list of monitoring alerts. Mirrors DiagnosticsLog's
 * design (SharedPreferences-backed StateFlow) so the UI, the notification
 * service and the PDF export can all read the same source.
 *
 * Re-raising the same [Alert.key] inside [COOLDOWN_MS] is ignored, so a
 * condition that stays true (e.g. an unexpected DNS server) alerts once rather
 * than on every scan tick.
 */
object AlertStore {

    private const val MAX_ENTRIES = 500
    private const val COOLDOWN_MS = 10 * 60 * 1000L

    private var prefs: android.content.SharedPreferences? = null
    private val lastRaised = HashMap<String, Long>()

    private val _alerts = MutableStateFlow<List<Alert>>(emptyList())
    val alerts: StateFlow<List<Alert>> = _alerts.asStateFlow()

    /** Emits only newly raised alerts, so the service can notify on each one. */
    private val _raised = MutableSharedFlow<Alert>(extraBufferCapacity = 32)
    val raised: SharedFlow<Alert> = _raised.asSharedFlow()

    /** Idempotent; safe to call from the service and every ViewModel. */
    fun init(context: Context) {
        if (prefs != null) return
        val p = context.applicationContext
            .getSharedPreferences("netdiag_alerts", Context.MODE_PRIVATE)
        prefs = p
        _alerts.value = deserialize(p.getString("alerts", "") ?: "")
    }

    /**
     * Records an alert unless the same [Alert.key] fired recently.
     * Returns the stored alert, or null when it was suppressed.
     */
    fun raise(alert: Alert): Alert? {
        val previous = lastRaised[alert.key]
        if (previous != null && alert.timeMillis - previous < COOLDOWN_MS) return null
        lastRaised[alert.key] = alert.timeMillis
        _alerts.value = (_alerts.value + alert).takeLast(MAX_ENTRIES)
        persist()
        _raised.tryEmit(alert)
        return alert
    }

    fun clear() {
        _alerts.value = emptyList()
        lastRaised.clear()
        persist()
    }

    /** Whole alert list as plain text (oldest first), for copy / PDF export. */
    fun asPlainText(): String =
        _alerts.value.joinToString("\n") {
            "${com.netdiag.core.DiagnosticsLog.formatTime(it.timeMillis)}  " +
                "[${it.severity}] ${it.category.label} ${it.title} — ${it.detail}"
        }

    private fun persist() {
        prefs?.edit()?.putString("alerts", serialize(_alerts.value))?.apply()
    }

    private fun serialize(list: List<Alert>): String =
        list.joinToString("\n") {
            listOf(
                it.timeMillis.toString(),
                it.key,
                it.severity.name,
                it.category.name,
                it.title.clean(),
                it.detail.clean(),
            ).joinToString("\t")
        }

    private fun deserialize(raw: String): List<Alert> =
        raw.lineSequence().mapNotNull { line ->
            val c = line.split("\t")
            if (c.size < 6) return@mapNotNull null
            val time = c[0].toLongOrNull() ?: return@mapNotNull null
            val severity = runCatching { AlertSeverity.valueOf(c[2]) }.getOrNull()
                ?: return@mapNotNull null
            val category = runCatching { AlertCategory.valueOf(c[3]) }.getOrNull()
                ?: return@mapNotNull null
            Alert(c[1], time, severity, category, c[4], c[5])
        }.toList()

    private fun String.clean(): String =
        replace("\t", " ").replace("\n", " ").trim()
}
