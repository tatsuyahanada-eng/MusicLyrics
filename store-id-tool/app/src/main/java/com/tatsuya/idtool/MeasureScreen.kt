package com.tatsuya.idtool

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.opengl.Matrix
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import com.google.ar.core.ArCoreApk
import com.google.ar.core.Camera
import com.google.ar.core.Config
import com.google.ar.core.TrackingState
import io.github.sceneview.ar.ARScene
import io.github.sceneview.rememberEngine
import kotlin.math.sqrt
import kotlin.math.tan

enum class MeasureType { DISTANCE, AREA }

/** 距離の表示単位（既定はメートル）。 */
enum class DistUnit(val label: String) { M("m"), CM("cm") }

/** 距離(メートル)を選択単位でフォーマットする。 */
fun formatDistance(meters: Float, unit: DistUnit): String = when (unit) {
    DistUnit.M -> "%.2f m".format(meters)
    DistUnit.CM -> "%.0f cm".format(meters * 100f)
}

data class Record(val type: MeasureType, val memo: String, val value: Float, val detail: String) {
    fun display(): String = display(DistUnit.M)
    fun display(unit: DistUnit): String = when (type) {
        MeasureType.DISTANCE -> formatDistance(value, unit)
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
    var showList by remember { mutableStateOf(false) }
    var unit by remember { mutableStateOf(loadDistUnit(context)) }
    var editIdx by remember { mutableIntStateOf(-1) }
    var editName by remember { mutableStateOf("") }
    var editValue by remember { mutableStateOf("") }

    var measuring by remember { mutableStateOf(false) }
    var requestAddPoint by remember { mutableStateOf(false) }
    var tracking by remember { mutableStateOf(false) }
    val pts = remember { mutableStateListOf<FloatArray>() }
    var ptsScreen by remember { mutableStateOf<List<Offset?>>(emptyList()) }
    var liveBig by remember { mutableStateOf("") }
    var liveDist by remember { mutableStateOf(0f) }

    var viewW by remember { mutableStateOf(0f) }
    var viewH by remember { mutableStateOf(0f) }

    var useManual by remember { mutableStateOf(false) }
    var useBle by remember { mutableStateOf(false) }
    var useTilt by remember { mutableStateOf(false) }

    val engine = rememberEngine()

    LaunchedEffect(Unit) {
        val supported = try {
            ArCoreApk.getInstance().checkAvailability(context).isSupported
        } catch (_: Exception) { false }
        if (!supported) useManual = true
    }

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
            Toast.makeText(context, "記録: ${rec.display(unit)}", Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(context, "点が足りません", Toast.LENGTH_SHORT).show()
        }
        remeasureIndex = -1; memo = ""; resetMeasure()
    }

    if (useTilt) {
        TiltMeasureBody(type, records, unit, memo, showList, remeasureIndex, editIdx, editName, editValue,
            onMemoChange = { memo = it },
            onShowListChange = { showList = it },
            onRemeasureChange = { remeasureIndex = it },
            onEditIdxChange = { editIdx = it },
            onEditNameChange = { editName = it },
            onEditValueChange = { editValue = it },
            onUnitChange = { unit = it },
            onPersist = { persist() },
            onSwitchToManual = { useTilt = false; useManual = true },
            modifier = modifier
        )
        return
    }

    if (useBle) {
        BleMeasureBody(type, records, unit, memo, showList, remeasureIndex, editIdx, editName, editValue,
            onMemoChange = { memo = it },
            onShowListChange = { showList = it },
            onRemeasureChange = { remeasureIndex = it },
            onEditIdxChange = { editIdx = it },
            onEditNameChange = { editName = it },
            onEditValueChange = { editValue = it },
            onUnitChange = { unit = it },
            onPersist = { persist() },
            onSwitchToManual = { useBle = false; useManual = true },
            modifier = modifier
        )
        return
    }

    if (useManual) {
        ManualMeasureBody(type, records, unit, memo, showList, remeasureIndex, editIdx, editName, editValue,
            onMemoChange = { memo = it },
            onShowListChange = { showList = it },
            onRemeasureChange = { remeasureIndex = it },
            onEditIdxChange = { editIdx = it },
            onEditNameChange = { editName = it },
            onEditValueChange = { editValue = it },
            onUnitChange = { unit = it },
            onPersist = { persist() },
            onSwitchToBle = { useManual = false; useBle = true },
            onSwitchToTilt = { useManual = false; useTilt = true },
            modifier = modifier
        )
        return
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
                                liveBig = formatDistance(liveDist, unit)
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
                    modifier = Modifier.padding(top = 104.dp)
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

            if (!tracking) {
                Spacer(Modifier.height(4.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
                    OutlinedButton(onClick = { useManual = true }, modifier = Modifier.weight(1f)) {
                        Text("手動入力", color = Color(0xFFE65100),
                            fontWeight = FontWeight.Bold, fontSize = 11.sp)
                    }
                    OutlinedButton(onClick = { useTilt = true }, modifier = Modifier.weight(1f)) {
                        Text("傾斜角", color = Color(0xFF6A1B9A),
                            fontWeight = FontWeight.Bold, fontSize = 11.sp)
                    }
                    OutlinedButton(onClick = { useBle = true }, modifier = Modifier.weight(1f)) {
                        Text("BT距離計", color = Color(0xFF1565C0),
                            fontWeight = FontWeight.Bold, fontSize = 11.sp)
                    }
                }
            }

            Spacer(Modifier.weight(1f))

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp, Alignment.End)
            ) {
                Text("単位", color = PanelText, fontWeight = FontWeight.Bold, fontSize = 11.sp)
                DistUnit.entries.forEach { u ->
                    val sel = unit == u
                    Box(
                        modifier = Modifier
                            .background(if (sel) Teal else Panel, RoundedCornerShape(6.dp))
                            .clickable { unit = u; saveDistUnit(context, u) }
                            .padding(horizontal = 10.dp, vertical = 3.dp)
                    ) {
                        Text(u.label, color = if (sel) Color.White else PanelText, fontSize = 12.sp,
                            fontWeight = if (sel) FontWeight.Bold else FontWeight.Normal)
                    }
                }
            }

