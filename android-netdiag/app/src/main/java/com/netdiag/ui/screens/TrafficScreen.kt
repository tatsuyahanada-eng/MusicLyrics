package com.netdiag.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
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
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                    RateColumn("↓ ダウンロード", s.rxRate, MaterialTheme.colorScheme.primary)
                    RateColumn("↑ アップロード", s.txRate, MaterialTheme.colorScheme.secondary)
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    "最大 ↓ ${TrafficMonitor.formatRate(s.peakRxRate)} / ↑ ${TrafficMonitor.formatRate(s.peakTxRate)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(12.dp))
                Button(onClick = { vm.toggleMonitoring() }) {
                    Text(if (s.monitoring) "STOP" else "START")
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    "STARTで計測開始。異常な大量通信（暴走ダウンロード・ブロードキャスト等）の早期発見に。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun RateColumn(label: String, bytesPerSec: Long, color: androidx.compose.ui.graphics.Color) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(label, style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(4.dp))
        Text(TrafficMonitor.formatRate(bytesPerSec),
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold, color = color)
    }
}
