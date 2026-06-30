package com.netdiag.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.netdiag.core.net.Hop
import com.netdiag.ui.LabeledValue
import com.netdiag.ui.MonoSmall
import com.netdiag.ui.OctetIpField
import com.netdiag.ui.SectionCard

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun DiagnoseScreen(vm: DiagnoseViewModel = viewModel()) {
    val s by vm.state.collectAsStateWithLifecycle()

    LazyColumn(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 12.dp),
    ) {
        // ---- Ping ----
        item {
            SectionCard("高度なPing（ロス率・ジッター）") {
                OctetIpField("宛先 IP", s.pingHost, vm::setPingHost)
                Spacer(Modifier.height(8.dp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    NumField("回数", s.pingCount, vm::setPingCount)
                    NumField("サイズB", s.pingSize, vm::setPingSize)
                    NumField("間隔ms", s.pingIntervalMs, vm::setPingInterval)
                    NumField("待機s", s.pingTimeoutSec, vm::setPingTimeout)
                }
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = vm::runPing) {
                        Text(if (s.pingRunning) "停止" else "Ping 実行")
                    }
                    OutlinedButton(onClick = vm::resetPing) { Text("RESET") }
                }
                s.pingError?.let {
                    Spacer(Modifier.height(8.dp))
                    Text(it, color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodyMedium)
                }
                s.pingSummary?.let { sum ->
                    Spacer(Modifier.height(12.dp))
                    LabeledValue("送信 / 受信", "${sum.transmitted} / ${sum.received}")
                    LabeledValue("パケットロス", "%.0f%%".format(sum.lossPercent))
                    LabeledValue("RTT 最小/平均/最大",
                        "${fmt(sum.minMs)} / ${fmt(sum.avgMs)} / ${fmt(sum.maxMs)} ms")
                    LabeledValue("ジッター", "${fmt(sum.jitterMs)} ms")
                }
                if (s.pingReplies.isNotEmpty()) {
                    Spacer(Modifier.height(10.dp))
                    s.pingReplies.takeLast(12).forEach { r ->
                        MonoSmall("seq=${r.seq}  ttl=${r.ttl ?: "-"}  time=${"%.1f".format(r.rttMs)} ms")
                    }
                }
            }
        }

        // ---- Traceroute ----
        item {
            SectionCard("Traceroute（経路追跡）") {
                OctetIpField("宛先 IP", s.traceHost, vm::setTraceHost)
                Spacer(Modifier.height(8.dp))
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.Center,
                ) {
                    NumField("最大ホップ", s.traceMaxHops, vm::setTraceMaxHops)
                    NumField("待機s", s.traceTimeoutSec, vm::setTraceTimeout)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Switch(checked = s.traceResolve, onCheckedChange = vm::setTraceResolve)
                        Spacer(Modifier.width(4.dp))
                        Text("名前解決", style = MaterialTheme.typography.bodySmall)
                    }
                }
                Spacer(Modifier.height(8.dp))
                Button(onClick = vm::runTrace) {
                    Text(if (s.traceRunning) "停止" else "経路を追跡")
                }
                s.traceError?.let {
                    Spacer(Modifier.height(8.dp))
                    Text(it, color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodyMedium)
                }
                if (s.hops.isNotEmpty()) {
                    Spacer(Modifier.height(10.dp))
                    s.hops.forEach { HopRow(it) }
                }
                s.traceReached?.let {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        if (it) "✓ 宛先に到達しました" else "✗ 最大ホップ数まで到達できませんでした",
                        color = if (it) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
        }

        // ---- DNS ----
        item {
            SectionCard("DNS 疎通・解決チェック") {
                OutlinedTextField(
                    value = s.dnsHost,
                    onValueChange = vm::setDnsHost,
                    label = { Text("解決するドメイン") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                OctetIpField("比較用DNSサーバー IP", s.dnsServer, vm::setDnsServer)
                Spacer(Modifier.height(8.dp))
                Button(onClick = vm::runDns, enabled = !s.dnsRunning) { Text("DNS テスト") }

                s.systemDns?.let { r ->
                    Spacer(Modifier.height(12.dp))
                    Text("システムDNS", style = MaterialTheme.typography.labelLarge)
                    if (r.success) {
                        LabeledValue("結果", r.addresses.joinToString(", "), monospace = true)
                        LabeledValue("応答時間", "${r.elapsedMs} ms")
                    } else {
                        Text("解決失敗: ${r.error ?: "不明"}",
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodyMedium)
                    }
                }
                s.serverDns?.let { r ->
                    Spacer(Modifier.height(10.dp))
                    Text("指定サーバー (${r.server})", style = MaterialTheme.typography.labelLarge)
                    if (r.success) {
                        LabeledValue("結果", r.addresses.joinToString(", "), monospace = true)
                        LabeledValue("応答時間", "${r.elapsedMs} ms")
                    } else {
                        Text("応答なし: ${r.error ?: "不明"}",
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        }
    }
}

@Composable
private fun HopRow(hop: Hop) {
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Text("%2d".format(hop.ttl), fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(
                hop.ip ?: "* * *  (応答なし)",
                fontFamily = FontFamily.Monospace,
                style = MaterialTheme.typography.bodyMedium,
            )
            hop.hostname?.let {
                Text(it, style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Text(hop.rttMs?.let { "%.1f ms".format(it) } ?: "-",
            fontFamily = FontFamily.Monospace,
            style = MaterialTheme.typography.bodySmall)
    }
}

private fun fmt(v: Double?): String = v?.let { "%.1f".format(it) } ?: "-"

@Composable
private fun NumField(label: String, value: Int, onChange: (Int) -> Unit) {
    OutlinedTextField(
        value = value.toString(),
        onValueChange = { text -> text.filter { it.isDigit() }.toIntOrNull()?.let(onChange) },
        label = { Text(label, style = MaterialTheme.typography.labelSmall) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        modifier = Modifier.width(96.dp),
    )
}
