package com.netdiag.ui.screens

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.netdiag.core.net.DiscoveredHost
import com.netdiag.core.net.HostDiscovery
import com.netdiag.core.net.NetInfo
import com.netdiag.core.net.NetworkInfoProvider
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
)

class ScanViewModel(app: Application) : AndroidViewModel(app) {

    private val infoProvider = NetworkInfoProvider(app)
    private val mdns = MdnsDiscovery(app)

    private val _state = MutableStateFlow(ScanUiState(info = infoProvider.current()))
    val state: StateFlow<ScanUiState> = _state.asStateFlow()

    private var scanJob: Job? = null

    fun refreshInfo() {
        _state.update { it.copy(info = infoProvider.current()) }
    }

    fun startScan() {
        if (_state.value.scanning) return
        val info = infoProvider.current()
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

            HostDiscovery.scan(info).collect { event ->
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
            _state.update { s ->
                s.copy(
                    portScanning = s.portScanning - ip,
                    hosts = s.hosts.map { if (it.ip == ip) it.copy(openPorts = open) else it },
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