            Spacer(Modifier.height(4.dp))

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
                            pts.clear(); ptsScreen = emptyList()
                            requestAddPoint = true; measuring = true; liveDist = 0f; liveBig = formatDistance(0f, unit)
                        } else confirm()
                    },
                    enabled = tracking || measuring,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (measuring) Color(0xFFD32F2F) else Teal
                    ),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        when {
                            measuring -> "終点を確定して記録"
                            tracking -> "計測（始点をセット）"
                            else -> "トラッキング中…"
                        },
                        fontWeight = FontWeight.Bold, fontSize = 16.sp)
                }
            } else {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = { requestAddPoint = true; measuring = true },
                        enabled = tracking,
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

            RecordListUI(records, unit, showList, remeasureIndex, memo, editIdx,
                onShowListChange = { showList = it },
                onRemeasureChange = { remeasureIndex = it; if (it >= 0 && it in records.indices) memo = records[it].memo },
                onMemoUpdate = { idx ->
                    val cur = records[idx]
                    records[idx] = cur.copy(memo = memo.ifBlank { cur.memo })
                    persist()
                    Toast.makeText(context, "メモ更新", Toast.LENGTH_SHORT).show()
                },
                onEditStart = { i ->
                    editIdx = i; editName = records[i].memo; editValue = valueForEdit(records[i].value, unit)
                },
                onDelete = { i ->
                    records.removeAt(i)
                    if (remeasureIndex == i) remeasureIndex = -1
                    persist()
                },
                onCopy = { copyText(context, buildReport(records, unit)) },
                onShare = { shareText(context, buildReport(records, unit)) },
                onClearAll = { records.clear(); remeasureIndex = -1; persist() }
            )
        }
    }

    EditRecordDialog(editIdx, editName, editValue, unit, records,
        onEditNameChange = { editName = it },
        onEditValueChange = { editValue = it },
        onDismiss = { editIdx = -1 },
        onSave = { idx, name, meters ->
            records[idx] = records[idx].copy(memo = name, value = meters)
            persist()
            Toast.makeText(context, "記録を修正しました", Toast.LENGTH_SHORT).show()
            editIdx = -1
        }
    )
}

