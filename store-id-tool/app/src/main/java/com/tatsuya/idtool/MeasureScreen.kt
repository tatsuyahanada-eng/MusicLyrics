package com.tatsuya.idtool

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.opengl.Matrix
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
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
import androidx.compose.foundation.layout.imePadding
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
import androidx.compose.material3.TextButton
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

enum class MeasureType { DISTANCE, AREA }

data class Record(val type: MeasureType, val memo: String, val value: Float, val detail: String) {
    fun display(): String = when (type) {
        MeasureType.DISTANCE -> "%.2f m".format(value)
        MeasureType.AREA -> "%.2f m²".format(value) + if (detail.isNotEmpty()) "（$detail）" else ""
    }
}

// オーバーレイ用の色（カメラ上でも見やすい淡色パネル）
private val Panel = Color(0xF2FFFFFF)
private val PanelText = Color(0xFF1B1B1B)
private val Teal = Color(0xFF00897B)
private val TealDark = Color(0xFF00695C)

@Composable
fun MeasureScreen(type: MeasureType, modifier: Modifier = Modifier) {
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
        MeasureBody(type, modifier)
    } else {
        Column(
            modifier = modifier.fillMaxSize().padding(24.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text("測定にはカメラの許可が必要です。", color = MaterialTheme.colorScheme.onBackground)
            Spacer(Modifier.height(12.dp))
            Button(onClick = { permLauncher.launch(Manifest.permission.CAMERA) }) { Text("カメラを許可") }
        }
    }
}

@Composable
private fun MeasureBody(type: MeasureType, modifier: Modifier) {
    val context = LocalContext.current
    val prefsKey = "records_${type.name}"

    val records = remember { mutableStateListOf<Record>().apply { addAll(loadRecords(context, prefsKey)) } }
    var remeasureIndex by remember { mutableIntStateOf(-1) }
    var memo by remember { mutableStateOf("") }

    var measuring by remember { mutableStateOf(false) }
    var requestAddPoint by remember { mutableStateOf(false) }
    var tracking by remember { mutableStateOf(false) }
    val pts = remember { mutableStateListOf<FloatArray>() }
    var ptsScreen by remember { mutableStateOf<List<Offset?>>(emptyList()) }
    var liveBig by remember { mutableStateOf("") }
    var liveDist by remember { mutableStateOf(0f) }

    var viewW by remember { mutableStateOf(0f) }
    var viewH by remember { mutableStateOf(0f) }

    val engine = rememberEngine()

    fun persist() = saveRecords(context, prefsKey, records)

    fun resetMeasure() {
        measuring = false; requestAddPoint = false
        pts.clear(); ptsScreen = emptyList(); liveBig = ""; liveDist = 0f
    }

    fun confirm() {
        val name = memo.ifBlank {
            if (remeasureIndex in records.indices) records[remeasureIndex].memo else "計測${records.size + 1}"
        }
        val rec: Record? = when (type) {
            MeasureType.DISTANCE ->
                if (pts.isNotEmpty()) Record(MeasureType.DISTANCE, name, liveDist, "") else null
            MeasureType.AREA ->
                if (pts.size >= 3) {
                    val a = dist3(pts[0], pts[1]); val b = dist3(pts[1], pts[2])
                    Record(MeasureType.AREA, name, a * b, "%.2f×%.2f".format(a, b))
                } else null
        }
        if (rec != null) {
            if (remeasureIndex in records.indices) records[remeasureIndex] = rec else records.add(0, rec)
            persist()
            Toast.makeText(context, "記録: ${rec.display()}", Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(context, "点が足りません", Toast.LENGTH_SHORT).show()
        }
        remeasureIndex = -1; memo = ""; resetMeasure()
    }

    Box(
        modifier = modifier.fillMaxSize()
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
                    if (requestAddPoint) { pts.add(cur); requestAddPoint = false }
                    if (measuring && pts.isNotEmpty()) {
                        ptsScreen = pts.map { worldToScreen(cam, viewW, viewH, it) }
                        when (type) {
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
                } else tracking = false
            }
        )

        // AR上の線・点・中央レチクル
        Canvas(modifier = Modifier.fillMaxSize()) {
            val center = Offset(size.width / 2f, size.height / 2f)
            drawCircle(color = Color.White, radius = 15f, center = center, style = Stroke(width = 4f))
            drawCircle(color = Teal, radius = 5f, center = center)
            val sp = ptsScreen
            for (i in 0 until sp.size - 1) {
                val a = sp[i]; val b = sp[i + 1]
                if (a != null && b != null) drawLine(Teal, a, b, strokeWidth = 8f)
            }
            if (measuring && sp.isNotEmpty()) sp.last()?.let { drawLine(Teal, it, center, strokeWidth = 8f) }
            sp.forEach { it?.let { o -> drawCircle(Color(0xFFFF8F00), 14f, o) } }
        }

        if (measuring && liveBig.isNotEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
                Box(
                    modifier = Modifier.padding(top = 16.dp)
                        .background(Panel, RoundedCornerShape(10.dp))
                        .padding(horizontal = 16.dp, vertical = 6.dp)
                ) {
                    Text(
                        liveBig, color = TealDark, fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace, fontSize = 34.sp
                    )
                }
            }
        }

        // 操作オーバーレイ
        Column(modifier = Modifier.fillMaxSize().imePadding().padding(12.dp)) {
            Box(
                modifier = Modifier.fillMaxWidth().background(Panel, RoundedCornerShape(8.dp)).padding(8.dp)
            ) {
                val msg = when {
                    !tracking -> "周囲を映してゆっくり動かしてください…"
                    remeasureIndex in records.indices -> "再測定: ${records[remeasureIndex].memo}（一覧で解除可）"
                    type == MeasureType.DISTANCE -> "メモ→計測→移動→終点確定"
                    else -> "角を3点記録（角1→角2→角3）して面積を確定"
                }
                Text(msg, color = if (tracking) TealDark else Color(0xFFE65100),
                    fontWeight = FontWeight.Bold, fontSize = 12.sp)
            }

            Spacer(Modifier.weight(1f))

            OutlinedTextField(
                value = memo,
                onValueChange = { memo = it },
                placeholder = { Text("メモ（例：入口→レジ / 会議室）") },
                singleLine = true,
                enabled = !measuring,
                modifier = Modifier.fillMaxWidth().background(Panel, RoundedCornerShape(8.dp))
            )

            Spacer(Modifier.height(8.dp))

            if (type == MeasureType.DISTANCE) {
                Button(
                    onClick = {
                        if (!measuring) {
                            if (tracking) {
                                pts.clear(); ptsScreen = emptyList()
                                requestAddPoint = true; measuring = true; liveDist = 0f; liveBig = "0.00 m"
                            } else Toast.makeText(context, "トラッキング後に押してください", Toast.LENGTH_SHORT).show()
                        } else confirm()
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (measuring) Color(0xFFD32F2F) else Teal
                    ),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(if (measuring) "終点を確定して記録" else "計測（始点をセット）",
                        fontWeight = FontWeight.Bold, fontSize = 16.sp)
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
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFD32F2F)),
                        modifier = Modifier.weight(1f)
                    ) { Text("面積を確定", fontWeight = FontWeight.Bold) }
                }
            }

            if (measuring) {
                TextButton(onClick = { resetMeasure() }) { Text("やり直し", color = Color(0xFFD84315)) }
            }

            Spacer(Modifier.height(6.dp))

            if (records.isNotEmpty()) {
                LazyColumn(
                    modifier = Modifier.fillMaxWidth().heightIn(max = 180.dp)
                        .background(Panel, RoundedCornerShape(8.dp)).padding(6.dp)
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
                                    if (!isTarget) memo = item.memo
                                }
                                .padding(horizontal = 4.dp, vertical = 3.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("${i + 1}. ${item.memo}", color = PanelText, fontSize = 13.sp,
                                modifier = Modifier.weight(1f))
                            Text(item.display(), color = TealDark, fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.Bold, fontSize = 13.sp)
                            TextButton(onClick = {
                                records.removeAt(i)
                                if (remeasureIndex == i) remeasureIndex = -1
                                persist()
                            }) { Text("×", color = Color(0xFFC62828)) }
                        }
                    }
                }
                Spacer(Modifier.height(6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { copyText(context, buildReport(records)) },
                        modifier = Modifier.weight(1f)) { Text("コピー") }
                    OutlinedButton(onClick = { shareText(context, buildReport(records)) },
                        modifier = Modifier.weight(1f)) { Text("共有") }
                    OutlinedButton(onClick = { records.clear(); remeasureIndex = -1; persist() },
                        modifier = Modifier.weight(1f)) { Text("全消去") }
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
    val ndcX = clip[0] / clip[3]; val ndcY = clip[1] / clip[3]
    return Offset((ndcX * 0.5f + 0.5f) * w, (1f - (ndcY * 0.5f + 0.5f)) * h)
}

