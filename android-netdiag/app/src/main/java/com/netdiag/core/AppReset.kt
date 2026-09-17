package com.netdiag.core

import android.content.Context
import com.netdiag.core.monitor.AlertStore
import com.netdiag.core.monitor.LanMonitor

/**
 * Backing action for the header's "オールクリア" button: wipes every piece of
 * saved/accumulated app state — memo text, the diagnostic log, monitoring
 * alerts, captured device photos, and remembered network baselines — so the
 * user can start completely fresh. Deliberately leaves display settings
 * (theme/accent) alone, since those are app configuration, not "results".
 *
 * Does not stop LanMonitor if it is currently running; monitoring keeps going
 * and simply captures a brand-new baseline on its next tick.
 */
object AppReset {

    fun clearAll(context: Context) {
        val app = context.applicationContext

        DiagnosticsLog.init(app)
        DiagnosticsLog.clear()

        MemoStore.init(app)
        MemoStore.clear()

        ImageStore.init(app)
        ImageStore.clear()

        AlertStore.init(app)
        AlertStore.clear()

        LanMonitor.resetAll(app)
    }
}
