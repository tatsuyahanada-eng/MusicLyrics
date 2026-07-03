package com.netdiag.ui.screens

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Matrix
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.material3.Slider
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
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.ClipboardManager
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

private enum class OcrMode { LIVE, CAPTURE }

/**
 * OCR reader with two modes:
 *  - かざす (LIVE): live recognition of a centre band, with a zoom control so
 *    small text can be enlarged.
 *  - 撮影 (CAPTURE): take a photo, then copy exactly the line(s) you need.
 */
@Composable
fun OcrScreen(onClose: () -> Unit, onAppend: (String) -> Unit) {
    val context = LocalContext.current

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

    var mode by remember { mutableStateOf(OcrMode.LIVE) }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        if (!granted) {
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
        } else {
            when (mode) {
                OcrMode.LIVE -> LiveOcr(onAppend)
                OcrMode.CAPTURE -> CaptureOcr(onAppend)
            }
        }

        // Top bar: mode switch + close.
        Row(
            Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
                .background(Color(0xB0000000))
                .padding(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ModeChip("かざす", mode == OcrMode.LIVE) { mode = OcrMode.LIVE }
            Spacer(Modifier.width(8.dp))
            ModeChip("撮影", mode == OcrMode.CAPTURE) { mode = OcrMode.CAPTURE }
            Spacer(Modifier.weight(1f))
            OutlinedButton(onClick = onClose) { Text("閉じる") }
        }
    }
}

@Composable
private fun ModeChip(label: String, selected: Boolean, onClick: () -> Unit) {
    if (selected) {
        Button(onClick = onClick) { Text(label) }
    } else {
        OutlinedButton(onClick = onClick) { Text(label) }
    }
}

// ---------------------------------------------------------------------------
// LIVE mode
// ---------------------------------------------------------------------------

@Composable
private fun LiveOcr(onAppend: (String) -> Unit) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val recognizer = remember { TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS) }
    val executor = remember { Executors.newSingleThreadExecutor() }

    var provider by remember { mutableStateOf<ProcessCameraProvider?>(null) }
    var camera by remember { mutableStateOf<Camera?>(null) }
    var zoom by remember { mutableStateOf(1f) }
    var maxZoom by remember { mutableStateOf(1f) }
    var recognized by remember { mutableStateOf("") }
    val wifi = remember(recognized) { extractWifi(recognized) }
    val onText: (String) -> Unit = { recognized = it }

    DisposableEffect(Unit) {
        onDispose {
            provider?.unbindAll()
            executor.shutdown()
            recognizer.close()
        }
    }

    Box(Modifier.fillMaxSize()) {
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
                        try {
                            val rotated = rotateBitmap(proxy.toBitmap(), proxy.imageInfo.rotationDegrees)
                            val bandH = (rotated.height * OCR_BAND_FRACTION).toInt().coerceAtLeast(1)
                            val topY = ((rotated.height - bandH) / 2).coerceAtLeast(0)
                            val cropped = Bitmap.createBitmap(rotated, 0, topY, rotated.width, bandH)
                            recognizer.process(InputImage.fromBitmap(cropped, 0))
                                .addOnSuccessListener { onText(sanitizeOcr(it.text)) }
                                .addOnCompleteListener { proxy.close() }
                        } catch (_: Exception) {
                            proxy.close()
                        }
                    }
                    try {
                        cameraProvider.unbindAll()
                        val cam = cameraProvider.bindToLifecycle(
                            lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis
                        )
                        camera = cam
                        maxZoom = cam.cameraInfo.zoomState.value?.maxZoomRatio ?: 1f
                    } catch (_: Exception) {
                        // camera unavailable
                    }
                }, ContextCompat.getMainExecutor(ctx))
                previewView
            },
        )

        // Capture frame guide.
        Column(
            Modifier.align(Alignment.Center).fillMaxWidth().padding(horizontal = 20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                "この横枠に文字を合わせてください（ズームで拡大可）",
                color = Color.White,
                style = MaterialTheme.typography.labelSmall,
                modifier = Modifier
                    .clip(RoundedCornerShape(4.dp))
                    .background(Color(0xB0000000))
                    .padding(horizontal = 6.dp, vertical = 2.dp),
            )
            Spacer(Modifier.height(6.dp))
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(88.dp)
                    .border(2.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(8.dp))
            )
        }

        // Bottom overlay: zoom + results.
        Column(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .padding(12.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(Color(0xE6101010))
                .padding(12.dp)
        ) {
            if (maxZoom > 1f) {
                Text("ズーム x${"%.1f".format(zoom)}",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.labelSmall)
                Slider(
                    value = zoom,
                    onValueChange = {
                        zoom = it
                        camera?.cameraControl?.setZoomRatio(it)
                    },
                    valueRange = 1f..maxZoom.coerceAtLeast(1.01f),
                )
            }
            wifi.ssid?.let { CopyLine("SSID", it, clipboard, context) }
            wifi.key?.let { CopyLine("KEY / パスワード", it, clipboard, context) }
            Spacer(Modifier.height(6.dp))
            Box(
                Modifier.fillMaxWidth().heightIn(max = 120.dp).verticalScroll(rememberScrollState())
            ) {
                Text(
                    recognized.ifBlank { "（読み取り中…）" },
                    color = Color.White, fontFamily = FontFamily.Monospace,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = {
                        clipboard.setText(AnnotatedString(recognized))
                        toast(context, "コピーしました")
                    },
                    enabled = recognized.isNotBlank(),
                ) { Text("全文コピー") }
                OutlinedButton(
                    onClick = { onAppend(recognized) },
                    enabled = recognized.isNotBlank(),
                ) { Text("メモに追加") }
            }
        }
    }
}