private fun buildReport(records: List<Record>): String {
    val sb = StringBuilder("測定結果\n")
    records.asReversed().forEachIndexed { i, r -> sb.append("${i + 1}. ${r.memo}: ${r.display()}\n") }
    return sb.toString().trimEnd()
}

private const val PREFS = "distance_prefs"
private const val FS = "\u001F" // フィールド区切り
private const val RS = "\u001E" // レコード区切り

private fun saveRecords(context: Context, key: String, records: List<Record>) {
    val data = records.joinToString(RS) { r ->
        listOf(r.type.name, r.value.toString(), r.detail, r.memo.replace("\n", " ")).joinToString(FS)
    }
    context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(key, data).apply()
}

/** 結果入力タブから距離記録を読むための公開関数。 */
fun loadDistanceRecords(context: Context): List<Record> =
    loadRecords(context, "records_${MeasureType.DISTANCE.name}")

private fun loadRecords(context: Context, key: String): List<Record> {
    val data = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(key, "") ?: ""
    if (data.isBlank()) return emptyList()
    return data.split(RS).mapNotNull { line ->
        val f = line.split(FS)
        if (f.size < 4) return@mapNotNull null
        val t = runCatching { MeasureType.valueOf(f[0]) }.getOrNull() ?: return@mapNotNull null
        val v = f[1].toFloatOrNull() ?: return@mapNotNull null
        Record(t, f[3], v, f[2])
    }
}

private fun copyText(context: Context, text: String) {
    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
    cm.setPrimaryClip(android.content.ClipData.newPlainText("測定結果", text))
    Toast.makeText(context, "コピーしました", Toast.LENGTH_SHORT).show()
}

private fun shareText(context: Context, text: String) {
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"; putExtra(Intent.EXTRA_TEXT, text)
    }
    context.startActivity(Intent.createChooser(intent, "測定結果を保存・共有"))
}
