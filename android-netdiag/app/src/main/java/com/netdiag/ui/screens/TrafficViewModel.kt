package com.netdiag.ui.screens

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.netdiag.core.traffic.AppTraffic
import com.netdiag.core.traffic.TrafficMonitor
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class TrafficUiState(
    val monitoring: Boolean = false,
    val rxRate: Long = 0,
    val txRate: Long = 0,
    val peakRxRate: Long = 0,
    val peakTxRate: Long = 0,
    val hasUsageAccess: Boolean = false,
    val apps: List<AppTraffic> = emptyList(),
    val loadingApps: Boolean = false,
)

class TrafficViewModel(app: Application) : AndroidViewModel(app) {

    private val monitor = TrafficMonitor(app)
    private val _state = MutableStateFlow(TrafficUiState(hasUsageAccess = monitor.hasUsageAccess()))
    val state: StateFlow<TrafficUiState> = _state.asStateFlow()

    private var tpJob: Job? = null

    /** START/STOP the live throughput meter on demand. */
    fun toggleMonitoring() {
        if (_state.value.monitoring) stopMonitoring() else startMonitoring()
    }

    fun startMonitoring() {
        if (_state.value.monitoring) return
        _state.update { it.copy(monitoring = true, peakRxRate = 0, peakTxRate = 0) }
        tpJob = viewModelScope.launch {
            monitor.throughput(1000).collect { sample ->
                _state.update {
                    it.copy(
                        rxRate = sample.rxBytesPerSec,
                        txRate = sample.txBytesPerSec,
                        peakRxRate = maxOf(it.peakRxRate, sample.rxBytesPerSec),
                        peakTxRate = maxOf(it.peakTxRate, sample.txBytesPerSec),
                    )
                }
            }
        }
    }

    fun stopMonitoring() {
        tpJob?.cancel()
        tpJob = null
        _state.update { it.copy(monitoring = false, rxRate = 0, txRate = 0) }
    }

    /** Re-checks the usage-access grant (e.g. after returning from Settings). */
    fun refreshPermission() {
        val has = monitor.hasUsageAccess()
        _state.update { it.copy(hasUsageAccess = has) }
        if (has) loadApps()
    }

    fun loadApps() {
        if (!monitor.hasUsageAccess()) return
        _state.update { it.copy(loadingApps = true) }
        viewModelScope.launch {
            val apps = monitor.wifiUsageByApp()
            _state.update { it.copy(apps = apps, loadingApps = false) }
        }
    }
}