// ---------------------------------------------------------------------------
// CAPTURE mode
// ---------------------------------------------------------------------------

@Composable
private fun CaptureOcr(onAppend: (String) -> Unit) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val recognizer = remember { TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS) }

    var provider by remember { mutableStateOf<ProcessCameraProvider?>(null) }
    var imageCapture by remember { mutableStateOf<ImageCapture?>(null) }
    var photo by remember { mutableStateOf<Bitmap?>(null) }
    var lines by remember { mutableStateOf<List<String>>(emptyList()) }
    var fullText by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    val wifi = remember(fullText) { extractWifi(fullText) }

    DisposableEffect(Unit) {
        onDispose {
            provider?.unbindAll()
            recognizer.close()
        }
    }

    val current = photo
    if (current == null) {
        Box(Modifier.fillMaxSize()) {
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
                        val capture = ImageCapture.Builder().build()
                        imageCapture = capture
                        try {
                            cameraProvider.unbindAll()
                            cameraProvider.bindToLifecycle(
                                lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, preview, capture
                            )
                        } catch (_: Exception) {
                        }
                    }, ContextCompat.getMainExecutor(ctx))
                    previewView
                },
            )
            Button(
                onClick = {
                    val capture = imageCapture ?: return@Button
                    busy = true
                    capture.takePicture(
                        ContextCompat.getMainExecutor(context),
                        object : ImageCapture.OnImageCapturedCallback() {
                            override fun onCaptureSuccess(image: ImageProxy) {
                                val bmp = rotateBitmap(image.toBitmap(), image.imageInfo.rotationDegrees)
                                image.close()
                                recognizer.process(InputImage.fromBitmap(bmp, 0))
                                    .addOnSuccessListener { vt ->
                                        photo = bmp
                                        fullText = sanitizeOcr(vt.text)
                                        lines = vt.textBlocks
                                            .flatMap { b -> b.lines }
                                            .map { sanitizeOcr(it.text) }
                                            .filter { it.isNotBlank() }
                                        busy = false
                                    }
                                    .addOnFailureListener { busy = false }
                            }

                            override fun onError(exception: ImageCaptureException) {
                                busy = false
                                toast(context, "撮影に失敗しました")
                            }
                        },
                    )
                },
                enabled = !busy,
                modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 24.dp),
            ) { Text(if (busy) "処理中…" else "● 撮影") }
        }
    } else {
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(12.dp)
                .padding(top = 48.dp)
        ) {
            Image(
                bitmap = current.asImageBitmap(),
                contentDescription = null,
                modifier = Modifier.fillMaxWidth().heightIn(max = 220.dp),
            )
            Spacer(Modifier.height(10.dp))
            wifi.ssid?.let { CopyLine("SSID", it, clipboard, context) }
            wifi.key?.let { CopyLine("KEY / パスワード", it, clipboard, context) }
            Spacer(Modifier.height(8.dp))
            Text("必要な行の「コピー」を押してください",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.labelSmall)
            Spacer(Modifier.height(6.dp))
            lines.forEach { line ->
                Row(
                    Modifier.fillMaxWidth().padding(vertical = 3.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(line, color = Color.White, fontFamily = FontFamily.Monospace,
                        style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                    Spacer(Modifier.width(8.dp))
                    OutlinedButton(onClick = {
                        clipboard.setText(AnnotatedString(line))
                        toast(context, "コピーしました")
                    }) { Text("コピー") }
                }
            }
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = {
                    clipboard.setText(AnnotatedString(fullText))
                    toast(context, "全文をコピーしました")
                }) { Text("全文コピー") }
                OutlinedButton(onClick = { onAppend(fullText) }) { Text("メモに追加") }
                OutlinedButton(onClick = {
                    photo = null; lines = emptyList(); fullText = ""
                }) { Text("撮り直し") }
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Shared helpers
// ---------------------------------------------------------------------------

@Composable
private fun CopyLine(
    label: String,
    value: String,
    clipboard: ClipboardManager,
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

/** Fraction of the image height read inside the LIVE capture frame. */
private const val OCR_BAND_FRACTION = 0.24f

private fun rotateBitmap(src: Bitmap, degrees: Int): Bitmap {
    if (degrees % 360 == 0) return src
    val matrix = Matrix().apply { postRotate(degrees.toFloat()) }
    return Bitmap.createBitmap(src, 0, 0, src.width, src.height, matrix, true)
}

/** Drops control characters and blank lines to reduce OCR garbling. */
fun sanitizeOcr(text: String): String =
    text.lines()
        .map { line -> line.filter { it == '\t' || it.code >= 32 }.trim() }
        .filter { it.isNotEmpty() }
        .joinToString("\n")
