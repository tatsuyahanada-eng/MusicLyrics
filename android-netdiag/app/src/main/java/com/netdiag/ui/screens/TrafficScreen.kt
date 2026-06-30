package com.netdiag.ui.screens

import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.netdiag.core.traffic.AppTraffic
import com.netdiag.core.traffic.TrafficMonitor
import com.netdiag.ui.MeterBar
import com.netdiag.ui.SectionCard

@Composable
fun TrafficScreen(vm: TrafficViewModel = viewModel()) {
    val s by vm.state.collectAsStateWithLifecycle()
    val context = LocalContext.current

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
                    "異常な大量通信（暴走ダウンロード・ブロードキャスト等）の早期発見に。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        item {
            SectionCard("アプリ別 Wi-Fi 使用量（過去24時間）") {
                if (!s.hasUsageAccess) {
                    Text(
                        "「誰が帯域を使っているか」を表示するには『使用状況へのアクセス』の許可が必要です。",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Spacer(Modifier.height(8.dp))
                    Button(onClick = {
                        context.startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
                    }) { Text("設定を開く") }
                    Spacer(Modifier.height(6.dp))
                    Button(onClick = { vm.refreshPermission() }) { Text("許可した後に再確認") }
                } else {
                    Button(onClick = { vm.loadApps() }, enabled = !s.loadingApps) {
                        Text(if (s.loadingApps) "集計中…" else "集計する")
                    }
                }
            }
        }

        if (s.apps.isNotEmpty()) {
            val maxTotal = s.apps.maxOf { it.totalBytes }.coerceAtLeast(1)
            items(s.apps, key = { it.uid }) { app -> AppRow(app, maxTotal) }
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

@Composable
private fun AppRow(app: AppTraffic, maxTotal: Long) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(app.label, fontWeight = FontWeight.SemiBold,
                    style = MaterialTheme.typography.bodyLarge)
                Text(TrafficMonitor.formatBytes(app.totalBytes),
                    style = MaterialTheme.typography.bodyMedium, fontFamily = FontFamily.Monospace)
            }
            Spacer(Modifier.height(6.dp))
            Box { MeterBar(app.totalBytes.toFloat() / maxTotal, MaterialTheme.colorScheme.primary) }
            Spacer(Modifier.height(4.dp))
            Text(
                "↓ ${TrafficMonitor.formatBytes(app.rxBytes)}   ↑ ${TrafficMonitor.formatBytes(app.txBytes)}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
