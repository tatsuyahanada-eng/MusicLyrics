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
import androidx.compose.foundation.clickable
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
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
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

enum class MeasureType { DISTANCE, AREA }

data class Record(val type: MeasureType, val memo: String, val value: Float, val detail: String) {
    fun display(): String = when (type) {
        MeasureType.DISTANCE -> "%.2f m".format(value)
        MeasureType.AREA -> "%.2f m²".format(value) + if (detail.isNotEmpty()) "（$detail）" else ""
    }
}

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
            modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).padding(24.dp),
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

    val records = remember { mutableStateListOf<Record>().apply { addAll(loadRecords(context)) } }
    var remeasureIndex by remember { mutableIntStateOf(-1) }
    var mode by remember { mutableStateOf(MeasureType.DISTANCE) }
    var memo by remember { mutableStateOf("") }

    var measuring by remember { mutableStateOf(false) }
    var requestAddPoint by remember { mutableStateOf(false) }
    var tracking by remember { mutableStateOf(false) }
    val pts = remember { mutableStateListOf<FloatArray>() }   // 記録済みの点（始点/角）
    var ptsScreen by remember { mutableStateOf<List<Offset?>>(emptyList()) }
    var liveBig by remember { mutableStateOf("") }            // 画面中央の大きな数字
    var liveDist by remember { mutableStateOf(0f) }           // 距離モードの確定用

    var viewW by remember { mutableStateOf(0f) }
    var viewH by remember { mutableStateOf(0f) }

    val engine = rememberEngine()

    fun persist() = saveRecords(context, records)

    fun resetMeasure() {
        measuring = false
        requestAddPoint = false
        pts.clear()
        ptsScreen = emptyList()
        liveBig = ""
        liveDist = 0f
    }

    fun confirm() {
        val name = memo.ifBlank {
            if (remeasureIndex in records.indices) records[remeasureIndex].memo
            else "計測${records.size + 1}"
        }
        val rec: Record? = when (mode) {
            MeasureType.DISTANCE ->
                if (pts.isNotEmpty()) Record(MeasureType.DISTANCE, name, liveDist, "") else null
            MeasureType.AREA ->
                if (pts.size >= 3) {
                    val a = dist3(pts[0], pts[1])
                    val b = dist3(pts[1], pts[2])
                    Record(MeasureType.AREA, name, a * b, "%.2f×%.2f".format(a, b))
                } else null
        }
        if (rec != null) {
            if (remeasureIndex in records.indices) records[remeasureIndex] = rec
            else records.add(0, rec)
            persist()
            Toast.makeText(context, "記録: ${rec.display()}", Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(context, "点が足りません", Toast.LENGTH_SHORT).show()
        }
        remeasureIndex = -1
        memo = ""
        resetMeasure()
    }

    Box(
        modifier = Modifier.fillMaxSize()
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
                    val pose = cam.pose
                    val cur = floatArrayOf(pose.tx(), pose.ty(), pose.tz())
                    if (requestAddPoint) {
                        pts.add(cur)
                        requestAddPoint = false
                    }
                    if (measuring && pts.isNotEmpty()) {
                        ptsScreen = pts.map { worldToScreen(cam, viewW, viewH, it) }
                        when (mode) {
                            MeasureType.DISTANCE -> {
                                liveDist = dist3(pts[0], cur)
                                liveBig = "%.2f m".format(liveDist)
                            }
                            MeasureType.AREA -> {
                                liveBig = when (pts.size) {
                                    1 -> "辺A: %.2f m".format(dist3(pts[0], cur))
                                    2 -> "面積(暫定): %.2f m²".format(dist3(pts[0], pts[1]) * dist3(pts[1], cur))
                                    else -> "%.2f m²".format(dist3(pts[0], pts[1]) * dist3(pts[1], pts[2]))
                                }
                            }
                        }
                    }
                } else {
                    tracking = false
                }
            }
        )

        // AR上の線・点・中央レチクル
        Canvas(modifier = Modifier.fillMaxSize()) {
            val center = Offset(size.width / 2f, size.height / 2f)
            drawCircle(color = Color.White, radius = 14f, center = center, style = Stroke(width = 3f))
            drawCircle(color = Teal, radius = 4f, center = center)
            val sp = ptsScreen
            // 記録済みの点どうしを連結
            for (i in 0 until sp.size - 1) {
                val a = sp[i]; val b = sp[i + 1]
                if (a != null && b != null) drawLine(Teal, a, b, strokeWidth = 7f)
            }
            // 最後の点から現在地（中央）へライブ線
            if (measuring && sp.isNotEmpty()) {
                sp.last()?.let { drawLine(Teal, it, center, strokeWidth = 7f) }
            }
            sp.forEach { it?.let { o -> drawCircle(Color(0xFFFFCA28), 13f, o) } }
        }

        // 画面中央上に大きく数値表示
        if (measuring && liveBig.isNotEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
                Box(
                    modifier = Modifier.padding(top = 84.dp)
                        .background(Color(0xCC000000), RoundedCornerShape(10.dp))
                        .padding(horizontal = 16.dp, vertical = 6.dp)
                ) {
                    Text(
                        liveBig, color = Color.White, fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace, fontSize = 34.sp
                    )
                }
            }
        }

        // 操作オーバーレイ
        Column(modifier = Modifier.fillMaxSize().padding(12.dp)) {
            // モード切替
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = mode == MeasureType.DISTANCE,
                    onClick = { if (!measuring) mode = MeasureType.DISTANCE },
                    label = { Text("距離") }
                )
                FilterChip(
                    selected = mode == MeasureType.AREA,
                    onClick = { if (!measuring) mode = MeasureType.AREA },
                    label = { Text("面積（部屋）") }
                )
            }

            Spacer(Modifier.height(6.dp))

            Box(
                modifier = Modifier.fillMaxWidth()
                    .background(Color(0xCC000000), RoundedCornerShape(8.dp)).padding(8.dp)
            ) {
                val msg = when {
                    !tracking -> "周囲を映してゆっくり動かしてください…"
                    remeasureIndex in records.indices -> "再測定: ${records[remeasureIndex].memo}（記録一覧から解除可）"
                    mode == MeasureType.DISTANCE -> "メモ→計測→移動→終点確定"
                    else -> "角を3点記録（角1→角2→角3）して面積を確定"
                }
                Text(
                    msg,
                    color = if (tracking) Color(0xFF80CBC4) else Color(0xFFFFB74D),
                    fontWeight = FontWeight.Bold, fontSize = 12.sp
                )
            }

            Spacer(Modifier.weight(1f))

            OutlinedTextField(
                value = memo,
                onValueChange = { memo = it },
                placeholder = { Text("メモ（例：入口→レジ / 会議室）") },
                singleLine = true,
                enabled = !measuring,
                modifier = Modifier.fillMaxWidth().background(Color(0x88000000), RoundedCornerShape(8.dp))
            )

            Spacer(Modifier.height(8.dp))

            // モード別の操作ボタン
            if (mode == MeasureType.DISTANCE) {
                Button(
                    onClick = {
                        if (!measuring) {
                            if (tracking) {
                                pts.clear(); ptsScreen = emptyList()
                                requestAddPoint = true; measuring = true; liveDist = 0f
                                liveBig = "0.00 m"
                            } else Toast.makeText(context, "トラッキング後に押してください", Toast.LENGTH_SHORT).show()
                        } else confirm()
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
            } else {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = {
                            if (tracking) { requestAddPoint = true; measuring = true }
                            else Toast.makeText(context, "トラッキング後に押してください", Toast.LENGTH_SHORT).show()
                        },
                        modifier = Modifier.weight(1f)
                    ) { Text("角を記録（${pts.size}/3）", fontWeight = FontWeight.Bold) }

                    Button(
                        onClick = { confirm() },
                        enabled = pts.size >= 3,
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFE57373)),
                        modifier = Modifier.weight(1f)
                    ) { Text("面積を確定", fontWeight = FontWeight.Bold) }
                }
            }

            if (measuring) {
                TextButton(onClick = { resetMeasure() }) { Text("やり直し", color = Color(0xFFFFAB91)) }
            }

            Spacer(Modifier.height(6.dp))

            // 記録一覧
            if (records.isNotEmpty()) {
                LazyColumn(
                    modifier = Modifier.fillMaxWidth().heightIn(max = 180.dp)
                        .background(Color(0xCC000000), RoundedCornerShape(8.dp)).padding(6.dp)
                ) {
                    itemsIndexed(records) { i, item ->
                        val isTarget = remeasureIndex == i
                        Row(
                            modifier = Modifier.fillMaxWidth()
                                .background(
                                    if (isTarget) Color(0x3326A69A) else Color.Transparent,
                                    RoundedCornerShape(6.dp)
                                )
                                .clickable {
                                    remeasureIndex = if (isTarget) -1 else i
                                    if (!isTarget) {
                                        mode = item.type
                                        memo = item.memo
                                    }
                                }
                                .padding(horizontal = 4.dp, vertical = 3.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                "${i + 1}. ${item.memo}",
                                color = Color.White, fontSize = 13.sp, modifier = Modifier.weight(1f)
                            )
                            Text(
                                item.display(),
                                color = Color(0xFF80CBC4), fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.Bold, fontSize = 13.sp
                            )
                            TextButton(onClick = {
                                records.removeAt(i)
                                if (remeasureIndex == i) remeasureIndex = -1
                                persist()
                            }) { Text("×", color = Color(0xFFEF9A9A)) }
                        }
                    }
                }
                Spacer(Modifier.height(6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = { copyText(context, buildReport(records)) },
                        modifier = Modifier.weight(1f)
                    ) { Text("コピー") }
                    OutlinedButton(
                        onClick = { shareText(context, buildReport(records)) },
                        modifier = Modifier.weight(1f)
                    ) { Text("共有") }
                    OutlinedButton(
                        onClick = { records.clear(); remeasureIndex = -1; persist() },
                        modifier = Modifier.weight(1f)
                    ) { Text("全消去") }
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
    return Offset((ndcX * 0.5f + 0.5f) * w, (1f - (ndcY * 0.5f + 0.5f)) * h)
}

private fun buildReport(records: List<Record>): String {
    val sb = StringBuilder("測定結果\n")
    records.asReversed().forEachIndexed { i, r ->
        sb.append("${i + 1}. ${r.memo}: ${r.display()}\n")
    }
    return sb.toString().trimEnd()
}

// ── 端末内に保存（自動）──
private const val PREFS = "distance_prefs"
private const val KEY = "records"
private const val FS = ""
private const val RS = ""

private fun saveRecords(context: Context, records: List<Record>) {
    val data = records.joinToString(RS) { r ->
        listOf(r.type.name, r.value.toString(), r.detail, r.memo.replace("\n", " ")).joinToString(FS)
    }
    context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, data).apply()
}

private fun loadRecords(context: Context): List<Record> {
    val data = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, "") ?: ""
    if (data.isBlank()) return emptyList()
    return data.split(RS).mapNotNull { line ->
        val f = line.split(FS)
        if (f.size < 4) return@mapNotNull null
        val type = runCatching { MeasureType.valueOf(f[0]) }.getOrNull() ?: return@mapNotNull null
        val value = f[1].toFloatOrNull() ?: return@mapNotNull null
        Record(type, f[3], value, f[2])
    }
}

private fun copyText(context: Context, text: String) {
    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
    cm.setPrimaryClip(android.content.ClipData.newPlainText("測定結果", text))
    Toast.makeText(context, "コピーしました", Toast.LENGTH_SHORT).show()
}

private fun shareText(context: Context, text: String) {
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_TEXT, text)
    }
    context.startActivity(Intent.createChooser(intent, "測定結果を保存・共有"))
}
