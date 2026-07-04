package com.netdiag.ui.screens

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.netdiag.core.DiagnosticsLog
import com.netdiag.core.net.DnsServerResult
import com.netdiag.core.net.DnsTool
import com.netdiag.core.net.Hop
import com.netdiag.core.net.PingEvent
import com.netdiag.core.net.PingTool
import com.netdiag.core.net.SystemDnsResult
import com.netdiag.core.net.TraceEvent
import com.netdiag.core.net.Traceroute
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class DiagnoseUiState(
    // Ping
    val pingHost: String = "",
    val pingCount: Int = 10,
    val pingSize: Int = 56,
    val pingIntervalMs: Int = 1000,
    val pingTimeoutSec: Int = 2,
    val pingRunning: Boolean = false,
    val pingReplies: List<PingEvent.Reply> = emptyList(),
    val pingSummary: PingEvent.Summary? = null,
    val pingError: String? = null,
    // Traceroute
    val traceHost: String = "8.8.8.8",
    val traceMaxHops: Int = 30,
    val traceTimeoutSec: Int = 2,
    val traceResolve: Boolean = true,
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
    // External reachability (internet) test
    val extRunning: Boolean = false,
    val extResults: List<ExtResult> = DiagnoseViewModel.EXTERNAL_TARGETS.map {
        ExtResult(it.first, it.second)
    },
    val wanIp: String? = null,
    val wanLoading: Boolean = false,
)

/** Result of an internet-reachability ping to a well-known host. */
data class ExtResult(
    val label: String,
    val host: String,
    val running: Boolean = false,
    val reachable: Boolean? = null,
    val lossPercent: Double? = null,
    val avgMs: Double? = null,
)

class DiagnoseViewModel(app: Application) : AndroidViewModel(app) {

    private val _state = MutableStateFlow(DiagnoseUiState())
    val state: StateFlow<DiagnoseUiState> = _state.asStateFlow()

    init {
        DiagnosticsLog.init(app)
    }

    private var pingJob: Job? = null
    private var traceJob: Job? = null

    fun setPingHost(v: String) = _state.update { it.copy(pingHost = v) }
    fun setPingCount(v: Int) = _state.update { it.copy(pingCount = v.coerceIn(1, 1000)) }
    fun setPingSize(v: Int) = _state.update { it.copy(pingSize = v.coerceIn(1, 65500)) }
    fun setPingInterval(v: Int) = _state.update { it.copy(pingIntervalMs = v.coerceIn(200, 10000)) }
    fun setPingTimeout(v: Int) = _state.update { it.copy(pingTimeoutSec = v.coerceIn(1, 30)) }
    fun setTraceHost(v: String) = _state.update { it.copy(traceHost = v) }
    fun setTraceMaxHops(v: Int) = _state.update { it.copy(traceMaxHops = v.coerceIn(1, 64)) }
    fun setTraceTimeout(v: Int) = _state.update { it.copy(traceTimeoutSec = v.coerceIn(1, 30)) }
    fun setTraceResolve(v: Boolean) = _state.update { it.copy(traceResolve = v) }
    fun setDnsHost(v: String) = _state.update { it.copy(dnsHost = v) }
    fun setDnsServer(v: String) = _state.update { it.copy(dnsServer = v) }

    fun runPing() {
        if (_state.value.pingRunning) { pingJob?.cancel(); _state.update { it.copy(pingRunning = false) }; return }
        val s = _state.value
        _state.update {
            it.copy(pingRunning = true, pingReplies = emptyList(), pingSummary = null, pingError = null)
        }
        pingJob = viewModelScope.launch {
            PingTool.ping(
                s.pingHost.trim(),
                count = s.pingCount,
                packetSizeBytes = s.pingSize,
                intervalMs = s.pingIntervalMs,
                perPacketTimeoutSec = s.pingTimeoutSec,
            )
                .collect { event ->
                    when (event) {
                        is PingEvent.Reply -> _state.update { it.copy(pingReplies = it.pingReplies + event) }
                        is PingEvent.Summary -> {
                            _state.update { it.copy(pingSummary = event) }
                            DiagnosticsLog.add(
                                "PING ${s.pingHost.trim()} 送信${event.transmitted}/受信${event.received} " +
                                    "loss ${"%.0f".format(event.lossPercent)}% " +
                                    "avg ${event.avgMs?.let { v -> "%.1f".format(v) } ?: "-"}ms " +
                                    "jitter ${event.jitterMs?.let { v -> "%.1f".format(v) } ?: "-"}ms"
                            )
                        }
                        is PingEvent.Error -> {
                            _state.update { it.copy(pingError = event.message) }
                            DiagnosticsLog.add("PING ${s.pingHost.trim()} エラー: ${event.message}")
                        }
                        is PingEvent.Timeout -> {}
                    }
                }
            _state.update { it.copy(pingRunning = false) }
        }
    }

