package com.netdiag.ui.screens

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.netdiag.core.traffic.SpeedEvent
import com.netdiag.core.traffic.SpeedTest
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class SpeedUiState(
    val running: Boolean = false,
    val phase: String = "",
    val progress: Float = 0f,
    val downMbps: Double = 0.0,
    val upMbps: Double = 0.0,
    val latencyMs: Long? = null,
    val done: Boolean = false,
    val error: String? = null,
    val durationSec: Int = 10,
)

class TrafficViewModel(app: Application) : AndroidViewModel(app) {

    private val _state = MutableStateFlow(SpeedUiState())
    val state: StateFlow<SpeedUiState> = _state.asStateFlow()

    private var job: Job? = null

    fun setDuration(sec: Int) {
        if (!_state.value.running) _state.update { it.copy(durationSec = sec) }
    }

    fun toggle() {
        if (_state.value.running) stop() else start()
    }

    fun start() {
        if (_state.value.running) return
        val duration = _state.value.durationSec
        _state.update {
            SpeedUiState(running = true, phase = "準備中…", durationSec = duration)
        }
        job = viewModelScope.launch {
            SpeedTest.run(duration * 1000L).collect { ev ->
                when (ev) {
                    is SpeedEvent.Latency ->
                        _state.update { it.copy(latencyMs = ev.ms, phase = "測定準備…") }
                    is SpeedEvent.Download ->
                        _state.update {
                            it.copy(phase = "下り（ダウンロード）測定中", downMbps = ev.mbps, progress = ev.progress)
                        }
                    is SpeedEvent.Upload ->
                        _state.update {
                            it.copy(phase = "上り（アップロード）測定中", upMbps = ev.mbps, progress = ev.progress)
                        }
                    is SpeedEvent.Done ->
                        _state.update {
                            it.copy(
                                running = false, done = true, phase = "完了",
                                downMbps = ev.downMbps, upMbps = ev.upMbps,
                                latencyMs = ev.latencyMs, progress = 1f,
                            )
                        }
                    is SpeedEvent.Error ->
                        _state.update { it.copy(running = false, phase = "エラー", error = ev.message) }
                }
            }
            _state.update { it.copy(running = false) }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
        _state.update { it.copy(running = false, phase = "中止しました") }
    }
}
