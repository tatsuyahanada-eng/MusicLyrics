package com.netdiag.ui.screens

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.netdiag.core.net.DiscoveredHost
import com.netdiag.core.net.HostDiscovery
import com.netdiag.core.net.NetInfo
import com.netdiag.core.net.NetworkInfoProvider
import com.netdiag.core.net.HttpBanner
import com.netdiag.core.net.NetUtils
import com.netdiag.core.net.PingTool
import com.netdiag.core.net.PortScanner
import com.netdiag.core.net.ScanEvent
import com.netdiag.core.net.mdns.MdnsDiscovery
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class ScanUiState(
    val info: NetInfo = NetInfo(),
    val scanning: Boolean = false,
    val progress: Int = 0,
    val total: Int = 0,
    val hosts: List<DiscoveredHost> = emptyList(),
    val portScanning: Set<String> = emptySet(),
    // User-specified scan range
    val rangeMode: Boolean = false,
    val rangeStart: String = "",
    val rangeEnd: String = "",
)

class ScanViewModel(app: Application) : AndroidViewModel(app) {

    private val infoProvider = NetworkInfoProvider(app)
    private val mdns = MdnsDiscovery(app)

    private val _state = MutableStateFlow(ScanUiState(info = infoProvider.current()))
    val state: StateFlow<ScanUiState> = _state.asStateFlow()

    init {
        seedRangeDefaults(_state.value.info)
    }

    private var scanJob: Job? = null

    fun refreshInfo() {
        val info = infoProvider.current()
        _state.update { it.copy(info = info) }
        seedRangeDefaults(info)
    }

    fun setRangeMode(v: Boolean) = _state.update { it.copy(rangeMode = v) }
    fun setRangeStart(v: String) = _state.update { it.copy(rangeStart = v) }
    fun setRangeEnd(v: String) = _state.update { it.copy(rangeEnd = v) }

    /** Pre-fills the range boxes with the current subnet's first/last host. */
    private fun seedRangeDefaults(info: NetInfo) {
        val ip = info.ipv4 ?: return
        val prefix = info.prefixLength ?: return
        val hosts = NetUtils.hostAddresses(ip, prefix)
        if (hosts.isEmpty()) return
        _state.update {
            if (it.rangeStart.isBlank() || it.rangeEnd.isBlank()) {
                it.copy(rangeStart = hosts.first(), rangeEnd = hosts.last())
            } else it
        }
    }

    fun startScan() {
        if (_state.value.scanning) return
        val info = infoProvider.current()
        val s = _state.value
        _state.update {
            it.copy(info = info, scanning = true, hosts = emptyList(), progress = 0, total = 0)
        }
        scanJob = viewModelScope.launch {
            // Enrich names from mDNS in parallel with the ICMP/TCP sweep.
            val mdnsJob = launch {
                val hosts = runCatching { mdns.discover(5000) }.getOrDefault(emptyList())
                if (hosts.isEmpty()) return@launch
                val byIp = hosts.associate { it.ip to it.name }
                _state.update { s ->
                    s.copy(hosts = s.hosts.map { h ->
                        if (h.hostname == null && byIp.containsKey(h.ip)) {
                            h.copy(hostname = byIp[h.ip])
                        } else h
                    })
                }
            }

            val flow = if (s.rangeMode && s.rangeStart.isNotBlank() && s.rangeEnd.isNotBlank()) {
                HostDiscovery.scanRange(info, s.rangeStart, s.rangeEnd)
            } else {
                HostDiscovery.scan(info)
            }
            flow.collect { event ->
                when (event) {
                    is ScanEvent.Progress ->
                        _state.update { it.copy(progress = event.scanned, total = event.total) }
                    is ScanEvent.Found ->
                        _state.update { it.copy(hosts = mergeSorted(it.hosts, event.host)) }
                    is ScanEvent.Done ->
                        _state.update { it.copy(scanning = false) }
                }
            }
            mdnsJob.join()
        }
    }

    fun stopScan() {
        scanJob?.cancel()
        _state.update { it.copy(scanning = false) }
    }

    fun portScan(ip: String) {
        if (_state.value.portScanning.contains(ip)) return
        _state.update { it.copy(portScanning = it.portScanning + ip) }
        viewModelScope.launch {
            val open = runCatching { PortScanner.scan(ip) }.getOrDefault(emptyList())
            val ttl = runCatching { PingTool.probeTtl(ip) }.getOrNull()
            val webPort = open.firstOrNull { it in HttpBanner.WEB_PORTS }
            val http = if (webPort != null) {
                runCatching { HttpBanner.probe(ip, webPort) }.getOrNull()
            } else null
            _state.update { s ->
                s.copy(
                    portScanning = s.portScanning - ip,
                    hosts = s.hosts.map {
                        if (it.ip == ip) it.copy(
                            openPorts = open,
                            ttl = ttl ?: it.ttl,
                            httpInfo = http ?: it.httpInfo,
                        ) else it
                    },
                )
            }
        }
    }

    private fun mergeSorted(existing: List<DiscoveredHost>, host: DiscoveredHost): List<DiscoveredHost> {
        val list = existing + host
        return list.sortedWith(
            compareByDescending<DiscoveredHost> { it.isSelf }
                .thenByDescending { it.isGateway }
                .thenBy { ipKey(it.ip) }
        )
    }

    private fun ipKey(ip: String): Long = try {
        ip.split(".").fold(0L) { acc, part -> (acc shl 8) or part.toLong() }
    } catch (_: Exception) {
        Long.MAX_VALUE
    }
}