@Composable
private fun ManualMeasureBody(
    type: MeasureType,
    records: MutableList<Record>,
    unit: DistUnit,
    memo: String,
    showList: Boolean,
    remeasureIndex: Int,
    editIdx: Int,
    editName: String,
    editValue: String,
    onMemoChange: (String) -> Unit,
    onShowListChange: (Boolean) -> Unit,
    onRemeasureChange: (Int) -> Unit,
    onEditIdxChange: (Int) -> Unit,
    onEditNameChange: (String) -> Unit,
    onEditValueChange: (String) -> Unit,
    onUnitChange: (DistUnit) -> Unit,
    onPersist: () -> Unit,
    onSwitchToBle: () -> Unit,
    onSwitchToTilt: () -> Unit,
    modifier: Modifier
) {
    val context = LocalContext.current
    var manualValue by remember { mutableStateOf("") }
    var manualValueB by remember { mutableStateOf("") }
    var currentMemo by remember(memo) { mutableStateOf(memo) }
    var currentUnit by remember(unit) { mutableStateOf(unit) }
    var currentShowList by remember(showList) { mutableStateOf(showList) }
    var currentRemeasure by remember(remeasureIndex) { mutableStateOf(remeasureIndex) }
    var currentEditIdx by remember(editIdx) { mutableStateOf(editIdx) }
    var currentEditName by remember(editName) { mutableStateOf(editName) }
    var currentEditValue by remember(editValue) { mutableStateOf(editValue) }

    Column(
        modifier = modifier.fillMaxSize().imePadding().padding(16.dp)
    ) {
        Box(
            modifier = Modifier.fillMaxWidth().background(Color(0xFFFFF3E0), RoundedCornerShape(8.dp)).padding(12.dp)
        ) {
            Text(
                "このデバイスはAR非対応です。下記の方法で計測できます。",
                color = Color(0xFFE65100), fontWeight = FontWeight.Bold, fontSize = 13.sp
            )
        }

        Spacer(Modifier.height(4.dp))

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            OutlinedButton(onClick = onSwitchToTilt, modifier = Modifier.weight(1f)) {
                Text("カメラ傾斜角", color = Color(0xFF6A1B9A),
                    fontWeight = FontWeight.Bold, fontSize = 12.sp)
            }
            OutlinedButton(onClick = onSwitchToBle, modifier = Modifier.weight(1f)) {
                Text("BT距離計", color = Color(0xFF1565C0),
                    fontWeight = FontWeight.Bold, fontSize = 12.sp)
            }
        }

        Spacer(Modifier.height(8.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp, Alignment.End)
        ) {
            Text("単位", color = PanelText, fontWeight = FontWeight.Bold, fontSize = 11.sp)
            DistUnit.entries.forEach { u ->
                val sel = currentUnit == u
                Box(
                    modifier = Modifier
                        .background(if (sel) Teal else Panel, RoundedCornerShape(6.dp))
                        .clickable { currentUnit = u; onUnitChange(u); saveDistUnit(context, u) }
                        .padding(horizontal = 10.dp, vertical = 3.dp)
                ) {
                    Text(u.label, color = if (sel) Color.White else PanelText, fontSize = 12.sp,
                        fontWeight = if (sel) FontWeight.Bold else FontWeight.Normal)
                }
            }
        }

        Spacer(Modifier.height(8.dp))

        OutlinedTextField(
            value = currentMemo,
            onValueChange = { currentMemo = it; onMemoChange(it) },
            placeholder = { Text("メモ（例：入口→レジ / 会議室）") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )

        Spacer(Modifier.height(8.dp))

        if (type == MeasureType.DISTANCE) {
            OutlinedTextField(
                value = manualValue,
                onValueChange = { manualValue = it.filter { c -> c.isDigit() || c == '.' } },
                placeholder = { Text("距離（${currentUnit.label}）") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(8.dp))
            Button(
                onClick = {
                    val meters = parseToMeters(manualValue, currentUnit)
                    if (meters != null) {
                        val name = currentMemo.ifBlank {
                            if (currentRemeasure in records.indices) records[currentRemeasure].memo
                            else "計測${records.size + 1}"
                        }
                        val rec = Record(MeasureType.DISTANCE, name, meters, "")
                        if (currentRemeasure in records.indices) records[currentRemeasure] = rec
                        else records.add(0, rec)
                        onPersist()
                        Toast.makeText(context, "記録: ${rec.display(currentUnit)}", Toast.LENGTH_SHORT).show()
                        manualValue = ""; currentMemo = ""; onMemoChange("")
                        currentRemeasure = -1; onRemeasureChange(-1)
                    } else {
                        Toast.makeText(context, "数値を正しく入力してください", Toast.LENGTH_SHORT).show()
                    }
                },
                colors = ButtonDefaults.buttonColors(containerColor = Teal),
                modifier = Modifier.fillMaxWidth()
            ) { Text("記録", fontWeight = FontWeight.Bold, fontSize = 16.sp) }
        } else {
            OutlinedTextField(
                value = manualValue,
                onValueChange = { manualValue = it.filter { c -> c.isDigit() || c == '.' } },
                placeholder = { Text("辺A（${currentUnit.label}）") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(4.dp))
            OutlinedTextField(
                value = manualValueB,
                onValueChange = { manualValueB = it.filter { c -> c.isDigit() || c == '.' } },
                placeholder = { Text("辺B（${currentUnit.label}）") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(8.dp))
            Button(
                onClick = {
                    val a = parseToMeters(manualValue, currentUnit)
                    val b = parseToMeters(manualValueB, currentUnit)
                    if (a != null && b != null) {
                        val name = currentMemo.ifBlank {
                            if (currentRemeasure in records.indices) records[currentRemeasure].memo
                            else "計測${records.size + 1}"
                        }
                        val rec = Record(MeasureType.AREA, name, a * b, "%.2f×%.2f".format(a, b))
                        if (currentRemeasure in records.indices) records[currentRemeasure] = rec
                        else records.add(0, rec)
                        onPersist()
                        Toast.makeText(context, "記録: ${rec.display(currentUnit)}", Toast.LENGTH_SHORT).show()
                        manualValue = ""; manualValueB = ""; currentMemo = ""; onMemoChange("")
                        currentRemeasure = -1; onRemeasureChange(-1)
                    } else {
                        Toast.makeText(context, "辺A・辺Bを正しく入力してください", Toast.LENGTH_SHORT).show()
                    }
                },
                colors = ButtonDefaults.buttonColors(containerColor = Teal),
                modifier = Modifier.fillMaxWidth()
            ) { Text("面積を記録", fontWeight = FontWeight.Bold, fontSize = 16.sp) }
        }

        Spacer(Modifier.height(12.dp))

        RecordListUI(records, currentUnit, currentShowList, currentRemeasure, currentMemo, currentEditIdx,
            onShowListChange = { currentShowList = it; onShowListChange(it) },
            onRemeasureChange = { currentRemeasure = it; onRemeasureChange(it)
                if (it >= 0 && it in records.indices) { currentMemo = records[it].memo; onMemoChange(records[it].memo) }
            },
            onMemoUpdate = { idx ->
                val cur = records[idx]
                records[idx] = cur.copy(memo = currentMemo.ifBlank { cur.memo })
                onPersist()
                Toast.makeText(context, "メモ更新", Toast.LENGTH_SHORT).show()
            },
            onEditStart = { i ->
                currentEditIdx = i; onEditIdxChange(i)
                currentEditName = records[i].memo; onEditNameChange(records[i].memo)
                val v = valueForEdit(records[i].value, currentUnit)
                currentEditValue = v; onEditValueChange(v)
            },
            onDelete = { i ->
                records.removeAt(i)
                if (currentRemeasure == i) { currentRemeasure = -1; onRemeasureChange(-1) }
                onPersist()
            },
            onCopy = { copyText(context, buildReport(records, currentUnit)) },
            onShare = { shareText(context, buildReport(records, currentUnit)) },
            onClearAll = { records.clear(); currentRemeasure = -1; onRemeasureChange(-1); onPersist() }
        )
    }

    EditRecordDialog(currentEditIdx, currentEditName, currentEditValue, currentUnit, records,
        onEditNameChange = { currentEditName = it; onEditNameChange(it) },
        onEditValueChange = { currentEditValue = it; onEditValueChange(it) },
        onDismiss = { currentEditIdx = -1; onEditIdxChange(-1) },
        onSave = { idx, name, meters ->
            records[idx] = records[idx].copy(memo = name, value = meters)
            onPersist()
            Toast.makeText(context, "記録を修正しました", Toast.LENGTH_SHORT).show()
            currentEditIdx = -1; onEditIdxChange(-1)
        }
    )
}

@Composable
private fun TiltMeasureBody(
    type: MeasureType,
    records: MutableList<Record>,
    unit: DistUnit,
    memo: String,
    showList: Boolean,
    remeasureIndex: Int,
    editIdx: Int,
    editName: String,
    editValue: String,
    onMemoChange: (String) -> Unit,
    onShowListChange: (Boolean) -> Unit,
    onRemeasureChange: (Int) -> Unit,
    onEditIdxChange: (Int) -> Unit,
    onEditNameChange: (String) -> Unit,
    onEditValueChange: (String) -> Unit,
    onUnitChange: (DistUnit) -> Unit,
    onPersist: () -> Unit,
    onSwitchToManual: () -> Unit,
    modifier: Modifier
) {
    val context = LocalContext.current
    val lifecycleOwner = context as LifecycleOwner
    var cameraHeight by remember { mutableFloatStateOf(loadCameraHeight(context)) }
    var showHeightDialog by remember { mutableStateOf(cameraHeight <= 0f) }
    var heightInput by remember { mutableStateOf(if (cameraHeight > 0f) "%.2f".format(cameraHeight) else "1.20") }
    var pitchDeg by remember { mutableFloatStateOf(0f) }
    var sensorAvailable by remember { mutableStateOf(true) }

    var currentMemo by remember(memo) { mutableStateOf(memo) }
    var currentUnit by remember(unit) { mutableStateOf(unit) }
    var currentShowList by remember(showList) { mutableStateOf(showList) }
    var currentRemeasure by remember(remeasureIndex) { mutableStateOf(remeasureIndex) }
    var currentEditIdx by remember(editIdx) { mutableStateOf(editIdx) }
    var currentEditName by remember(editName) { mutableStateOf(editName) }
    var currentEditValue by remember(editValue) { mutableStateOf(editValue) }
    var capturedA by remember { mutableStateOf("") }
    var capturedB by remember { mutableStateOf("") }

    val angleBelowHorizontal = 90f + pitchDeg
    val liveDistance = if (angleBelowHorizontal > 5f && cameraHeight > 0f)
        (cameraHeight / tan(Math.toRadians(angleBelowHorizontal.toDouble()))).toFloat()
    else null

    DisposableEffect(Unit) {
        val sm = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
        val sensor = sm.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
            ?: sm.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR)
        if (sensor == null) { sensorAvailable = false; return@DisposableEffect onDispose {} }
        val rotMatrix = FloatArray(9)
        val orient = FloatArray(3)
        val listener = object : SensorEventListener {
            override fun onSensorChanged(e: SensorEvent) {
                SensorManager.getRotationMatrixFromVector(rotMatrix, e.values)
                SensorManager.getOrientation(rotMatrix, orient)
                pitchDeg = Math.toDegrees(orient[1].toDouble()).toFloat()
            }
            override fun onAccuracyChanged(s: Sensor, a: Int) {}
        }
        sm.registerListener(listener, sensor, SensorManager.SENSOR_DELAY_UI)
        onDispose { sm.unregisterListener(listener) }
    }

    fun recordDist(meters: Float) {
        val name = currentMemo.ifBlank {
            if (currentRemeasure in records.indices) records[currentRemeasure].memo
            else "計測${records.size + 1}"
        }
        val rec = Record(MeasureType.DISTANCE, name, meters, "")
        if (currentRemeasure in records.indices) records[currentRemeasure] = rec
        else records.add(0, rec)
        onPersist()
        Toast.makeText(context, "記録: ${rec.display(currentUnit)}", Toast.LENGTH_SHORT).show()
        currentMemo = ""; onMemoChange(""); currentRemeasure = -1; onRemeasureChange(-1)
    }

    fun recordArea(a: Float, b: Float) {
        val name = currentMemo.ifBlank {
            if (currentRemeasure in records.indices) records[currentRemeasure].memo
            else "計測${records.size + 1}"
        }
        val rec = Record(MeasureType.AREA, name, a * b, "%.2f×%.2f".format(a, b))
        if (currentRemeasure in records.indices) records[currentRemeasure] = rec
        else records.add(0, rec)
        onPersist()
        Toast.makeText(context, "記録: ${rec.display(currentUnit)}", Toast.LENGTH_SHORT).show()
        capturedA = ""; capturedB = ""
        currentMemo = ""; onMemoChange(""); currentRemeasure = -1; onRemeasureChange(-1)
    }

    if (showHeightDialog) {
        AlertDialog(
            onDismissRequest = { if (cameraHeight > 0f) showHeightDialog = false },
            title = { Text("カメラの高さ設定") },
            text = {
                Column {
                    Text("端末を持つ高さ（地面からカメラまで）をメートルで入力してください。",
                        fontSize = 13.sp, color = PanelText)
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = heightInput,
                        onValueChange = { heightInput = it.filter { c -> c.isDigit() || c == '.' } },
                        placeholder = { Text("例: 1.20") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        modifier = Modifier.fillMaxWidth()
                    )
                    Text("※ 胸の高さで持つ場合: 約1.0〜1.3m", fontSize = 11.sp, color = Color(0xFF70767C))
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val h = heightInput.toFloatOrNull()
                    if (h != null && h > 0f && h < 3f) {
                        cameraHeight = h; saveCameraHeight(context, h); showHeightDialog = false
                    } else {
                        Toast.makeText(context, "0.1〜3.0の範囲で入力してください", Toast.LENGTH_SHORT).show()
                    }
                }) { Text("設定") }
            },
            dismissButton = {
                if (cameraHeight > 0f) TextButton(onClick = { showHeightDialog = false }) { Text("取消") }
            }
        )
    }

    if (!sensorAvailable) {
        Column(modifier = modifier.fillMaxSize().padding(16.dp),
            verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
            Text("このデバイスには傾斜センサーがありません。", color = Color(0xFFD32F2F),
                fontWeight = FontWeight.Bold, fontSize = 14.sp)
            Spacer(Modifier.height(12.dp))
            Button(onClick = onSwitchToManual) { Text("手動入力に切替") }
        }
        return
    }

    val previewView = remember { PreviewView(context) }

    DisposableEffect(lifecycleOwner) {
        val future = ProcessCameraProvider.getInstance(context)
        future.addListener({
            try {
                val cp = future.get()
                val preview = Preview.Builder().build().apply {
                    setSurfaceProvider(previewView.surfaceProvider)
                }
                cp.unbindAll()
                cp.bindToLifecycle(lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, preview)
            } catch (_: Exception) {}
        }, ContextCompat.getMainExecutor(context))
        onDispose { try { future.get().unbindAll() } catch (_: Exception) {} }
    }

    Box(modifier = modifier.fillMaxSize()) {
        AndroidView(factory = { previewView }, modifier = Modifier.fillMaxSize())

        Canvas(modifier = Modifier.fillMaxSize()) {
            val center = Offset(size.width / 2f, size.height / 2f)
            drawCircle(color = Color.White, radius = 15f, center = center, style = Stroke(width = 4f))
            drawCircle(color = Color(0xFF6A1B9A), radius = 5f, center = center)
            drawLine(Color.White.copy(alpha = 0.5f),
                Offset(center.x - 30f, center.y), Offset(center.x + 30f, center.y), strokeWidth = 2f)
            drawLine(Color.White.copy(alpha = 0.5f),
                Offset(center.x, center.y - 30f), Offset(center.x, center.y + 30f), strokeWidth = 2f)
        }

        if (liveDistance != null && liveDistance in 0.01f..50f) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
                Box(modifier = Modifier.padding(top = 104.dp)
                    .background(Panel, RoundedCornerShape(10.dp))
                    .padding(horizontal = 16.dp, vertical = 6.dp)) {
                    Text(formatDistance(liveDistance, currentUnit), color = Color(0xFF6A1B9A),
                        fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace, fontSize = 34.sp)
                }
            }
        }

        Column(modifier = Modifier.fillMaxSize().imePadding().padding(12.dp)) {
            Box(modifier = Modifier.fillMaxWidth().background(Panel, RoundedCornerShape(8.dp)).padding(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("カメラ傾斜角方式", color = Color(0xFF6A1B9A),
                            fontWeight = FontWeight.Bold, fontSize = 12.sp)
                        Text("高さ: %.2fm | 角度: %.0f°".format(cameraHeight, angleBelowHorizontal),
                            fontSize = 11.sp, color = PanelText)
                    }
                    TextButton(onClick = {
                        heightInput = "%.2f".format(cameraHeight); showHeightDialog = true
                    }) { Text("高さ変更", fontSize = 11.sp, color = Color(0xFF6A1B9A)) }
                    TextButton(onClick = onSwitchToManual) {
                        Text("手動入力", fontSize = 11.sp, color = Color(0xFFE65100))
                    }
                }
            }

            if (liveDistance == null || liveDistance !in 0.01f..50f) {
                Spacer(Modifier.height(4.dp))
                Box(modifier = Modifier.fillMaxWidth().background(Color(0xFFFFF3E0), RoundedCornerShape(6.dp)).padding(8.dp)) {
                    Text("床面の計測点に照準を合わせてください（有効: 〜5m）",
                        color = Color(0xFFE65100), fontSize = 12.sp, fontWeight = FontWeight.Bold)
                }
            }

            Spacer(Modifier.weight(1f))

            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp, Alignment.End)) {
                Text("単位", color = PanelText, fontWeight = FontWeight.Bold, fontSize = 11.sp)
                DistUnit.entries.forEach { u ->
                    val sel = currentUnit == u
                    Box(modifier = Modifier.background(if (sel) Teal else Panel, RoundedCornerShape(6.dp))
                        .clickable { currentUnit = u; onUnitChange(u); saveDistUnit(context, u) }
                        .padding(horizontal = 10.dp, vertical = 3.dp)) {
                        Text(u.label, color = if (sel) Color.White else PanelText, fontSize = 12.sp,
                            fontWeight = if (sel) FontWeight.Bold else FontWeight.Normal)
                    }
                }
            }

            Spacer(Modifier.height(4.dp))

            OutlinedTextField(value = currentMemo,
                onValueChange = { currentMemo = it; onMemoChange(it) },
                placeholder = { Text("メモ（例：入口→レジ / 会議室）") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().background(Panel, RoundedCornerShape(8.dp)))

            Spacer(Modifier.height(8.dp))

            if (type == MeasureType.DISTANCE) {
                Button(
                    onClick = { if (liveDistance != null) recordDist(liveDistance) },
                    enabled = liveDistance != null && liveDistance in 0.01f..50f,
                    colors = ButtonDefaults.buttonColors(containerColor = Teal),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(if (liveDistance != null && liveDistance in 0.01f..50f)
                        "この距離を記録（${formatDistance(liveDistance, currentUnit)}）"
                    else "照準を床面に合わせてください",
                        fontWeight = FontWeight.Bold, fontSize = 14.sp)
                }
            } else {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    OutlinedTextField(value = capturedA, onValueChange = {},
                        placeholder = { Text("辺A") }, singleLine = true, readOnly = true,
                        modifier = Modifier.weight(1f).background(Panel, RoundedCornerShape(8.dp)))
                    Button(
                        onClick = { if (liveDistance != null) capturedA = "%.3f".format(liveDistance) },
                        enabled = liveDistance != null && liveDistance in 0.01f..50f,
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF6A1B9A))
                    ) { Text("取得") }
                }
                Spacer(Modifier.height(4.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    OutlinedTextField(value = capturedB, onValueChange = {},
                        placeholder = { Text("辺B") }, singleLine = true, readOnly = true,
                        modifier = Modifier.weight(1f).background(Panel, RoundedCornerShape(8.dp)))
                    Button(
                        onClick = { if (liveDistance != null) capturedB = "%.3f".format(liveDistance) },
                        enabled = liveDistance != null && liveDistance in 0.01f..50f,
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF6A1B9A))
                    ) { Text("取得") }
                }
                Spacer(Modifier.height(8.dp))
                Button(
                    onClick = {
                        val a = capturedA.toFloatOrNull(); val b = capturedB.toFloatOrNull()
                        if (a != null && b != null) recordArea(a, b)
                    },
                    enabled = capturedA.isNotEmpty() && capturedB.isNotEmpty(),
                    colors = ButtonDefaults.buttonColors(containerColor = Teal),
                    modifier = Modifier.fillMaxWidth()
                ) { Text("面積を記録", fontWeight = FontWeight.Bold, fontSize = 16.sp) }
            }

            Spacer(Modifier.height(6.dp))

            RecordListUI(records, currentUnit, currentShowList, currentRemeasure, currentMemo, currentEditIdx,
                onShowListChange = { currentShowList = it; onShowListChange(it) },
                onRemeasureChange = { currentRemeasure = it; onRemeasureChange(it)
                    if (it >= 0 && it in records.indices) { currentMemo = records[it].memo; onMemoChange(records[it].memo) }
                },
                onMemoUpdate = { idx ->
                    val cur = records[idx]
                    records[idx] = cur.copy(memo = currentMemo.ifBlank { cur.memo })
                    onPersist(); Toast.makeText(context, "メモ更新", Toast.LENGTH_SHORT).show()
                },
                onEditStart = { i ->
                    currentEditIdx = i; onEditIdxChange(i)
                    currentEditName = records[i].memo; onEditNameChange(records[i].memo)
                    val v = valueForEdit(records[i].value, currentUnit)
                    currentEditValue = v; onEditValueChange(v)
                },
                onDelete = { i ->
                    records.removeAt(i)
                    if (currentRemeasure == i) { currentRemeasure = -1; onRemeasureChange(-1) }
                    onPersist()
                },
                onCopy = { copyText(context, buildReport(records, currentUnit)) },
                onShare = { shareText(context, buildReport(records, currentUnit)) },
                onClearAll = { records.clear(); currentRemeasure = -1; onRemeasureChange(-1); onPersist() }
            )
        }
    }

    EditRecordDialog(currentEditIdx, currentEditName, currentEditValue, currentUnit, records,
        onEditNameChange = { currentEditName = it; onEditNameChange(it) },
        onEditValueChange = { currentEditValue = it; onEditValueChange(it) },
        onDismiss = { currentEditIdx = -1; onEditIdxChange(-1) },
        onSave = { idx, name, meters ->
            records[idx] = records[idx].copy(memo = name, value = meters)
            onPersist()
            Toast.makeText(context, "記録を修正しました", Toast.LENGTH_SHORT).show()
            currentEditIdx = -1; onEditIdxChange(-1)
        }
    )
}

