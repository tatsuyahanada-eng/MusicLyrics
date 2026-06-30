package com.netdiag.ui.screens

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.netdiag.core.net.DnsServerResult
import com.netdiag.core.net.DnsTool
import com.netdiag.core.net.Hop
import com.netdiag.core.net.PingEvent
import com.netdiag.core.net.PingTool
import com.netdiag.core.net.SystemDnsResult
import com.netdiag.core.net.TraceEvent
import com.netdiag.core.net.Traceroute
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class DiagnoseUiState(
    // Ping
    val pingHost: String = "8.8.8.8",
    val pingCount: Int = 10,
    val pingSize: Int = 56,
    val pingRunning: Boolean = false,
    val pingReplies: List<PingEvent.Reply> = emptyList(),
    val pingSummary: PingEvent.Summary? = null,
    val pingError: String? = null,
    // Traceroute
    val traceHost: String = "8.8.8.8",
    val traceRunning: Boolean = false,
    val hops: List<Hop> = emptyList(),
    val traceError: String? = null,
    val traceReached: Boolean? = null,
    // DNS
    val dnsHost: String = "example.com",
    val dnsServer: String = "8.8.8.8",
    val dnsRunning: Boolean = false,
    val systemDns: SystemDnsResult? = null,
    val serverDns: DnsServerResult? = null,
)

class DiagnoseViewModel(app: Application) : AndroidViewModel(app) {

    private val _state = MutableStateFlow(DiagnoseUiState())
    val state: StateFlow<DiagnoseUiState> = _state.asStateFlow()

    private var pingJob: Job? = null
    private var traceJob: Job? = null

    fun setPingHost(v: String) = _state.update { it.copy(pingHost = v) }
    fun setTraceHost(v: String) = _state.update { it.copy(traceHost = v) }
    fun setDnsHost(v: String) = _state.update { it.copy(dnsHost = v) }
    fun setDnsServer(v: String) = _state.update { it.copy(dnsServer = v) }

    fun runPing() {
        if (_state.value.pingRunning) { pingJob?.cancel(); _state.update { it.copy(pingRunning = false) }; return }
        val s = _state.value
        _state.update {
            it.copy(pingRunning = true, pingReplies = emptyList(), pingSummary = null, pingError = null)
        }
        pingJob = viewModelScope.launch {
            PingTool.ping(s.pingHost.trim(), count = s.pingCount, packetSizeBytes = s.pingSize)
                .collect { event ->
                    when (event) {
                        is PingEvent.Reply -> _state.update { it.copy(pingReplies = it.pingReplies + event) }
                        is PingEvent.Summary -> _state.update { it.copy(pingSummary = event) }
                        is PingEvent.Error -> _state.update { it.copy(pingError = event.message) }
                        is PingEvent.Timeout -> {}
                    }
                }
            _state.update { it.copy(pingRunning = false) }
        }
    }

    fun runTrace() {
        if (_state.value.traceRunning) { traceJob?.cancel(); _state.update { it.copy(traceRunning = false) }; return }
        val host = _state.value.traceHost.trim()
        _state.update { it.copy(traceRunning = true, hops = emptyList(), traceError = null, traceReached = null) }
        traceJob = viewModelScope.launch {
            Traceroute.trace(host).collect { event ->
                when (event) {
                    is TraceEvent.HopFound -> _state.update { it.copy(hops = it.hops + event.hop) }
                    is TraceEvent.Done -> _state.update { it.copy(traceReached = event.reached) }
                    is TraceEvent.Error -> _state.update { it.copy(traceError = event.message) }
                }
            }
            _state.update { it.copy(traceRunning = false) }
        }
    }

    fun runDns() {
        if (_state.value.dnsRunning) return
        val s = _state.value
        _state.update { it.copy(dnsRunning = true, systemDns = null, serverDns = null) }
        viewModelScope.launch {
            val sys = DnsTool.resolveSystem(s.dnsHost.trim())
            _state.update { it.copy(systemDns = sys) }
            val server = DnsTool.queryServer(s.dnsServer.trim(), s.dnsHost.trim())
            _state.update { it.copy(serverDns = server, dnsRunning = false) }
        }
    }
}
