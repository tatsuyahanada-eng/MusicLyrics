package com.netdiag.ui.screens

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
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
    val durationSec: Int = 30,
    val remainingSec: Int = 0,
    // Result of the finished measurement window (averages).
    val hasResult: Boolean = false,
    val resultRxAvg: Long = 0,
    val resultTxAvg: Long = 0,
    val resultDurationSec: Int = 0,
)

class TrafficViewModel(app: Application) : AndroidViewModel(app) {

    private val monitor = TrafficMonitor(app)
    private val _state = MutableStateFlow(TrafficUiState())
    val state: StateFlow<TrafficUiState> = _state.asStateFlow()

    private var tpJob: Job? = null
    private var rxSum = 0L
    private var txSum = 0L
    private var sampleCount = 0

    fun setDuration(sec: Int) {
        if (!_state.value.monitoring) _state.update { it.copy(durationSec = sec) }
    }

    fun toggleMonitoring() {
        if (_state.value.monitoring) stopMonitoring() else startMonitoring()
    }

    /** Starts the meter, accumulating averages, and auto-stops after the window. */
    fun startMonitoring() {
        if (_state.value.monitoring) return
        val duration = _state.value.durationSec
        rxSum = 0; txSum = 0; sampleCount = 0
        _state.update {
            it.copy(
                monitoring = true, hasResult = false,
                rxRate = 0, txRate = 0, peakRxRate = 0, peakTxRate = 0,
                remainingSec = duration,
            )
        }
        tpJob = viewModelScope.launch {
            var remaining = duration
            monitor.throughput(1000).collect { sample ->
                rxSum += sample.rxBytesPerSec
                txSum += sample.txBytesPerSec
                sampleCount++
                _state.update {
                    it.copy(
                        rxRate = sample.rxBytesPerSec,
                        txRate = sample.txBytesPerSec,
                        peakRxRate = maxOf(it.peakRxRate, sample.rxBytesPerSec),
                        peakTxRate = maxOf(it.peakTxRate, sample.txBytesPerSec),
                    )
                }
                remaining--
                _state.update { it.copy(remainingSec = remaining.coerceAtLeast(0)) }
                if (remaining <= 0) {
                    stopMonitoring()
                    return@collect
                }
            }
        }
    }

    fun stopMonitoring() {
        tpJob?.cancel()
        tpJob = null
        val avgRx = if (sampleCount > 0) rxSum / sampleCount else 0
        val avgTx = if (sampleCount > 0) txSum / sampleCount else 0
        _state.update {
            it.copy(
                monitoring = false,
                remainingSec = 0,
                hasResult = sampleCount > 0,
                resultRxAvg = avgRx,
                resultTxAvg = avgTx,
                resultDurationSec = it.durationSec,
            )
        }
    }
}