    /** Clears the ping results AND the target IP so a fresh run starts blank. */
    fun resetPing() {
        pingJob?.cancel()
        _state.update {
            it.copy(
                pingHost = "",
                pingRunning = false,
                pingReplies = emptyList(),
                pingSummary = null,
                pingError = null,
            )
        }
    }

    fun runTrace() {
        if (_state.value.traceRunning) { traceJob?.cancel(); _state.update { it.copy(traceRunning = false) }; return }
        val host = _state.value.traceHost.trim()
        _state.update { it.copy(traceRunning = true, hops = emptyList(), traceError = null, traceReached = null) }
        val s = _state.value
        traceJob = viewModelScope.launch {
            Traceroute.trace(
                host,
                maxHops = s.traceMaxHops,
                perHopTimeoutSec = s.traceTimeoutSec,
                resolveNames = s.traceResolve,
            ).collect { event ->
                when (event) {
                    is TraceEvent.HopFound -> _state.update { it.copy(hops = it.hops + event.hop) }
                    is TraceEvent.Done -> {
                        _state.update { it.copy(traceReached = event.reached) }
                        DiagnosticsLog.add(
                            "TRACE $host ${if (event.reached) "到達" else "未到達"} hops=${event.hops}"
                        )
                    }
                    is TraceEvent.Error -> {
                        _state.update { it.copy(traceError = event.message) }
                        DiagnosticsLog.add("TRACE $host エラー: ${event.message}")
                    }
                }
            }
            _state.update { it.copy(traceRunning = false) }
        }
    }

    /**
     * Pings well-known external hosts (Google / Yahoo) and fetches the public
     * (WAN) IP, to confirm the device really reaches the internet — not just
     * the LAN.
     */
    fun runExternalTests() {
        if (_state.value.extRunning) return
        _state.update {
            it.copy(
                extRunning = true,
                wanLoading = true,
                wanIp = null,
                extResults = EXTERNAL_TARGETS.map { t -> ExtResult(t.first, t.second, running = true) },
            )
        }
        viewModelScope.launch {
            val wanJob = launch {
                val ip = fetchPublicIp()
                _state.update { it.copy(wanIp = ip, wanLoading = false) }
            }
            val pingJobs = EXTERNAL_TARGETS.mapIndexed { index, target ->
                launch {
                    var summary: PingEvent.Summary? = null
                    var error = false
                    PingTool.ping(target.second, count = 4, perPacketTimeoutSec = 2)
                        .collect { ev ->
                            when (ev) {
                                is PingEvent.Summary -> summary = ev
                                is PingEvent.Error -> error = true
                                else -> {}
                            }
                        }
                    val reachable = !error && (summary?.received ?: 0) > 0
                    _state.update { s ->
                        s.copy(extResults = s.extResults.toMutableList().also { list ->
                            list[index] = list[index].copy(
                                running = false,
                                reachable = reachable,
                                lossPercent = summary?.lossPercent,
                                avgMs = summary?.avgMs,
                            )
                        })
                    }
                }
            }
            pingJobs.forEach { it.join() }
            wanJob.join()
            _state.update { it.copy(extRunning = false) }
            val st = _state.value
            val res = st.extResults.joinToString(" ") {
                "${it.label}:${if (it.reachable == true) "OK" else "NG"}"
            }
            DiagnosticsLog.add("外部疎通 $res WAN=${st.wanIp ?: "-"}")
        }
    }

    private suspend fun fetchPublicIp(): String? = withContext(Dispatchers.IO) {
        try {
            val conn = (java.net.URL("https://api.ipify.org").openConnection() as java.net.HttpURLConnection)
            conn.connectTimeout = 5000
            conn.readTimeout = 5000
            conn.requestMethod = "GET"
            conn.inputStream.bufferedReader().use { it.readText().trim() }.takeIf { it.isNotBlank() }
        } catch (_: Exception) {
            null
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
            DiagnosticsLog.add(
                "DNS ${s.dnsHost.trim()} sys=${sys.addresses.joinToString("/").ifBlank { "解決失敗" }} " +
                    "@${s.dnsServer.trim()}=${server.addresses.joinToString("/").ifBlank { "応答なし" }}"
            )
        }
    }

    companion object {
        /** label -> host. Mix of an IP (DNS) and names (DNS resolution + reach). */
        val EXTERNAL_TARGETS: List<Pair<String, String>> = listOf(
            "Google DNS" to "8.8.8.8",
            "Google" to "www.google.com",
            "Yahoo! JAPAN" to "www.yahoo.co.jp",
        )
    }
}