@Composable
private fun BleMeasureBody(
    type: MeasureType,
    records: MutableList<Record>,
    unit: DistUnit,
    memo: String,
    showList: Boolean,
    remeasureIndex: Int,
    editIdx: Int,
    editName: String,
    editValue: String,
    onMemoChange: (String) -> Unit,
    onShowListChange: (Boolean) -> Unit,
    onRemeasureChange: (Int) -> Unit,
    onEditIdxChange: (Int) -> Unit,
    onEditNameChange: (String) -> Unit,
    onEditValueChange: (String) -> Unit,
    onUnitChange: (DistUnit) -> Unit,
    onPersist: () -> Unit,
    onSwitchToManual: () -> Unit,
    modifier: Modifier
) {
    val context = LocalContext.current
    val bleMeter = remember { BleDistanceMeter(context) }
    val bleState by bleMeter.state.collectAsState()
    val bleDevices by bleMeter.devices.collectAsState()
    val lastValue by bleMeter.lastValue.collectAsState()
    val connectedName by bleMeter.connectedName.collectAsState()

    DisposableEffect(Unit) { onDispose { bleMeter.disconnect() } }

    var blePermGranted by remember { mutableStateOf(false) }
    val blePerms = if (Build.VERSION.SDK_INT >= 31)
        arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
    else
        arrayOf(Manifest.permission.BLUETOOTH, Manifest.permission.ACCESS_FINE_LOCATION)
    val permLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results -> blePermGranted = results.values.all { it } }

    LaunchedEffect(Unit) {
        blePermGranted = blePerms.all {
            context.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED
        }
        if (!blePermGranted) permLauncher.launch(blePerms)
    }

    var currentMemo by remember(memo) { mutableStateOf(memo) }
    var currentUnit by remember(unit) { mutableStateOf(unit) }
    var currentShowList by remember(showList) { mutableStateOf(showList) }
    var currentRemeasure by remember(remeasureIndex) { mutableStateOf(remeasureIndex) }
    var currentEditIdx by remember(editIdx) { mutableStateOf(editIdx) }
    var currentEditName by remember(editName) { mutableStateOf(editName) }
    var currentEditValue by remember(editValue) { mutableStateOf(editValue) }

    var capturedA by remember { mutableStateOf("") }
    var capturedB by remember { mutableStateOf("") }

    fun recordDistance(distMeters: Float) {
        val name = currentMemo.ifBlank {
            if (currentRemeasure in records.indices) records[currentRemeasure].memo
            else "計測${records.size + 1}"
        }
        val rec = Record(MeasureType.DISTANCE, name, distMeters, "")
        if (currentRemeasure in records.indices) records[currentRemeasure] = rec
        else records.add(0, rec)
        onPersist()
        Toast.makeText(context, "記録: ${rec.display(currentUnit)}", Toast.LENGTH_SHORT).show()
        currentMemo = ""; onMemoChange("")
        currentRemeasure = -1; onRemeasureChange(-1)
    }

    fun recordArea(a: Float, b: Float) {
        val name = currentMemo.ifBlank {
            if (currentRemeasure in records.indices) records[currentRemeasure].memo
            else "計測${records.size + 1}"
        }
        val rec = Record(MeasureType.AREA, name, a * b, "%.2f×%.2f".format(a, b))
        if (currentRemeasure in records.indices) records[currentRemeasure] = rec
        else records.add(0, rec)
        onPersist()
        Toast.makeText(context, "記録: ${rec.display(currentUnit)}", Toast.LENGTH_SHORT).show()
        capturedA = ""; capturedB = ""
        currentMemo = ""; onMemoChange("")
        currentRemeasure = -1; onRemeasureChange(-1)
    }

    Column(modifier = modifier.fillMaxSize().imePadding().padding(16.dp)) {
        Box(
            modifier = Modifier.fillMaxWidth()
                .background(Color(0xFFE3F2FD), RoundedCornerShape(8.dp)).padding(12.dp)
        ) {
            Column {
                Text("Bluetooth距離計モード", color = Color(0xFF1565C0),
                    fontWeight = FontWeight.Bold, fontSize = 14.sp)
                when (bleState) {
                    BleDistanceMeter.State.IDLE ->
                        Text("距離計をスキャンして接続してください", fontSize = 12.sp, color = Color(0xFF555555))
                    BleDistanceMeter.State.SCANNING ->
                        Text("スキャン中…", fontSize = 12.sp, color = Color(0xFF1565C0))
                    BleDistanceMeter.State.CONNECTING ->
                        Text("${connectedName} に接続中…", fontSize = 12.sp, color = Color(0xFF1565C0))
                    BleDistanceMeter.State.CONNECTED ->
                        Text("接続済み: $connectedName", fontSize = 12.sp, color = Color(0xFF2E7D32))
                    BleDistanceMeter.State.ERROR ->
                        Text("エラー: Bluetoothを確認してください", fontSize = 12.sp, color = Color(0xFFD32F2F))
                }
            }
        }

        Spacer(Modifier.height(8.dp))

        when (bleState) {
            BleDistanceMeter.State.IDLE, BleDistanceMeter.State.ERROR -> {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    Button(
                        onClick = {
                            if (!blePermGranted) { permLauncher.launch(blePerms); return@Button }
                            bleMeter.startScan()
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1565C0)),
                        modifier = Modifier.weight(1f)
                    ) { Text("スキャン開始", fontWeight = FontWeight.Bold) }
                    OutlinedButton(onClick = onSwitchToManual, modifier = Modifier.weight(1f)) {
                        Text("手動入力に戻る", fontSize = 12.sp)
                    }
                }
            }
            BleDistanceMeter.State.SCANNING -> {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    Button(
                        onClick = { bleMeter.stopScan() },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF757575)),
                        modifier = Modifier.weight(1f)
                    ) { Text("スキャン停止") }
                    OutlinedButton(onClick = onSwitchToManual, modifier = Modifier.weight(1f)) {
                        Text("手動入力に戻る", fontSize = 12.sp)
                    }
                }
                Spacer(Modifier.height(4.dp))
                if (bleDevices.isEmpty()) {
                    Row(verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.padding(8.dp)) {
                        CircularProgressIndicator(modifier = Modifier.height(20.dp), strokeWidth = 2.dp)
                        Text("デバイスを検索中…", fontSize = 13.sp, color = Color(0xFF555555))
                    }
                } else {
                    Text("検出されたデバイス:", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = PanelText)
                    bleDevices.forEach { dev ->
                        Box(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)
                                .background(Panel, RoundedCornerShape(6.dp))
                                .clickable { bleMeter.connect(dev) }
                                .padding(10.dp)
                        ) {
                            Text("${dev.name}  (${dev.address})", fontSize = 13.sp, color = PanelText)
                        }
                    }
                }
            }
            BleDistanceMeter.State.CONNECTING -> {
                Row(verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.padding(8.dp)) {
                    CircularProgressIndicator(modifier = Modifier.height(20.dp), strokeWidth = 2.dp)
                    Text("接続中…", fontSize = 13.sp, color = Color(0xFF1565C0))
                }
            }
            BleDistanceMeter.State.CONNECTED -> {
                OutlinedButton(onClick = { bleMeter.disconnect() }, modifier = Modifier.fillMaxWidth()) {
                    Text("切断する", color = Color(0xFFD32F2F), fontSize = 12.sp)
                }

                Spacer(Modifier.height(8.dp))

                Box(
                    modifier = Modifier.fillMaxWidth()
                        .background(Color(0xFFF1F8E9), RoundedCornerShape(8.dp)).padding(16.dp),
                    contentAlignment = Alignment.Center
                ) {
                    if (lastValue != null) {
                        Text(
                            formatDistance(lastValue!!, currentUnit),
                            color = TealDark, fontWeight = FontWeight.Bold,
                            fontFamily = FontFamily.Monospace, fontSize = 36.sp
                        )
                    } else {
                        Text("距離計で計測してください", fontSize = 14.sp, color = Color(0xFF555555))
                    }
                }
            }
        }

        Spacer(Modifier.height(8.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp, Alignment.End)
        ) {
            Text("単位", color = PanelText, fontWeight = FontWeight.Bold, fontSize = 11.sp)
            DistUnit.entries.forEach { u ->
                val sel = currentUnit == u
                Box(
                    modifier = Modifier
                        .background(if (sel) Teal else Panel, RoundedCornerShape(6.dp))
                        .clickable { currentUnit = u; onUnitChange(u); saveDistUnit(context, u) }
                        .padding(horizontal = 10.dp, vertical = 3.dp)
                ) {
                    Text(u.label, color = if (sel) Color.White else PanelText, fontSize = 12.sp,
                        fontWeight = if (sel) FontWeight.Bold else FontWeight.Normal)
                }
            }
        }

        Spacer(Modifier.height(8.dp))

        OutlinedTextField(
            value = currentMemo,
            onValueChange = { currentMemo = it; onMemoChange(it) },
            placeholder = { Text("メモ（例：入口→レジ / 会議室）") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )

        Spacer(Modifier.height(8.dp))

        if (type == MeasureType.DISTANCE) {
            Button(
                onClick = {
                    if (lastValue != null) recordDistance(lastValue!!)
                    else Toast.makeText(context, "距離計で計測してください", Toast.LENGTH_SHORT).show()
                },
                enabled = bleState == BleDistanceMeter.State.CONNECTED && lastValue != null,
                colors = ButtonDefaults.buttonColors(containerColor = Teal),
                modifier = Modifier.fillMaxWidth()
            ) { Text("この距離を記録", fontWeight = FontWeight.Bold, fontSize = 16.sp) }
        } else {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                OutlinedTextField(
                    value = capturedA,
                    onValueChange = { capturedA = it.filter { c -> c.isDigit() || c == '.' } },
                    placeholder = { Text("辺A") },
                    singleLine = true,
                    readOnly = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.weight(1f)
                )
                Button(
                    onClick = { if (lastValue != null) capturedA = "%.3f".format(lastValue!!) },
                    enabled = lastValue != null,
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1565C0)),
                ) { Text("取得") }
            }
            Spacer(Modifier.height(4.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                OutlinedTextField(
                    value = capturedB,
                    onValueChange = { capturedB = it.filter { c -> c.isDigit() || c == '.' } },
                    placeholder = { Text("辺B") },
                    singleLine = true,
                    readOnly = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.weight(1f)
                )
                Button(
                    onClick = { if (lastValue != null) capturedB = "%.3f".format(lastValue!!) },
                    enabled = lastValue != null,
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1565C0)),
                ) { Text("取得") }
            }
            Spacer(Modifier.height(8.dp))
            Button(
                onClick = {
                    val a = capturedA.toFloatOrNull()
                    val b = capturedB.toFloatOrNull()
                    if (a != null && b != null) recordArea(a, b)
                    else Toast.makeText(context, "辺Aと辺Bを取得してください", Toast.LENGTH_SHORT).show()
                },
                enabled = capturedA.isNotEmpty() && capturedB.isNotEmpty(),
                colors = ButtonDefaults.buttonColors(containerColor = Teal),
                modifier = Modifier.fillMaxWidth()
            ) { Text("面積を記録", fontWeight = FontWeight.Bold, fontSize = 16.sp) }
        }

        Spacer(Modifier.height(12.dp))

        RecordListUI(records, currentUnit, currentShowList, currentRemeasure, currentMemo, currentEditIdx,
            onShowListChange = { currentShowList = it; onShowListChange(it) },
            onRemeasureChange = { currentRemeasure = it; onRemeasureChange(it)
                if (it >= 0 && it in records.indices) { currentMemo = records[it].memo; onMemoChange(records[it].memo) }
            },
            onMemoUpdate = { idx ->
                val cur = records[idx]
                records[idx] = cur.copy(memo = currentMemo.ifBlank { cur.memo })
                onPersist()
                Toast.makeText(context, "メモ更新", Toast.LENGTH_SHORT).show()
            },
            onEditStart = { i ->
                currentEditIdx = i; onEditIdxChange(i)
                currentEditName = records[i].memo; onEditNameChange(records[i].memo)
                val v = valueForEdit(records[i].value, currentUnit)
                currentEditValue = v; onEditValueChange(v)
            },
            onDelete = { i ->
                records.removeAt(i)
                if (currentRemeasure == i) { currentRemeasure = -1; onRemeasureChange(-1) }
                onPersist()
            },
            onCopy = { copyText(context, buildReport(records, currentUnit)) },
            onShare = { shareText(context, buildReport(records, currentUnit)) },
            onClearAll = { records.clear(); currentRemeasure = -1; onRemeasureChange(-1); onPersist() }
        )
    }

    EditRecordDialog(currentEditIdx, currentEditName, currentEditValue, currentUnit, records,
        onEditNameChange = { currentEditName = it; onEditNameChange(it) },
        onEditValueChange = { currentEditValue = it; onEditValueChange(it) },
        onDismiss = { currentEditIdx = -1; onEditIdxChange(-1) },
        onSave = { idx, name, meters ->
            records[idx] = records[idx].copy(memo = name, value = meters)
            onPersist()
            Toast.makeText(context, "記録を修正しました", Toast.LENGTH_SHORT).show()
            currentEditIdx = -1; onEditIdxChange(-1)
        }
    )
}

