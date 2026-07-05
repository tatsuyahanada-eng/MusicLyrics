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
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
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
            SectionCard("回線速度テスト（スピードテスト）") {
                Text(
                    when {
                        s.error != null -> s.error!!
                        s.running -> s.phase
                        s.done -> "測定完了"
                        else -> "STARTで実際にデータを送受信し、回線速度を計測します"
                    },
                    style = MaterialTheme.typography.labelLarge,
                    color = if (s.error != null) MaterialTheme.colorScheme.error
                    else if (s.running) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(12.dp))

                SpeedBig("▼ ダウンロード", s.downMbps, MaterialTheme.colorScheme.primary)
                Spacer(Modifier.height(16.dp))
                SpeedBig("▲ アップロード", s.upMbps, MaterialTheme.colorScheme.secondary)

                Spacer(Modifier.height(10.dp))
                Text(
                    "レイテンシ（Ping）: " + (s.latencyMs?.let { "$it ms" } ?: "—"),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                if (s.running) {
                    Spacer(Modifier.height(12.dp))
                    LinearProgressIndicator(
                        progress = { s.progress },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }

                Spacer(Modifier.height(14.dp))
                Text("計測時間（各方向）", style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(4.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(5, 10, 15).forEach { sec ->
                        if (s.durationSec == sec) {
                            Button(onClick = { vm.setDuration(sec) }, enabled = !s.running) { Text("${sec}秒") }
                        } else {
                            OutlinedButton(onClick = { vm.setDuration(sec) }, enabled = !s.running) { Text("${sec}秒") }
                        }
                    }
                }

                Spacer(Modifier.height(12.dp))
                Button(onClick = { vm.toggle() }, modifier = Modifier.fillMaxWidth()) {
                    Text(if (s.running) "■ 中止" else "▶ スピードテスト開始")
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    "テストデータを実際に送受信します（Cloudflare）。" +
                        "モバイル回線ではデータを消費するため Wi-Fi 推奨。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun SpeedBig(label: String, mbps: Double, color: Color) {
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(label, style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold, color = color)
        Spacer(Modifier.height(2.dp))
        Row(verticalAlignment = Alignment.Bottom) {
            Text(
                String.format("%.1f", mbps),
                fontSize = 56.sp, lineHeight = 60.sp,
                fontWeight = FontWeight.Bold, color = color,
            )
            Spacer(Modifier.width(6.dp))
            Text("Mbps", fontSize = 22.sp, fontWeight = FontWeight.SemiBold,
                color = color, modifier = Modifier.padding(bottom = 8.dp))
        }
    }
}
