package com.netdiag.ui.screens

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.netdiag.core.DiagnosticsLog
import com.netdiag.core.ImageStore
import com.netdiag.core.PdfExporter
import com.netdiag.ui.SectionCard
import java.io.File

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun MemoScreen(vm: MemoViewModel = viewModel()) {
    val text by vm.text.collectAsStateWithLifecycle()
    val log by DiagnosticsLog.entries.collectAsStateWithLifecycle()
    val images by ImageStore.paths.collectAsStateWithLifecycle()
    val clipboard = LocalClipboardManager.current
    val context = LocalContext.current
    val authority = "${context.packageName}.fileprovider"

    var showOcr by remember { mutableStateOf(false) }
    var pendingFile by remember { mutableStateOf<File?>(null) }

    val takePicture = rememberLauncherForActivityResult(
        ActivityResultContracts.TakePicture()
    ) { ok ->
        val f = pendingFile
        if (ok && f != null) ImageStore.add(f.absolutePath)
        pendingFile = null
    }
    val launchCapture = {
        val f = ImageStore.newImageFile(context)
        pendingFile = f
        takePicture.launch(FileProvider.getUriForFile(context, authority, f))
    }
    val cameraPerm = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted -> if (granted) launchCapture() }

    if (showOcr) {
        OcrScreen(onClose = { showOcr = false }, onAppend = { vm.append(it); showOcr = false })
        return
    }

    Column(
        Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(12.dp)
    ) {
        // ---- Manual memo ----
        SectionCard("メモ") {
            Text(
                "ルーターのIP・機器名・SSID などを一時的にメモできます。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = text,
                onValueChange = vm::setText,
                placeholder = { Text("例: ルーター 192.168.1.1 / admin\nSSID: office-5G") },
                modifier = Modifier.fillMaxWidth().height(200.dp),
            )
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = {
                        clipboard.setText(AnnotatedString(text))
                        toastMemo(context, "コピーしました")
                    },
                    enabled = text.isNotEmpty(),
                ) { Text("コピー") }
                OutlinedButton(onClick = { vm.clear() }, enabled = text.isNotEmpty()) { Text("クリア") }
            }
            Spacer(Modifier.height(8.dp))
            OutlinedButton(onClick = { showOcr = true }, modifier = Modifier.fillMaxWidth()) {
                Text("カメラで文字を読み取る（OCR）")
            }
        }

        Spacer(Modifier.height(12.dp))

        // ---- Auto diagnostic log ----
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
                        toastMemo(context, "ログをコピーしました")
                    },
                    enabled = log.isNotEmpty(),
                ) { Text("コピー") }
                OutlinedButton(onClick = { DiagnosticsLog.clear() }, enabled = log.isNotEmpty()) {
                    Text("クリア")
                }
            }
        }

        Spacer(Modifier.height(12.dp))

        // ---- Device images ----
        SectionCard("機器画像") {
            Text(
                "現場で機器やシールを撮影して保存できます。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
            OutlinedButton(onClick = {
                if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA)
                    == PackageManager.PERMISSION_GRANTED
                ) launchCapture() else cameraPerm.launch(Manifest.permission.CAMERA)
            }) { Text("撮影して追加") }

            if (images.isNotEmpty()) {
                Spacer(Modifier.height(10.dp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    images.forEach { path ->
                        val bmp = remember(path) { decodeThumb(path) }
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            if (bmp != null) {
                                Image(
                                    bitmap = bmp.asImageBitmap(),
                                    contentDescription = null,
                                    modifier = Modifier.size(96.dp),
                                )
                            }
                            OutlinedButton(onClick = { ImageStore.remove(path) }) { Text("削除") }
                        }
                    }
                }
            }
        }

        Spacer(Modifier.height(12.dp))

        // ---- PDF export ----
        SectionCard("PDF 出力") {
            Text(
                "メモ・診断ログ・機器画像をまとめて1つのPDFに保存/共有できます。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
            Button(onClick = {
                runCatching {
                    val pdf = PdfExporter.export(context, text, DiagnosticsLog.asPlainText(), images)
                    val uri = FileProvider.getUriForFile(context, authority, pdf)
                    val share = Intent(Intent.ACTION_SEND).apply {
                        type = "application/pdf"
                        putExtra(Intent.EXTRA_STREAM, uri)
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                    context.startActivity(Intent.createChooser(share, "PDFを共有/保存"))
                }.onFailure { toastMemo(context, "PDF作成に失敗しました") }
            }) { Text("PDFで保存・共有") }
        }
    }
}

private fun decodeThumb(path: String): Bitmap? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(path, bounds)
    if (bounds.outWidth <= 0) return null
    var sample = 1
    while (bounds.outWidth / sample > 400) sample *= 2
    return BitmapFactory.decodeFile(path, BitmapFactory.Options().apply { inSampleSize = sample })
}

private fun toastMemo(context: android.content.Context, msg: String) {
    Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
}
