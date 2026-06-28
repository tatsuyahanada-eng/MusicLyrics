package com.tatsuya.idtool

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.opengl.Matrix
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.ar.core.Camera
import com.google.ar.core.Config
import com.google.ar.core.TrackingState
import io.github.sceneview.ar.ARScene
import io.github.sceneview.rememberEngine
import kotlin.math.sqrt

private val Teal = Color(0xFF26A69A)

class DistanceActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(colorScheme = darkColorScheme(primary = Teal)) {
                DistanceScreen()
            }
        }
    }
}

@Composable
private fun DistanceScreen() {
    val context = LocalContext.current
    var hasCamera by remember {
        mutableStateOf(
            context.checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        )
    }
    val permLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { hasCamera = it }

    LaunchedEffect(Unit) { if (!hasCamera) permLauncher.launch(Manifest.permission.CAMERA) }

    if (hasCamera) {
        MeasureContent()
    } else {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
                .padding(24.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text("距離測定にはカメラの許可が必要です。", color = MaterialTheme.colorScheme.onBackground)
            Spacer(Modifier.height(12.dp))
            Button(onClick = { permLauncher.launch(Manifest.permission.CAMERA) }) { Text("カメラを許可") }
        }
    }
}

@Composable
private fun MeasureContent() {
    val context = LocalContext.current

    var memo by remember { mutableStateOf("") }
    var measuring by remember { mutableStateOf(false) }
    var requestStart by remember { mutableStateOf(false) }
    var tracking by remember { mutableStateOf(false) }
    var startWorld by remember { mutableStateOf<FloatArray?>(null) }
    var liveDist by remember { mutableStateOf<Float?>(null) }
    var startScreen by remember { mutableStateOf<Offset?>(null) }
    val results = remember { mutableStateListOf<Pair<String, Float>>() }

    var viewW by remember { mutableStateOf(0f) }
    var viewH by remember { mutableStateOf(0f) }

    val engine = rememberEngine()

    Box(
        modifier = Modifier
            .fillMaxSize()
            .onSizeChanged { viewW = it.width.toFloat(); viewH = it.height.toFloat() }
    ) {
        ARScene(
            modifier = Modifier.fillMaxSize(),
            engine = engine,
            planeRenderer = false,
            sessionConfiguration = { _, config ->
                config.planeFindingMode = Config.PlaneFindingMode.DISABLED
                config.lightEstimationMode = Config.LightEstimationMode.DISABLED
                config.depthMode = Config.DepthMode.DISABLED
            },
            onSessionUpdated = { _, frame ->
                val cam = frame.camera
                if (cam.trackingState == TrackingState.TRACKING) {
                    tracking = true
                    val p = cam.pose
                    val cur = floatArrayOf(p.tx(), p.ty(), p.tz())
                    if (requestStart) {
                        startWorld = cur
                        requestStart = false
                    }
                    val s = startWorld
                    if (measuring && s != null) {
                        liveDist = dist3(s, cur)
                        startScreen = if (viewW > 0f && viewH > 0f) worldToScreen(cam, viewW, viewH, s) else null
                    }
                } else {
                    tracking = false
                }
            }
        )

        // AR上の線と中央レチクル
        Canvas(modifier = Modifier.fillMaxSize()) {
            val center = Offset(size.width / 2f, size.height / 2f)
            drawCircle(color = Color.White, radius = 14f, center = center, style = Stroke(width = 3f))
            drawCircle(color = Teal, radius = 4f, center = center)
            val s = startScreen
            if (measuring && s != null) {
                drawLine(color = Teal, start = s, end = center, strokeWidth = 7f)
                drawCircle(color = Color(0xFFFFCA28), radius = 13f, center = s)
            }
        }

        // 画面中央上に距離を大きく表示
        if (measuring) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
                Box(
                    modifier = Modifier
                        .padding(top = 84.dp)
                        .background(Color(0xCC000000), RoundedCornerShape(10.dp))
                        .padding(horizontal = 16.dp, vertical = 6.dp)
                ) {
                    Text(
                        text = "%.2f m".format(liveDist ?: 0f),
                        color = Color.White,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 40.sp
                    )
                }
            }
        }

        // 操作オーバーレイ
        Column(modifier = Modifier.fillMaxSize().padding(12.dp)) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color(0xCC000000), RoundedCornerShape(8.dp))
                    .padding(10.dp)
            ) {
                Text(
                    if (tracking) "トラッキング中：メモ→計測→移動→終点確定"
                    else "周囲を映してゆっくり動かしてください…",
                    color = if (tracking) Color(0xFF80CBC4) else Color(0xFFFFB74D),
                    fontWeight = FontWeight.Bold, fontSize = 13.sp
                )
            }

            Spacer(Modifier.weight(1f))

            OutlinedTextField(
                value = memo,
                onValueChange = { memo = it },
                placeholder = { Text("メモ（例：入口→レジ）") },
                singleLine = true,
                enabled = !measuring,
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color(0x88000000), RoundedCornerShape(8.dp))
            )

            Spacer(Modifier.height(8.dp))

            Button(
                onClick = {
                    if (!measuring) {
                        if (tracking) {
                            startWorld = null
                            startScreen = null
                            liveDist = 0f
                            requestStart = true
                            measuring = true
                        } else {
                            Toast.makeText(context, "トラッキング後に押してください", Toast.LENGTH_SHORT).show()
                        }
                    } else {
                        val d = liveDist
                        if (d != null) {
                            results.add(0, memo.ifBlank { "計測${results.size + 1}" } to d)
                            Toast.makeText(context, "記録: %.2f m".format(d), Toast.LENGTH_SHORT).show()
                        }
                        measuring = false
                        startWorld = null
                        startScreen = null
                        liveDist = null
                        memo = ""
                    }
                },
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (measuring) Color(0xFFE57373) else Teal
                ),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    if (measuring) "終点を確定して記録" else "計測（始点をセット）",
                    fontWeight = FontWeight.Bold, fontSize = 16.sp
                )
            }

            Spacer(Modifier.height(8.dp))

            if (results.isNotEmpty()) {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 160.dp)
                        .background(Color(0xCC000000), RoundedCornerShape(8.dp))
                        .padding(6.dp)
                ) {
                    itemsIndexed(results) { i, item ->
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                "${results.size - i}. ${item.first}",
                                color = Color.White, fontSize = 13.sp,
                                modifier = Modifier.weight(1f)
                            )
                            Text(
                                "%.2f m".format(item.second),
                                color = Color(0xFF80CBC4),
                                fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.Bold, fontSize = 14.sp
                            )
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = { copyText(context, buildReport(results)) },
                        modifier = Modifier.weight(1f)
                    ) { Text("コピー") }
                    OutlinedButton(
                        onClick = { shareText(context, buildReport(results)) },
                        modifier = Modifier.weight(1f)
                    ) { Text("共有（保存）") }
                }
            }
        }
    }
}

