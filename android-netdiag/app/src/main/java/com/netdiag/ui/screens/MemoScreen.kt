package com.netdiag.ui.screens

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.netdiag.core.DiagnosticsLog
import androidx.lifecycle.viewmodel.compose.viewModel
import com.netdiag.ui.SectionCard

@Composable
fun MemoScreen(vm: MemoViewModel = viewModel()) {
    val text by vm.text.collectAsStateWithLifecycle()
    val clipboard = LocalClipboardManager.current
    val context = LocalContext.current
    var showOcr by remember { mutableStateOf(false) }

    if (showOcr) {
        OcrScreen(
            onClose = { showOcr = false },
            onAppend = { vm.append(it); showOcr = false },
        )
        return
    }

    Column(
        Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(12.dp)
    ) {
        SectionCard("メモ") {
            Text(
                "ルーターのIP・機器名・SSID・設定などを一時的にメモできます。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = text,
                onValueChange = vm::setText,
                placeholder = { Text("例: ルーター 192.168.1.1 / admin\nSSID: office-5G") },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(260.dp),
            )
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = {
                        clipboard.setText(AnnotatedString(text))
                        Toast.makeText(context, "コピーしました", Toast.LENGTH_SHORT).show()
                    },
                    enabled = text.isNotEmpty(),
                ) { Text("コピー") }
                OutlinedButton(
                    onClick = { vm.clear() },
                    enabled = text.isNotEmpty(),
                ) { Text("クリア") }
            }
            Spacer(Modifier.height(8.dp))
            OutlinedButton(
                onClick = { showOcr = true },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("カメラで文字を読み取る（OCR）") }
        }

        Spacer(Modifier.height(12.dp))

        val log by DiagnosticsLog.entries.collectAsStateWithLifecycle()
        SectionCard("診断ログ（自動記録）") {
            Text(
                "Ping・Traceroute・DNS・外部疎通テストの結果が実行時刻つきで自動保存されます。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
            if (log.isEmpty()) {
                Text("まだログはありません。診断タブで実行してください。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                log.asReversed().take(200).forEach { e ->
                    Text(DiagnosticsLog.formatTime(e.timeMillis),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(e.text, style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace)
                    Spacer(Modifier.height(6.dp))
                }
            }
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = {
                        clipboard.setText(AnnotatedString(DiagnosticsLog.asPlainText()))
                        Toast.makeText(context, "ログをコピーしました", Toast.LENGTH_SHORT).show()
                    },
                    enabled = log.isNotEmpty(),
                ) { Text("コピー") }
                OutlinedButton(
                    onClick = { DiagnosticsLog.clear() },
                    enabled = log.isNotEmpty(),
                ) { Text("クリア") }
            }
        }
    }
}
