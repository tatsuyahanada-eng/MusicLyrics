package com.netdiag.ui.screens

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.netdiag.core.DiagnosticsLog
import com.netdiag.core.monitor.Alert
import com.netdiag.core.monitor.AlertSeverity
import com.netdiag.ui.LabeledValue
import com.netdiag.ui.MonoSmall
import com.netdiag.ui.NameColor
import com.netdiag.ui.SectionCard
import com.netdiag.ui.Tag

@Composable
fun MonitorScreen(vm: MonitorViewModel = viewModel()) {
    val status by vm.status.collectAsStateWithLifecycle()
    val alerts by vm.alerts.collectAsStateWithLifecycle()
    val context = LocalContext.current

    val notifyPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { vm.start() }

    val startMonitoring = {
        val needsPermission = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(
                context, Manifest.permission.POST_NOTIFICATIONS
            ) != PackageManager.PERMISSION_GRANTED
        if (needsPermission) {
            notifyPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            vm.start()
        }
    }

    LazyColumn(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 12.dp),
    ) {
        item {
            SectionCard("ネットワーク監視") {
                Text(
                    "LANの設定変化（不正DHCP・ゲートウェイ/DNS書き換え）と、" +
                        "mDNS/SSDPの異常（新しい機器・ブロードキャストストーム・不正な形式の" +
                        "パケット）を常時監視し、異常があれば通知します。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(10.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Tag(
                        if (status.running) "監視中" else "停止中",
                        if (status.running) Color(0xFF2E9E54) else Color(0xFF808080),
                    )
                    Spacer(Modifier.width(10.dp))
                    Text(
                        "受信 ${status.packetsSeen} パケット / 既知 ${status.knownDevices} 機器",
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { if (status.running) vm.stop() else startMonitoring() }) {
                        Text(if (status.running) "■ 監視を停止" else "▶ 監視を開始")
                    }
                    OutlinedButton(onClick = { vm.rebaseline() }) { Text("基準値を再取得") }
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    "※ 他の機器の通信内容は一切見ません。全員宛に送られる" +
                        "マルチキャストと、自端末のネットワーク設定のみを確認します。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        status.current?.let { current ->
            item {
                SectionCard("現在の構成と基準値") {
                    LabeledValue("ネットワーク", current.networkKey, monospace = true)
                    LabeledValue("ゲートウェイ", current.gateway ?: "-", monospace = true)
                    LabeledValue("DHCPサーバー", current.dhcpServer ?: "-", monospace = true)
                    LabeledValue(
                        "DNS",
                        current.dnsServers.joinToString(", ").ifBlank { "-" },
                        monospace = true,
                    )
                    status.baseline?.let { base ->
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "基準値: GW=${base.gateway ?: "-"} / DHCP=${base.dhcpServer ?: "-"} / " +
                                "DNS=${base.dnsServers.joinToString(",").ifBlank { "-" }}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }

        item {
            SectionCard("検知したアラート (${alerts.size})") {
                if (alerts.isEmpty()) {
                    Text(
                        "まだ異常は検知されていません。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { vm.clearAlerts() }) { Text("クリア") }
                    }
                }
            }
        }

        items(alerts.asReversed().take(100), key = { "${it.key}-${it.timeMillis}" }) { alert ->
            AlertCard(alert)
        }

        if (status.recentPackets.isNotEmpty()) {
            item {
                SectionCard("最近のマルチキャスト") {
                    status.recentPackets.asReversed().take(20).forEach { p ->
                        MonoSmall("${p.sourceIp}  ${p.protocol}  ${p.sizeBytes}B  ${p.summary}")
                    }
                }
            }
        }
    }
}

@Composable
private fun AlertCard(alert: Alert) {
    val color = severityColor(alert.severity)
    SectionCard(alert.title) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Tag(severityLabel(alert.severity), color)
            Spacer(Modifier.width(8.dp))
            Tag(alert.category.label, MaterialTheme.colorScheme.secondary)
            Spacer(Modifier.width(8.dp))
            Text(
                DiagnosticsLog.formatTime(alert.timeMillis),
                style = MaterialTheme.typography.labelSmall,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.height(8.dp))
        Text(
            alert.detail,
            style = MaterialTheme.typography.bodySmall,
            color = NameColor,
        )
    }
}

private fun severityColor(severity: AlertSeverity): Color = when (severity) {
    AlertSeverity.CRITICAL -> Color(0xFFD05050)
    AlertSeverity.WARN -> Color(0xFFE0A422)
    AlertSeverity.INFO -> Color(0xFF3C8DBC)
}

private fun severityLabel(severity: AlertSeverity): String = when (severity) {
    AlertSeverity.CRITICAL -> "重大"
    AlertSeverity.WARN -> "警告"
    AlertSeverity.INFO -> "情報"
}