private fun dist3(a: FloatArray, b: FloatArray): Float {
    val dx = a[0] - b[0]; val dy = a[1] - b[1]; val dz = a[2] - b[2]
    return sqrt(dx * dx + dy * dy + dz * dz)
}

private fun worldToScreen(cam: Camera, w: Float, h: Float, p: FloatArray): Offset? {
    val view = FloatArray(16); cam.getViewMatrix(view, 0)
    val proj = FloatArray(16); cam.getProjectionMatrix(proj, 0, 0.1f, 100f)
    val mvp = FloatArray(16); Matrix.multiplyMM(mvp, 0, proj, 0, view, 0)
    val clip = FloatArray(4)
    Matrix.multiplyMV(clip, 0, mvp, 0, floatArrayOf(p[0], p[1], p[2], 1f), 0)
    if (clip[3] <= 0f) return null
    val ndcX = clip[0] / clip[3]
    val ndcY = clip[1] / clip[3]
    val sx = (ndcX * 0.5f + 0.5f) * w
    val sy = (1f - (ndcY * 0.5f + 0.5f)) * h
    return Offset(sx, sy)
}

private fun buildReport(results: List<Pair<String, Float>>): String {
    val sb = StringBuilder("距離測定結果\n")
    results.asReversed().forEachIndexed { i, item ->
        sb.append("${i + 1}. ${item.first}: %.2f m\n".format(item.second))
    }
    return sb.toString().trimEnd()
}

private fun copyText(context: Context, text: String) {
    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
    cm.setPrimaryClip(android.content.ClipData.newPlainText("距離測定", text))
    Toast.makeText(context, "コピーしました", Toast.LENGTH_SHORT).show()
}

private fun shareText(context: Context, text: String) {
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_TEXT, text)
    }
    context.startActivity(Intent.createChooser(intent, "距離測定結果を保存・共有"))
}
