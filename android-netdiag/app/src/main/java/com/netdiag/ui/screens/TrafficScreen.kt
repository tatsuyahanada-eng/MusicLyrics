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
                Spacer(Modifier.height(16.dp))
                Button(onClick = { vm.toggleMonitoring() }, modifier = Modifier.fillMaxWidth()) {
                    Text(if (s.monitoring) "■ STOP（計測停止）" else "▶ START（計測開始）")
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    "B/s = 1秒あたりのバイト数、Mbps = 回線速度（ビット毎秒）。" +
                        "異常な大量通信（暴走DL・ブロードキャスト等）の早期発見に。",
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