@Composable
private fun RecordListUI(
    records: List<Record>,
    unit: DistUnit,
    showList: Boolean,
    remeasureIndex: Int,
    memo: String,
    editIdx: Int,
    onShowListChange: (Boolean) -> Unit,
    onRemeasureChange: (Int) -> Unit,
    onMemoUpdate: (Int) -> Unit,
    onEditStart: (Int) -> Unit,
    onDelete: (Int) -> Unit,
    onCopy: () -> Unit,
    onShare: () -> Unit,
    onClearAll: () -> Unit
) {
    if (records.isNotEmpty()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            TextButton(onClick = { onShowListChange(!showList) }) {
                Text("記録一覧 (${records.size}) ${if (showList) "▲" else "▼"}",
                    color = TealDark, fontWeight = FontWeight.Bold)
            }
            Spacer(Modifier.weight(1f))
            if (remeasureIndex in records.indices) {
                TextButton(onClick = { onMemoUpdate(remeasureIndex) }) {
                    Text("メモ更新", color = TealDark)
                }
            }
        }
    }

    if (records.isNotEmpty() && showList) {
        LazyColumn(
            modifier = Modifier.fillMaxWidth().heightIn(max = 200.dp)
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
                        .clickable { onRemeasureChange(if (isTarget) -1 else i) }
                        .padding(horizontal = 4.dp, vertical = 3.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("${i + 1}. ${item.memo}", color = PanelText, fontSize = 13.sp,
                        modifier = Modifier.weight(1f))
                    Text(item.display(unit), color = TealDark, fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold, fontSize = 13.sp)
                    TextButton(onClick = { onEditStart(i) }) {
                        Text("修正", color = TealDark, fontSize = 12.sp)
                    }
                    TextButton(onClick = { onDelete(i) }) {
                        Text("×", color = Color(0xFFC62828))
                    }
                }
            }
        }
        Spacer(Modifier.height(6.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = onCopy, modifier = Modifier.weight(1f)) { Text("コピー") }
            OutlinedButton(onClick = onShare, modifier = Modifier.weight(1f)) { Text("共有") }
            OutlinedButton(onClick = onClearAll, modifier = Modifier.weight(1f)) { Text("全消去") }
        }
    }
}

