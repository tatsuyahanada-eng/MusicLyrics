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
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Computer
import androidx.compose.material.icons.filled.DevicesOther
import androidx.compose.material.icons.filled.Lightbulb
import androidx.compose.material.icons.filled.Print
import androidx.compose.material.icons.filled.Router
import androidx.compose.material.icons.filled.Smartphone
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.Tv
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import android.content.Intent
import android.net.Uri
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.netdiag.core.net.CableTestResult
import com.netdiag.core.net.CableVerdict
import com.netdiag.core.net.DeviceClassifier
import com.netdiag.core.net.DeviceKind
import com.netdiag.core.net.DiscoveredHost
import com.netdiag.core.net.EthernetState
import com.netdiag.core.net.OsGuesser
import com.netdiag.core.net.PortScanner
import com.netdiag.ui.DangerColor
import com.netdiag.ui.LabeledValue
import com.netdiag.ui.NameColor
import com.netdiag.ui.NetScopeHeader
import com.netdiag.ui.OctetIpField
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
        item { NetScopeHeader() }

        if (state.ethernet.connected) {
            item {
                EthernetCard(
                    eth = state.ethernet,
                    bound = state.ethernetBound,
                    onBoundChange = vm::setEthernetBound,
                    testRunning = state.cableTestRunning,
                    testResult = state.cableTestResult,
                    onRunTest = vm::runCableTest,
                )
            }
        }

        item {
            SectionCard(title = "このネットワーク") {
                if (info.hasIpv4) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("接続方式", style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.width(8.dp))
                        Tag(info.transport.label, MaterialTheme.colorScheme.secondary)
                    }
                    Spacer(Modifier.height(8.dp))
                    LabeledValue("自分のIP", info.ipv4 ?: "-", monospace = true)
                    LabeledValue("サブネット", "/${info.prefixLength}  (${info.subnetMask})", monospace = true)
                    LabeledValue("ゲートウェイ", info.gateway ?: "-", monospace = true)
                    LabeledValue("DNS", info.dnsServers.joinToString(", ").ifBlank { "-" }, monospace = true)
                } else {
                    Text(
                        "アクティブなIPv4ネットワークが見つかりません。Wi-Fiに接続するか、" +
                            "LANケーブル/イーサネットアダプタを接続してください。",
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                Spacer(Modifier.height(12.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("範囲を指定", style = MaterialTheme.typography.bodyMedium)
                    Spacer(Modifier.width(8.dp))
                    Switch(checked = state.rangeMode, onCheckedChange = vm::setRangeMode)
                }
                if (state.rangeMode) {
                    Spacer(Modifier.height(8.dp))
                    OctetIpField("開始IP", state.rangeStart, vm::setRangeStart)
                    Spacer(Modifier.height(8.dp))
                    OctetIpField("終了IP", state.rangeEnd, vm::setRangeEnd)
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
    val context = LocalContext.current
    val type = remember(host) { DeviceClassifier.classify(host) }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(iconFor(type.kind), contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        host.ip,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                        color = NameColor,
                        style = MaterialTheme.typography.bodyLarge,
                    )
                    host.hostname?.let {
                        Text(it, color = NameColor, style = MaterialTheme.typography.bodySmall)
                    }
                }
                Tag(type.label, MaterialTheme.colorScheme.primary)
            }
            Spacer(Modifier.height(4.dp))
            OsGuesser.guess(host)?.let {
                LabeledValue("OS(推定)", it)
            }
            host.latencyMs?.let { LabeledValue("応答時間", "${it} ms") }
            host.vendor?.let {
                LabeledValue("メーカー", it)
            }
            host.httpInfo?.let { LabeledValue("Web機器", it) }
            LabeledValue("MAC", host.mac ?: "取得不可", monospace = true)
            host.ttl?.let { LabeledValue("TTL", it.toString(), monospace = true) }
            if (host.openPorts.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    host.openPorts.forEach { port ->
                        Tag("$port ${PortScanner.serviceName(port)}", MaterialTheme.colorScheme.tertiary)
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically) {
                if (scanning) {
                    CircularProgressIndicator(Modifier.height(18.dp).width(18.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(8.dp))
                    Text("ポート調査中…", style = MaterialTheme.typography.bodySmall)
                } else {
                    OutlinedButton(onClick = onPortScan) {
                        Text(if (host.openPorts.size <= 1) "詳細取得" else "再取得")
                    }
                }
                if (host.isGateway) {
                    OutlinedButton(onClick = {
                        runCatching {
                            context.startActivity(
                                Intent(Intent.ACTION_VIEW, Uri.parse("http://${host.ip}"))
                            )
                        }
                    }) { Text("管理画面") }
                }
            }
        }
    }
}

/**
 * Shown when a USB-C/Ethernet adapter is plugged in, independently of whether
 * Android treats it as the system's default network (it may not, especially
 * when the wired LAN has no internet gateway). Lets the user see the wired
 * link's DHCP result, pin this app's own traffic to it with one switch, and
 * run a one-tap "is this cable/port good" check — a lightweight stand-in for
 * a dedicated LAN tester (it can't check physical wiring, see the test's own
 * explanation below).
 */
@Composable
private fun EthernetCard(
    eth: EthernetState,
    bound: Boolean,
    onBoundChange: (Boolean) -> Unit,
    testRunning: Boolean,
    testResult: CableTestResult?,
    onRunTest: () -> Unit,
) {
    SectionCard(title = "有線LAN（イーサネットアダプタ）") {
        val info = eth.info
        if (info?.hasIpv4 == true) {
            LabeledValue("IPアドレス", info.ipv4 ?: "-", monospace = true)
            LabeledValue("サブネット", "/${info.prefixLength}  (${info.subnetMask})", monospace = true)
            LabeledValue("ゲートウェイ", info.gateway ?: "-", monospace = true)
            LabeledValue("DNS", info.dnsServers.joinToString(", ").ifBlank { "-" }, monospace = true)
        } else {
            Text(
                "アダプタは接続されていますが、IPアドレスを取得できていません。" +
                    "LANケーブルが挿さっているか、DHCPサーバー（ルーター）が動作しているか確認してください。",
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        Spacer(Modifier.height(12.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Switch(checked = bound, onCheckedChange = onBoundChange)
            Spacer(Modifier.width(8.dp))
            Text("このアプリの通信を有線LANに固定", style = MaterialTheme.typography.bodyMedium)
        }
        Spacer(Modifier.height(6.dp))
        Text(
            "オンの間、スキャン・速度測定などこのアプリの通信はすべて有線LAN経由になります。" +
                "端末本体のWi-Fi／モバイル回線や他のアプリの通信には影響しません。",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Spacer(Modifier.height(14.dp))
        HorizontalDividerThin()
        Spacer(Modifier.height(10.dp))

        Text("ケーブル診断（簡易LANテスター）", style = MaterialTheme.typography.labelLarge)
        Spacer(Modifier.height(4.dp))
        Text(
            "リンク速度・IP取得・ゲートウェイ応答をまとめて確認します。" +
                "配線の断線／結線チェックは専用機器が必要なため対応できません。",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(8.dp))
        Button(onClick = onRunTest, enabled = !testRunning) {
            Text(if (testRunning) "診断中…" else "ケーブル診断を実行")
        }

        testResult?.let { r ->
            Spacer(Modifier.height(12.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Tag(verdictLabel(r.verdict), verdictColor(r.verdict))
                Spacer(Modifier.width(8.dp))
                Text(r.summary, style = MaterialTheme.typography.bodySmall, color = NameColor)
            }
            Spacer(Modifier.height(10.dp))
            LabeledValue(
                "リンク速度（推定）",
                r.downstreamMbps?.let { down ->
                    "${down}Mbps" + (r.upstreamMbps?.let { " / ↑${it}Mbps" } ?: "")
                } ?: "取得不可",
            )
            LabeledValue("DHCP取得", if (r.ipObtained) "成功（${r.ip}）" else "失敗")
            LabeledValue(
                "ゲートウェイ応答",
                r.gatewayCheck?.let {
                    if (it.reachable) "${it.note}（${it.rttMs ?: "-"} ms）" else it.note
                } ?: "未確認",
            )
        }
    }
}

@Composable
private fun HorizontalDividerThin() {
    HorizontalDivider(
        thickness = 1.dp,
        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.2f),
    )
}

private fun verdictLabel(v: CableVerdict): String = when (v) {
    CableVerdict.PASS -> "正常"
    CableVerdict.WARN -> "要確認"
    CableVerdict.FAIL -> "異常"
}

private fun verdictColor(v: CableVerdict): Color = when (v) {
    CableVerdict.PASS -> Color(0xFF2E9E54)
    CableVerdict.WARN -> Color(0xFFE0A422)
    CableVerdict.FAIL -> DangerColor
}

private fun iconFor(kind: DeviceKind): ImageVector = when (kind) {
    DeviceKind.SELF, DeviceKind.PHONE -> Icons.Filled.Smartphone
    DeviceKind.ROUTER -> Icons.Filled.Router
    DeviceKind.PRINTER -> Icons.Filled.Print
    DeviceKind.NAS -> Icons.Filled.Storage
    DeviceKind.PC -> Icons.Filled.Computer
    DeviceKind.TV -> Icons.Filled.Tv
    DeviceKind.CAMERA -> Icons.Filled.Videocam
    DeviceKind.IOT -> Icons.Filled.Lightbulb
    DeviceKind.UNKNOWN -> Icons.Filled.DevicesOther
}
