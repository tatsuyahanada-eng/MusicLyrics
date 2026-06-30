package com.netdiag.ui.screens

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.netdiag.core.wifi.SecurityRisk
import com.netdiag.core.wifi.WifiAp
import com.netdiag.ui.LabeledValue
import com.netdiag.ui.MeterBar
import com.netdiag.ui.SectionCard
import com.netdiag.ui.Tag

@Composable
fun WifiScreen(vm: WifiViewModel = viewModel()) {
    val s by vm.state.collectAsStateWithLifecycle()
    val context = LocalContext.current

    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        vm.onPermissionResult(result.values.any { it })
    }

    LaunchedEffect(Unit) {
        val granted = ContextCompat.checkSelfPermission(
            context, Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        vm.onPermissionResult(granted)
    }

    LazyColumn(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 12.dp),
    ) {
        item {
            SectionCard("Wi-Fi 環境") {
                if (!s.wifiEnabled) {
                    Text("Wi-Fi がオフです。オンにしてください。",
                        color = MaterialTheme.colorScheme.error)
                }
                LabeledValue("接続中のRSSI",
                    s.connectedRssi?.let { "$it dBm" } ?: "未接続")
                Spacer(Modifier.height(10.dp))
                if (!s.hasPermission) {
                    Text(
                        "周辺APのスキャンには位置情報の許可が必要です（Androidの仕様）。",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(8.dp))
                    Button(onClick = {
                        launcher.launch(arrayOf(
                            Manifest.permission.ACCESS_FINE_LOCATION,
                            Manifest.permission.ACCESS_COARSE_LOCATION,
                        ))
                    }) { Text("位置情報を許可") }
                } else {
                    Button(onClick = { vm.refresh() }, enabled = !s.scanning) {
                        Text(if (s.scanning) "スキャン中…" else "再スキャン")
                    }
                }
            }
        }

        if (s.channelLoad.isNotEmpty()) {
            item {
                SectionCard("チャンネル混雑（AP数）") {
                    val max = s.channelLoad.maxOf { it.apCount }.coerceAtLeast(1)
                    s.channelLoad.forEach { load ->
                        Row(verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(vertical = 3.dp)) {
                            Text("${load.band} ch${load.channel}",
                                modifier = Modifier.width(110.dp),
                                style = MaterialTheme.typography.bodySmall,
                                fontFamily = FontFamily.Monospace)
                            Box(Modifier.weight(1f)) {
                                MeterBar(load.apCount.toFloat() / max,
                                    MaterialTheme.colorScheme.primary)
                            }
                            Spacer(Modifier.width(8.dp))
                            Text("${load.apCount}", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
        }

        if (s.aps.isNotEmpty()) {
            item {
                Text("周辺のアクセスポイント (${s.aps.size})",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(start = 4.dp, top = 4.dp))
            }
            items(s.aps, key = { it.bssid }) { ap -> ApCard(ap) }
        }
    }
}

@Composable
private fun ApCard(ap: WifiAp) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(ap.ssid, fontWeight = FontWeight.SemiBold,
                        style = MaterialTheme.typography.bodyLarge)
                    Text(ap.bssid, fontFamily = FontFamily.Monospace,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (ap.isConnected) Tag("接続中", MaterialTheme.colorScheme.secondary)
            }
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("${ap.rssi} dBm", modifier = Modifier.width(80.dp),
                    style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
                Box(Modifier.weight(1f)) {
                    MeterBar(ap.signalQuality / 100f, signalColor(ap.signalQuality))
                }
            }
            Spacer(Modifier.height(8.dp))
            LabeledValue("バンド / Ch", "${ap.band} / ch${ap.channel} (${ap.channelWidthMhz}MHz)")
            Row(Modifier.fillMaxWidth().padding(top = 6.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically) {
                Text("セキュリティ", style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                Tag(ap.security, riskColor(ap.risk))
            }
        }
    }
}

private fun signalColor(quality: Int): Color = when {
    quality >= 66 -> Color(0xFF2E9E54)
    quality >= 40 -> Color(0xFFE0A422)
    else -> Color(0xFFD05050)
}

private fun riskColor(risk: SecurityRisk): Color = when (risk) {
    SecurityRisk.SAFE -> Color(0xFF2E9E54)
    SecurityRisk.OK -> Color(0xFF3C8DBC)
    SecurityRisk.WEAK -> Color(0xFFE0A422)
    SecurityRisk.DANGER -> Color(0xFFD05050)
    SecurityRisk.UNKNOWN -> Color(0xFF808080)
}