@Composable
private fun EditRecordDialog(
    editIdx: Int,
    editName: String,
    editValue: String,
    unit: DistUnit,
    records: List<Record>,
    onEditNameChange: (String) -> Unit,
    onEditValueChange: (String) -> Unit,
    onDismiss: () -> Unit,
    onSave: (Int, String, Float) -> Unit
) {
    val context = LocalContext.current
    if (editIdx in records.indices) {
        AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text("記録の修正") },
            text = {
                Column {
                    Text("名称", fontSize = 12.sp, color = PanelText)
                    OutlinedTextField(
                        value = editName,
                        onValueChange = onEditNameChange,
                        singleLine = true,
                        placeholder = { Text("名称（例：入口→レジ）") },
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.height(10.dp))
                    Text("距離（${unit.label}）", fontSize = 12.sp, color = PanelText)
                    OutlinedTextField(
                        value = editValue,
                        onValueChange = { input ->
                            onEditValueChange(input.filter { it.isDigit() || it == '.' })
                        },
                        singleLine = true,
                        placeholder = { Text(if (unit == DistUnit.CM) "例：235" else "例：2.35") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        modifier = Modifier.fillMaxWidth()
                    )
                    Text("※ 現在の単位（${unit.label}）で入力してください。",
                        fontSize = 10.sp, color = Color(0xFF70767C))
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val meters = parseToMeters(editValue, unit)
                    if (editIdx in records.indices && editName.isNotBlank() && meters != null) {
                        onSave(editIdx, editName, meters)
                    } else {
                        Toast.makeText(context, "名称と距離（数字）を正しく入力してください", Toast.LENGTH_SHORT).show()
                    }
                }) { Text("保存") }
            },
            dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
        )
    }
}

