package com.netdiag.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Router
import androidx.compose.material.icons.filled.Smartphone
import androidx.compose.material.icons.filled.DevicesOther
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.netdiag.core.net.DiscoveredHost
import com.netdiag.core.net.PortScanner
import com.netdiag.ui.LabeledValue
import com.netdiag.ui.SectionCard
import com.netdiag.ui.Tag

@Composable
fun ScanScreen(vm: ScanViewModel = viewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()
    val info = state.info

    LazyColumn(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 12.dp),
    ) {
        item {
            SectionCard(title = "このネットワーク") {
                if (info.hasIpv4) {
                    LabeledValue("自分のIP", info.ipv4 ?: "-", monospace = true)
                    LabeledValue("サブネット", "/${info.prefixLength}  (${info.subnetMask})", monospace = true)
                    LabeledValue("ゲートウェイ", info.gateway ?: "-", monospace = true)
                    LabeledValue("DNS", info.dnsServers.joinToString(", ").ifBlank { "-" }, monospace = true)
                    LabeledValue("インターフェース", info.interfaceName ?: "-")
                } else {
                    Text(
                        "アクティブなIPv4ネットワークが見つかりません。Wi-Fiに接続してください。",
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                Spacer(Modifier.height(12.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Button(
                        onClick = { if (state.scanning) vm.stopScan() else vm.startScan() },
                        enabled = info.hasIpv4,
                    ) {
                        Text(if (state.scanning) "停止" else "スキャン開始")
                    }
                    Spacer(Modifier.width(12.dp))
                    OutlinedButton(onClick = { vm.refreshInfo() }) { Text("情報更新") }
                }
                if (state.scanning || state.total > 0) {
                    Spacer(Modifier.height(12.dp))
                    val frac = if (state.total > 0) state.progress.toFloat() / state.total else 0f
                    LinearProgressIndicator(
                        progress = { frac },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "${state.progress} / ${state.total} 件確認 ・ ${state.hosts.size} 台検出",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        if (state.hosts.isNotEmpty()) {
            item {
                Text(
                    "検出された機器 (${state.hosts.size})",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(start = 4.dp, top = 4.dp),
                )
            }
        }

        items(state.hosts, key = { it.ip }) { host ->
            HostCard(
                host = host,
                scanning = state.portScanning.contains(host.ip),
                onPortScan = { vm.portScan(host.ip) },
            )
        }

        if (!state.scanning && state.hosts.isEmpty() && state.total > 0) {
            item {
                Text(
                    "応答した機器はありませんでした。ファイアウォールで応答が抑止されている可能性があります。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(8.dp),
                )
            }
        }
    }
}

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun HostCard(host: DiscoveredHost, scanning: Boolean, onPortScan: () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                val icon = when {
                    host.isGateway -> Icons.Filled.Router
                    host.isSelf -> Icons.Filled.Smartphone
                    else -> Icons.Filled.DevicesOther
                }
                Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        host.ip,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.SemiBold,
                        style = MaterialTheme.typography.bodyLarge,
                    )
                    host.hostname?.let {
                        Text(it, style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                when {
                    host.isGateway -> Tag("ゲートウェイ", MaterialTheme.colorScheme.primary)
                    host.isSelf -> Tag("この端末", MaterialTheme.colorScheme.secondary)
                    else -> {}
                }
            }
            host.mac?.let {
                Spacer(Modifier.height(6.dp))
                LabeledValue("MAC", it, monospace = true)
            }
            if (host.openPorts.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    host.openPorts.forEach { port ->
                        Tag("$port ${PortScanner.serviceName(port)}", MaterialTheme.colorScheme.tertiary)
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
            if (scanning) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.height(18.dp).width(18.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(8.dp))
                    Text("ポート調査中…", style = MaterialTheme.typography.bodySmall)
                }
            } else {
                OutlinedButton(onClick = onPortScan) {
                    Text(if (host.openPorts.isEmpty()) "ポート調査" else "再調査")
                }
            }
        }
    }
}
