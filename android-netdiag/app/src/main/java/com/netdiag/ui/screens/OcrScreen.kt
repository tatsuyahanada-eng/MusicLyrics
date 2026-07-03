package com.netdiag.ui.screens

import android.Manifest
import android.content.pm.PackageManager
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.OptIn
import androidx.camera.core.CameraSelector
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import com.netdiag.ui.NameColor
import java.util.concurrent.Executors

/**
 * Google-Lens-style OCR: point the camera at printed text (e.g. a Wi-Fi
 * router's sticker) to read it live, then copy the whole text or the detected
 * SSID / encryption key, or push it into the memo.
 */
@Composable
fun OcrScreen(onClose: () -> Unit, onAppend: (String) -> Unit) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current

    var granted by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED
        )
    }
    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted = it }
    LaunchedEffect(Unit) { if (!granted) launcher.launch(Manifest.permission.CAMERA) }

    var recognized by remember { mutableStateOf("") }
    val wifi = remember(recognized) { extractWifi(recognized) }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        if (granted) {
            CameraTextAnalyzer(onText = { recognized = it })
        } else {
            Column(
                Modifier.fillMaxSize().padding(24.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text("カメラの許可が必要です", color = Color.White)
                Spacer(Modifier.height(12.dp))
                Button(onClick = { launcher.launch(Manifest.permission.CAMERA) }) {
                    Text("カメラを許可")
                }
            }
        }

        // Bottom overlay panel with results and actions.
        Column(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .padding(12.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(Color(0xE6101010))
                .padding(12.dp)
        ) {
            Text(
                "Wi-Fiシールや機器の文字にかざしてください",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall,
            )
            Spacer(Modifier.height(8.dp))

            wifi.ssid?.let { CopyLine("SSID", it, clipboard, context) }
            wifi.key?.let { CopyLine("KEY / パスワード", it, clipboard, context) }

            Spacer(Modifier.height(8.dp))
            Text("認識テキスト", color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.labelSmall)
            Box(
                Modifier
                    .fillMaxWidth()
                    .heightIn(max = 140.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                Text(
                    recognized.ifBlank { "（読み取り中…）" },
                    color = Color.White,
                    fontFamily = FontFamily.Monospace,
                    style = MaterialTheme.typography.bodySmall,
                )
            }

            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = {
                        clipboard.setText(AnnotatedString(recognized))
                        toast(context, "全文をコピーしました")
                    },
                    enabled = recognized.isNotBlank(),
                ) { Text("全文コピー") }
                OutlinedButton(
                    onClick = { onAppend(recognized) },
                    enabled = recognized.isNotBlank(),
                ) { Text("メモに追加") }
                OutlinedButton(onClick = onClose) { Text("閉じる") }
            }
        }
    }
}

@Composable
private fun CopyLine(
    label: String,
    value: String,
    clipboard: androidx.compose.ui.platform.ClipboardManager,
    context: android.content.Context,
) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.labelSmall)
            Text(value, color = NameColor, fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace)
        }
        Spacer(Modifier.width(8.dp))
        OutlinedButton(onClick = {
            clipboard.setText(AnnotatedString(value))
            toast(context, "$label をコピーしました")
        }) { Text("コピー") }
    }
}

@OptIn(ExperimentalGetImage::class)
@Composable
private fun CameraTextAnalyzer(onText: (String) -> Unit) {
    val lifecycleOwner = LocalLifecycleOwner.current
    val recognizer = remember { TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS) }
    val executor = remember { Executors.newSingleThreadExecutor() }
    var provider by remember { mutableStateOf<ProcessCameraProvider?>(null) }

    DisposableEffect(Unit) {
        onDispose {
            provider?.unbindAll()
            executor.shutdown()
            recognizer.close()
        }
    }

    androidx.compose.ui.viewinterop.AndroidView(
        modifier = Modifier.fillMaxSize(),
        factory = { ctx ->
            val previewView = PreviewView(ctx)
            val future = ProcessCameraProvider.getInstance(ctx)
            future.addListener({
                val cameraProvider = future.get()
                provider = cameraProvider
                val preview = Preview.Builder().build().also {
                    it.setSurfaceProvider(previewView.surfaceProvider)
                }
                val analysis = ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()
                analysis.setAnalyzer(executor) { proxy ->
                    val media = proxy.image
                    if (media == null) {
                        proxy.close()
                        return@setAnalyzer
                    }
                    val image = InputImage.fromMediaImage(media, proxy.imageInfo.rotationDegrees)
                    recognizer.process(image)
                        .addOnSuccessListener { onText(it.text) }
                        .addOnCompleteListener { proxy.close() }
                }
                try {
                    cameraProvider.unbindAll()
                    cameraProvider.bindToLifecycle(
                        lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis
                    )
                } catch (_: Exception) {
                    // camera unavailable
                }
            }, ContextCompat.getMainExecutor(ctx))
            previewView
        },
    )
}

/** Detected Wi-Fi credentials from OCR text. */
data class WifiCredentials(val ssid: String?, val key: String?)

private val SSID_REGEX = Regex(
    "(?i)(?:ssid|network\\s*name|ネットワーク名)\\s*[:：]?\\s*([\\w\\-\\.@]{1,32})"
)
private val KEY_REGEX = Regex(
    "(?i)(?:password|passphrase|pass|key|暗号化キー|暗号キー|パスワード|pin|kpsk|psk)\\s*[:：]?\\s*([\\w\\-\\.@#!\\$%]{4,64})"
)

/** Extracts SSID / key from raw OCR text using common sticker labels. */
fun extractWifi(text: String): WifiCredentials {
    var ssid: String? = null
    var key: String? = null
    for (line in text.lines()) {
        if (ssid == null) SSID_REGEX.find(line)?.let { ssid = it.groupValues[1] }
        if (key == null) KEY_REGEX.find(line)?.let { key = it.groupValues[1] }
    }
    return WifiCredentials(ssid, key)
}

private fun toast(context: android.content.Context, msg: String) {
    Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
}
