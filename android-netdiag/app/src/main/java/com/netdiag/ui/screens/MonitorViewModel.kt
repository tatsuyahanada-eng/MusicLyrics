package com.netdiag.ui.screens

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import com.netdiag.core.DiagnosticsLog
import com.netdiag.core.monitor.AlertStore
import com.netdiag.core.monitor.LanMonitor
import com.netdiag.core.monitor.MonitorService

class MonitorViewModel(app: Application) : AndroidViewModel(app) {

    val status = LanMonitor.status
    val alerts = AlertStore.alerts

    init {
        AlertStore.init(app)
        DiagnosticsLog.init(app)
    }

    fun start() = MonitorService.start(getApplication<Application>())

    fun stop() = MonitorService.stop(getApplication<Application>())

    fun toggle() {
        if (LanMonitor.isRunning()) stop() else start()
    }

    /** Accepts the current gateway/DHCP/DNS as normal for this network. */
    fun rebaseline() = LanMonitor.rebaseline(getApplication<Application>())

    fun clearAlerts() = AlertStore.clear()
}
