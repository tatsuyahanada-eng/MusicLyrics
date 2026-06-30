package com.netdiag.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.netdiag.core.traffic.TrafficMonitor
import com.netdiag.ui.SectionCard

@Composable
fun TrafficScreen(vm: TrafficViewModel = viewModel()) {
    val s by vm.state.collectAsStateWithLifecycle()

    LazyColumn(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 12.dp),
    ) {
        item {
            SectionCard("リアルタイム通信量（端末全体）") {
                RateRow(
                    arrow = "▼",
                    title = "ダウンロード（受信）",
                    bytesPerSec = s.rxRate,
                    peak = s.peakRxRate,
                    color = MaterialTheme.colorScheme.primary,
                )
                Spacer(Modifier.height(14.dp))
                RateRow(
                    arrow = "▲",
                    title = "アップロード（送信）",
                    bytesPerSec = s.txRate,
                    peak = s.peakTxRate,
                    color = MaterialTheme.colorScheme.secondary,
                )
                Spacer(Modifier.height(14.dp))
                Text("計測時間", style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(4.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(10, 30, 60).forEach { sec ->
                        val selected = s.durationSec == sec
                        if (selected) {
                            Button(onClick = { vm.setDuration(sec) }, enabled = !s.monitoring) {
                                Text("${sec}秒")
                            }
                        } else {
                            OutlinedButton(onClick = { vm.setDuration(sec) }, enabled = !s.monitoring) {
                                Text("${sec}秒")
                            }
                        }
                    }
                }
                Spacer(Modifier.height(12.dp))
                Button(onClick = { vm.toggleMonitoring() }, modifier = Modifier.fillMaxWidth()) {
                    Text(
                        if (s.monitoring) "■ STOP（残り ${s.remainingSec}秒）"
                        else "▶ START（${s.durationSec}秒間 計測）"
                    )
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    "STARTすると指定時間で自動的に停止します。" +
                        "B/s=バイト毎秒、Mbps=回線速度（ビット毎秒）。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun RateRow(
    arrow: String,
    title: String,
    bytesPerSec: Long,
    peak: Long,
    color: androidx.compose.ui.graphics.Color,
) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(arrow, style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold, color = color)
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text("最大 ${TrafficMonitor.formatRate(peak)}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Column(horizontalAlignment = Alignment.End) {
            Text(TrafficMonitor.formatRate(bytesPerSec),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold, color = color)
            Text(TrafficMonitor.formatBitsRate(bytesPerSec),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