/** 距離(メートル)を、編集用に選択単位の数値文字列へ変換する。 */
private fun valueForEdit(meters: Float, unit: DistUnit): String = when (unit) {
    DistUnit.M -> "%.2f".format(meters)
    DistUnit.CM -> "%.0f".format(meters * 100f)
}

/** 選択単位で入力された数値文字列を、メートルへ変換する（不正なら null）。 */
private fun parseToMeters(input: String, unit: DistUnit): Float? {
    val v = input.trim().toFloatOrNull() ?: return null
    if (v < 0f) return null
    return when (unit) {
        DistUnit.M -> v
        DistUnit.CM -> v / 100f
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

private fun buildReport(records: List<Record>, unit: DistUnit): String {
    val sb = StringBuilder("測定結果\n")
    records.asReversed().forEachIndexed { i, r -> sb.append("${i + 1}. ${r.memo}: ${r.display(unit)}\n") }
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

/** 選択中の距離表示単位を保存／読み出し（タブ間で共通）。 */
fun saveDistUnit(context: Context, unit: DistUnit) {
    context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString("dist_unit", unit.name).apply()
}

fun loadDistUnit(context: Context): DistUnit {
    val s = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("dist_unit", DistUnit.M.name)
    return runCatching { DistUnit.valueOf(s!!) }.getOrDefault(DistUnit.M)
}

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

private fun saveCameraHeight(context: Context, height: Float) {
    context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
        .putFloat("camera_height", height).apply()
}

private fun loadCameraHeight(context: Context): Float {
    return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        .getFloat("camera_height", 0f)
}
