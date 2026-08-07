package com.tatsuya.idtool

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Typeface
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin

private enum class DTool { MARK, PEN, TEXT, DISTANCE, MOVE, DELETE }
private enum class PenMode { FREE, LINE, DASH, ARROW }

private val DeviceTypes = listOf("RST", "EST", "RPR", "EPR", "RMD", "EMD", "PC", "Router", "HUB")
private val ShapeTypes = listOf("正方形", "横長方形", "縦長方形", "丸")
private val Palette = listOf(0xFF000000L, 0xFFD32F2FL, 0xFF1565C0L, 0xFF2E7D32L, 0xFFEF6C00L, 0xFF6A1B9AL)

private fun typeColor(type: String): Long = when (type) {
    "RST" -> 0xFF1565C0
    "EST" -> 0xFF2E7D32
    "RPR" -> 0xFF6A1B9A
    "EPR" -> 0xFFEF6C00
    "RMD" -> 0xFF00838F
    "EMD" -> 0xFFC62828
    "PC" -> 0xFF3949AB
    "Router" -> 0xFF00796B
    "HUB" -> 0xFF5D4037
    else -> 0xFF546E7A
}

private fun isShape(type: String) = type in ShapeTypes

private const val NUM_MAIN = -1
private const val NUM_SUB = -2
private fun numLabel(n: Int): String = when (n) { NUM_MAIN -> "MAIN"; NUM_SUB -> "SUB"; else -> "$n" }
private fun isMainSub(n: Int) = n < 0
private fun numBoxColor(n: Int, type: String): Long = when (n) {
    NUM_MAIN -> 0xFFD50000
    NUM_SUB -> 0xFFFFAB00
    else -> typeColor(type)
}
private fun numTextColorInt(n: Int): Int = when (n) {
    NUM_SUB -> android.graphics.Color.BLACK
    else -> android.graphics.Color.WHITE
}

private fun shapeHalf(type: String, size: String): Pair<Float, Float> {
    val s = when (size) { "S" -> 0; "L" -> 2; "XL" -> 3; else -> 1 }
    return when (type) {
        "横長方形" -> listOf(Pair(40f, 22f), Pair(67f, 37f), Pair(104f, 54f), Pair(144f, 75f))[s]
        "縦長方形" -> listOf(Pair(22f, 40f), Pair(37f, 67f), Pair(54f, 104f), Pair(75f, 144f))[s]
        else -> listOf(Pair(22f, 22f), Pair(37f, 37f), Pair(58f, 58f), Pair(80f, 80f))[s]
    }
}

private data class MarkT(
    val x: Float, val y: Float, val type: String, val num: Int,
    val label: String = "", val size: String = "M",
    val customHW: Float = 0f, val customHH: Float = 0f
)
private fun markHalf(m: MarkT): Pair<Float, Float> =
    if (m.size == "FREE" && m.customHW > 0f) Pair(m.customHW, m.customHH)
    else shapeHalf(m.type, m.size)

private data class TextT(val x: Float, val y: Float, val s: String, val sizeF: Float, val colorL: Long, val boxed: Boolean)
private data class StrokeT(val pts: List<Offset>, val mode: PenMode, val colorL: Long)

@Composable
fun DrawScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current

    // ── ページ管理 ──
    val pageNames = remember { mutableStateListOf<String>() }
    var pageCount by remember { mutableIntStateOf(1) }
    var curPage by remember { mutableIntStateOf(0) }
    var showPageRename by remember { mutableStateOf(false) }
    var pageRenameIdx by remember { mutableIntStateOf(-1) }
    var pageRenameInput by remember { mutableStateOf("") }
    var showPageDelete by remember { mutableStateOf(false) }
    var pageDeleteIdx by remember { mutableIntStateOf(-1) }

    remember {
        val meta = loadPageMeta(context)
        pageCount = meta.first
        pageNames.clear(); pageNames.addAll(meta.second)
        0
    }

    val marks = remember { mutableStateListOf<MarkT>() }
    val texts = remember { mutableStateListOf<TextT>() }
    val strokes = remember { mutableStateListOf<StrokeT>() }
    val undoStack = remember { mutableStateListOf<Triple<List<MarkT>, List<TextT>, List<StrokeT>>>() }
    remember { loadDrawPage(context, curPage, marks, texts, strokes); 0 }

    var bg by remember { mutableStateOf(loadBitmapFile(drawBgFile(context, curPage))) }
    var tool by remember { mutableStateOf(DTool.MARK) }
    var penMode by remember { mutableStateOf(PenMode.FREE) }
    var selType by remember { mutableStateOf("RST") }
    var selNum by remember { mutableIntStateOf(1) }
    var selColor by remember { mutableStateOf(0xFF000000L) }
    var selTextSize by remember { mutableStateOf(36f) }
    var selSize by remember { mutableStateOf("M") }

    var live by remember { mutableStateOf<List<Offset>>(emptyList()) }
    var dragStart by remember { mutableStateOf(Offset.Zero) }
    var moveKind by remember { mutableStateOf<String?>(null) }
    var moveIdx by remember { mutableIntStateOf(-1) }
    var lastP by remember { mutableStateOf(Offset.Zero) }
    var selKind by remember { mutableStateOf<String?>(null) }
    var selIdx by remember { mutableIntStateOf(-1) }

    var freeCenter by remember { mutableStateOf<Offset?>(null) }
    var freeDragCur by remember { mutableStateOf(Offset.Zero) }

    var textPoint by remember { mutableStateOf<Offset?>(null) }
    var textInput by remember { mutableStateOf("") }
    var distPoint by remember { mutableStateOf<Offset?>(null) }
    var showClear by remember { mutableStateOf(false) }
    var nameIdx by remember { mutableIntStateOf(-1) }
    var nameInput by remember { mutableStateOf("") }

    var showPhotoChoice by remember { mutableStateOf(false) }
    var photoFile by remember { mutableStateOf<File?>(null) }

    val distance = remember { loadDistanceRecords(context) }
    val distUnit = remember { loadDistUnit(context) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri: Uri? ->
        if (uri != null) { bg = loadBitmap(context, uri); saveDrawBg(context, curPage, bg) }
    }
    val cameraLauncher = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        if (ok) photoFile?.let { bg = loadBitmapFile(it); saveDrawBg(context, curPage, bg) }
    }
    val launchCamera: () -> Unit = {
        val f = File(context.cacheDir, "capture_${System.currentTimeMillis()}.jpg")
        photoFile = f
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", f)
        cameraLauncher.launch(uri)
    }
    val camPerm = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) launchCamera() else Toast.makeText(context, "カメラ権限が必要です", Toast.LENGTH_SHORT).show()
    }

    fun persist() = saveDrawPage(context, curPage, marks, texts, strokes)

    fun pushUndo() {
        undoStack.add(Triple(marks.toList(), texts.toList(), strokes.toList()))
        if (undoStack.size > 40) undoStack.removeAt(0)
    }

    fun undo() {
        if (undoStack.isEmpty()) return
        val prev = undoStack.removeAt(undoStack.size - 1)
        marks.clear(); marks.addAll(prev.first)
        texts.clear(); texts.addAll(prev.second)
        strokes.clear(); strokes.addAll(prev.third)
        selKind = null; selIdx = -1
        persist()
    }

    fun deleteAt(kind: String, idx: Int) {
        pushUndo()
        when (kind) {
            "M" -> if (idx in marks.indices) marks.removeAt(idx)
            "T" -> if (idx in texts.indices) texts.removeAt(idx)
            "S" -> if (idx in strokes.indices) strokes.removeAt(idx)
        }
        selKind = null; selIdx = -1
        persist()
    }

    fun switchPage(newPage: Int) {
        if (newPage == curPage) return
        saveDrawPage(context, curPage, marks, texts, strokes)
        undoStack.clear()
        marks.clear(); texts.clear(); strokes.clear()
        curPage = newPage
        loadDrawPage(context, curPage, marks, texts, strokes)
        bg = loadBitmapFile(drawBgFile(context, curPage))
        selKind = null; selIdx = -1
    }

    fun addPage() {
        saveDrawPage(context, curPage, marks, texts, strokes)
        pageCount++
        pageNames.add("ページ$pageCount")
        savePageMeta(context, pageCount, pageNames)
        switchPage(pageCount - 1)
    }

    fun deletePage(idx: Int) {
        if (pageCount <= 1) return
        deleteDrawPage(context, idx)
        drawBgFile(context, idx).delete()
        for (i in idx + 1 until pageCount) {
            renameDrawPage(context, i, i - 1)
            val oldBg = drawBgFile(context, i)
            if (oldBg.exists()) oldBg.renameTo(drawBgFile(context, i - 1))
        }
        pageNames.removeAt(idx)
        pageCount--
        savePageMeta(context, pageCount, pageNames)
        val newCur = if (curPage >= pageCount) pageCount - 1 else if (curPage > idx) curPage - 1 else curPage
        marks.clear(); texts.clear(); strokes.clear(); undoStack.clear()
        curPage = newCur
        loadDrawPage(context, curPage, marks, texts, strokes)
        bg = loadBitmapFile(drawBgFile(context, curPage))
        selKind = null; selIdx = -1
    }

    Column(modifier = modifier.fillMaxSize().padding(8.dp)) {
        // ── ページタブ ──
        Row(
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            for (i in 0 until pageCount) {
                val isCurrent = i == curPage
                val tabName = pageNames.getOrElse(i) { "ページ${i + 1}" }
                Box(
                    modifier = Modifier
                        .background(
                            if (isCurrent) Color(0xFF1565C0) else Color(0xFFE0E0E0),
                            RoundedCornerShape(topStart = 6.dp, topEnd = 6.dp)
                        )
                        .clickable { switchPage(i) }
                        .padding(horizontal = 10.dp, vertical = 4.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            tabName,
                            color = if (isCurrent) Color.White else Color.Black,
                            fontSize = 12.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.clickable(onClick = {
                                pageRenameIdx = i
                                pageRenameInput = tabName
                                showPageRename = true
                            }).weight(1f, fill = false)
                        )
                        if (pageCount > 1) {
                            Spacer(Modifier.width(2.dp))
                            Icon(
                                Icons.Default.Close,
                                contentDescription = "削除",
                                tint = if (isCurrent) Color.White.copy(alpha = 0.7f) else Color.Gray,
                                modifier = Modifier
                                    .size(14.dp)
                                    .clickable { pageDeleteIdx = i; showPageDelete = true }
                            )
                        }
                    }
                }
            }
            IconButton(onClick = { addPage() }, modifier = Modifier.size(28.dp)) {
                Icon(Icons.Default.Add, contentDescription = "ページ追加", modifier = Modifier.size(18.dp))
            }
        }

        // ツール
        Row(modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            FilterChip(tool == DTool.MARK, { tool = DTool.MARK }, label = { Text("アイテム") })
            FilterChip(tool == DTool.PEN, { tool = DTool.PEN }, label = { Text("ペン") })
            FilterChip(tool == DTool.TEXT, { tool = DTool.TEXT }, label = { Text("文字") })
            FilterChip(tool == DTool.DISTANCE, { tool = DTool.DISTANCE }, label = { Text("距離") })
            FilterChip(tool == DTool.MOVE, { tool = DTool.MOVE }, label = { Text("移動") })
            FilterChip(tool == DTool.DELETE, { tool = DTool.DELETE }, label = { Text("削除") })
        }

        when (tool) {
            DTool.MARK -> {
                Row(modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    (DeviceTypes + ShapeTypes).forEach { t ->
                        FilterChip(selType == t, { selType = t }, label = { Text(t) })
                    }
                }
                if (isShape(selType)) {
                    Row(modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        FilterChip(selSize == "S", { selSize = "S" }, label = { Text("小") })
                        FilterChip(selSize == "M", { selSize = "M" }, label = { Text("中") })
                        FilterChip(selSize == "L", { selSize = "L" }, label = { Text("大") })
                        FilterChip(selSize == "XL", { selSize = "XL" }, label = { Text("特大") })
                    }
                } else {
                    Row(modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        FilterChip(selNum == NUM_MAIN, { selNum = NUM_MAIN }, label = { Text("MAIN") },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = Color(0xFFD50000), selectedLabelColor = Color.White,
                                containerColor = Color(0xFFFFCDD2), labelColor = Color(0xFFB71C1C)))
                        FilterChip(selNum == NUM_SUB, { selNum = NUM_SUB }, label = { Text("SUB") },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = Color(0xFFFFAB00), selectedLabelColor = Color.Black,
                                containerColor = Color(0xFFFFECB3), labelColor = Color(0xFFE65100)))
                        (1..10).forEach { n -> FilterChip(selNum == n, { selNum = n }, label = { Text("$n") }) }
                    }
                }
                Text(if (isShape(selType)) "タップで配置 / 長押しドラッグでフリーサイズ" else "長押しでアイテム名を登録できます", fontSize = 11.sp)
            }
            DTool.PEN -> {
                Row(modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    FilterChip(penMode == PenMode.FREE, { penMode = PenMode.FREE }, label = { Text("フリー") })
                    FilterChip(penMode == PenMode.LINE, { penMode = PenMode.LINE }, label = { Text("直線") })
                    FilterChip(penMode == PenMode.DASH, { penMode = PenMode.DASH }, label = { Text("点線") })
                    FilterChip(penMode == PenMode.ARROW, { penMode = PenMode.ARROW }, label = { Text("矢印") })
                }
                ColorPalette(selColor) { selColor = it }
            }
            DTool.TEXT -> {
                Row(modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    FilterChip(selTextSize == 26f, { selTextSize = 26f }, label = { Text("小") })
                    FilterChip(selTextSize == 36f, { selTextSize = 36f }, label = { Text("中") })
                    FilterChip(selTextSize == 50f, { selTextSize = 50f }, label = { Text("大") })
                }
                ColorPalette(selColor) { selColor = it }
            }
            DTool.DISTANCE -> Text("距離を入れたい場所をタップ（距離タブの記録から選択）", fontSize = 11.sp)
            DTool.MOVE -> Text("タップで選択（青くハイライト）→ドラッグで移動", fontSize = 11.sp)
            DTool.DELETE -> Text("消したいアイテム/線/文字をタップ", fontSize = 11.sp)
        }

        Spacer(Modifier.height(6.dp))

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .background(Color.White, RoundedCornerShape(4.dp))
                .border(1.dp, Color(0xFFBDBDBD), RoundedCornerShape(4.dp))
                .pointerInput(tool, penMode, selType, selNum, selColor, selTextSize, selSize, curPage) {
                    when {
                        tool == DTool.PEN -> detectDragGestures(
                            onDragStart = { off -> dragStart = norm(off, size.width, size.height); live = listOf(dragStart) },
                            onDrag = { ch, _ ->
                                val cur = norm(ch.position, size.width, size.height)
                                live = if (penMode == PenMode.FREE) live + cur else listOf(dragStart, cur)
                            },
                            onDragEnd = {
                                if (live.size >= 2) {
                                    pushUndo()
                                    val pts = if (penMode == PenMode.FREE) live else listOf(live.first(), live.last())
                                    strokes.add(StrokeT(pts, penMode, selColor)); persist()
                                }
                                live = emptyList()
                            }
                        )
                        tool == DTool.MOVE -> detectDragGestures(
                            onDragStart = { off ->
                                val p = norm(off, size.width, size.height)
                                val n = nearestKind(p, marks, texts, strokes)
                                moveKind = n?.first; moveIdx = n?.second ?: -1; lastP = p
                                selKind = n?.first; selIdx = n?.second ?: -1
                                if (moveKind != null) pushUndo()
                            },
                            onDrag = { ch, _ ->
                                val np = norm(ch.position, size.width, size.height)
                                applyMove(moveKind, moveIdx, np.x - lastP.x, np.y - lastP.y, marks, texts, strokes)
                                lastP = np
                            },
                            onDragEnd = { if (moveKind != null) persist(); moveKind = null; moveIdx = -1 }
                        )
                        tool == DTool.MARK && isShape(selType) -> detectDragGestures(
                            onDragStart = { off ->
                                dragStart = norm(off, size.width, size.height)
                                freeCenter = dragStart; freeDragCur = dragStart
                            },
                            onDrag = { ch, _ ->
                                if (freeCenter != null) freeDragCur = norm(ch.position, size.width, size.height)
                            },
                            onDragEnd = {
                                val center = freeCenter
                                if (center != null) {
                                    val hw = abs(freeDragCur.x - center.x) * size.width
                                    val hh = abs(freeDragCur.y - center.y) * size.height
                                    if (hw > 10f || hh > 10f) {
                                        pushUndo()
                                        marks.add(MarkT(center.x, center.y, selType, selNum, "", "FREE",
                                            customHW = max(hw, 12f), customHH = max(hh, 12f)))
                                        persist()
                                        nameIdx = marks.size - 1; nameInput = ""
                                    }
                                }
                                freeCenter = null
                            },
                            onDragCancel = { freeCenter = null }
                        )
                        else -> detectTapGestures(
                            onLongPress = { off ->
                                val p = norm(off, size.width, size.height)
                                val mi = nearestMark(p, marks)
                                if (mi >= 0) { nameIdx = mi; nameInput = marks[mi].label }
                            },
                            onTap = { off ->
                                val p = norm(off, size.width, size.height)
                                when (tool) {
                                    DTool.MARK -> {
                                        pushUndo()
                                        marks.add(MarkT(p.x, p.y, selType, selNum, "", selSize)); persist()
                                    }
                                    DTool.TEXT -> { textPoint = p; textInput = "" }
                                    DTool.DISTANCE -> { distPoint = p }
                                    DTool.DELETE -> {
                                        val n = nearestKind(p, marks, texts, strokes)
                                        if (n != null) deleteAt(n.first, n.second)
                                    }
                                    else -> {}
                                }
                            }
                        )
                    }
                }
                .pointerInput(tool, selType, selNum, selSize, curPage) {
                    if (tool == DTool.MARK && isShape(selType)) detectTapGestures(
                        onTap = { off ->
                            val p = norm(off, size.width, size.height)
                            pushUndo()
                            marks.add(MarkT(p.x, p.y, selType, selNum, "", selSize)); persist()
                        }
                    )
                }
                .pointerInput(tool, curPage) {
                    if (tool == DTool.MOVE) detectTapGestures(onTap = { off ->
                        val p = norm(off, size.width, size.height)
                        val n = nearestKind(p, marks, texts, strokes)
                        selKind = n?.first; selIdx = n?.second ?: -1
                    })
                }
        ) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                val w = size.width; val h = size.height
                bg?.let { b ->
                    drawImage(
                        image = b.asImageBitmap(),
                        srcOffset = IntOffset.Zero, srcSize = IntSize(b.width, b.height),
                        dstOffset = IntOffset.Zero, dstSize = IntSize(w.toInt(), h.toInt())
                    )
                }
                strokes.forEach { drawStroke(it, w, h) }
                if (live.size >= 2) drawStroke(StrokeT(live, penMode, selColor), w, h)
                marks.forEach { drawMark(it, w, h) }
                texts.forEach { drawTextItem(it, w, h) }
                if (tool == DTool.MOVE && selKind != null && selIdx >= 0) {
                    val hc: Offset? = when (selKind) {
                        "M" -> marks.getOrNull(selIdx)?.let { Offset(it.x * w, it.y * h) }
                        "T" -> texts.getOrNull(selIdx)?.let { Offset(it.x * w, it.y * h) }
                        "S" -> strokes.getOrNull(selIdx)?.let { s ->
                            Offset(s.pts.map { it.x }.average().toFloat() * w, s.pts.map { it.y }.average().toFloat() * h)
                        }
                        else -> null
                    }
                    hc?.let {
                        drawCircle(Color(0x332196F3), radius = 54f, center = it)
                        drawCircle(Color(0xFF2196F3), radius = 54f, center = it, style = Stroke(4f))
                    }
                }
                freeCenter?.let { center ->
                    val cx = center.x * w; val cy = center.y * h
                    val hw = abs(freeDragCur.x - center.x) * w
                    val hh = abs(freeDragCur.y - center.y) * h
                    val previewColor = Color(0xFF2196F3)
                    val dash = PathEffect.dashPathEffect(floatArrayOf(12f, 8f))
                    if (selType == "丸") {
                        val r = max(hw, hh)
                        drawCircle(previewColor, radius = r, center = Offset(cx, cy),
                            style = Stroke(3f, pathEffect = dash))
                    } else {
                        drawRect(previewColor, topLeft = Offset(cx - hw, cy - hh),
                            size = Size(hw * 2, hh * 2), style = Stroke(3f, pathEffect = dash))
                    }
                }
            }
        }

        Spacer(Modifier.height(6.dp))
        Row(modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            OutlinedButton(onClick = { showPhotoChoice = true }) { Text("写真取込") }
            OutlinedButton(onClick = { bg = null; drawBgFile(context, curPage).delete() }) { Text("背景白") }
            OutlinedButton(onClick = { undo() }, enabled = undoStack.isNotEmpty()) { Text("取消") }
            OutlinedButton(onClick = { showClear = true }) { Text("全消去") }
            OutlinedButton(onClick = {
                saveDrawPage(context, curPage, marks, texts, strokes)
                exportImage(context, curPage, marks, texts, strokes, bg, pdf = false)
            }) { Text("画像保存") }
            OutlinedButton(onClick = {
                saveDrawPage(context, curPage, marks, texts, strokes)
                exportAllPages(context, pageCount, pageNames)
            }) { Text("PDF保存") }
        }
    }

    if (textPoint != null) {
        AlertDialog(
            onDismissRequest = { textPoint = null },
            title = { Text("文字を入力") },
            text = { OutlinedTextField(value = textInput, onValueChange = { textInput = it }, singleLine = true) },
            confirmButton = {
                TextButton(onClick = {
                    val p = textPoint!!
                    if (textInput.isNotBlank()) { pushUndo(); texts.add(TextT(p.x, p.y, textInput, selTextSize, selColor, false)); persist() }
                    textPoint = null
                }) { Text("配置") }
            },
            dismissButton = { TextButton(onClick = { textPoint = null }) { Text("取消") } }
        )
    }

    if (distPoint != null) {
        AlertDialog(
            onDismissRequest = { distPoint = null },
            title = { Text("距離を挿入") },
            text = {
                if (distance.isEmpty()) Text("距離タブの記録がありません")
                else Column {
                    distance.forEach { r ->
                        TextButton(onClick = {
                            val p = distPoint!!
                            pushUndo()
                            texts.add(TextT(p.x, p.y, "${r.memo}\n${r.display(distUnit)}", 22f, 0xFF000000L, true)); persist()
                            distPoint = null
                        }) { Text("${r.memo}: ${r.display(distUnit)}") }
                    }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = { distPoint = null }) { Text("閉じる") } }
        )
    }

    if (nameIdx >= 0) {
        AlertDialog(
            onDismissRequest = { nameIdx = -1 },
            title = { Text("アイテム名を登録") },
            text = { OutlinedTextField(value = nameInput, onValueChange = { nameInput = it }, singleLine = true) },
            confirmButton = {
                TextButton(onClick = {
                    if (nameIdx in marks.indices) { pushUndo(); marks[nameIdx] = marks[nameIdx].copy(label = nameInput); persist() }
                    nameIdx = -1
                }) { Text("登録") }
            },
            dismissButton = { TextButton(onClick = { nameIdx = -1 }) { Text("取消") } }
        )
    }

    if (showPhotoChoice) {
        AlertDialog(
            onDismissRequest = { showPhotoChoice = false },
            title = { Text("写真の取り込み") },
            text = { Text("撮影するか、ファイルから選びます。") },
            confirmButton = {
                TextButton(onClick = {
                    showPhotoChoice = false
                    if (context.checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED)
                        launchCamera() else camPerm.launch(Manifest.permission.CAMERA)
                }) { Text("撮影") }
            },
            dismissButton = {
                TextButton(onClick = {
                    showPhotoChoice = false
                    picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                }) { Text("ファイルから") }
            }
        )
    }

    if (showClear) {
        AlertDialog(
            onDismissRequest = { showClear = false },
            title = { Text("全消去の確認") },
            text = { Text("配置したアイテム・線・文字をすべて消去します。よろしいですか？") },
            confirmButton = {
                TextButton(onClick = {
                    pushUndo(); marks.clear(); texts.clear(); strokes.clear(); selKind = null; selIdx = -1; persist(); showClear = false
                }) { Text("消去する") }
            },
            dismissButton = { TextButton(onClick = { showClear = false }) { Text("やめる") } }
        )
    }

    // ページ名変更ダイアログ
    if (showPageRename) {
        AlertDialog(
            onDismissRequest = { showPageRename = false },
            title = { Text("ページ名を変更") },
            text = { OutlinedTextField(value = pageRenameInput, onValueChange = { pageRenameInput = it }, singleLine = true) },
            confirmButton = {
                TextButton(onClick = {
                    if (pageRenameInput.isNotBlank() && pageRenameIdx in pageNames.indices) {
                        pageNames[pageRenameIdx] = pageRenameInput
                        savePageMeta(context, pageCount, pageNames)
                    }
                    showPageRename = false
                }) { Text("変更") }
            },
            dismissButton = { TextButton(onClick = { showPageRename = false }) { Text("取消") } }
        )
    }

    // ページ削除確認ダイアログ
    if (showPageDelete) {
        AlertDialog(
            onDismissRequest = { showPageDelete = false },
            title = { Text("ページ削除の確認") },
            text = {
                val name = pageNames.getOrElse(pageDeleteIdx) { "ページ${pageDeleteIdx + 1}" }
                Text("「$name」を削除します。この操作は元に戻せません。よろしいですか？")
            },
            confirmButton = {
                TextButton(onClick = {
                    showPageDelete = false
                    deletePage(pageDeleteIdx)
                }) { Text("削除する") }
            },
            dismissButton = { TextButton(onClick = { showPageDelete = false }) { Text("やめる") } }
        )
    }
}

@Composable
private fun ColorPalette(sel: Long, onPick: (Long) -> Unit) {
    Row(modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(top = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Palette.forEach { c ->
            Box(modifier = Modifier.size(28.dp).background(Color(c), CircleShape)
                .border(if (sel == c) 3.dp else 1.dp,
                    if (sel == c) Color(0xFF111111) else Color(0xFFBBBBBB), CircleShape)
                .clickable { onPick(c) })
        }
    }
}

private fun norm(off: Offset, w: Int, h: Int) =
    Offset(if (w > 0) off.x / w else 0f, if (h > 0) off.y / h else 0f)

private fun d2(a: Offset, b: Offset): Float { val dx = a.x - b.x; val dy = a.y - b.y; return dx * dx + dy * dy }

private fun nearestMark(p: Offset, marks: List<MarkT>): Int {
    var bi = -1; var bd = Float.MAX_VALUE
    marks.forEachIndexed { i, m -> val d = d2(p, Offset(m.x, m.y)); if (d < bd) { bd = d; bi = i } }
    return if (bd <= 0.08f * 0.08f) bi else -1
}

private fun nearestKind(p: Offset, marks: List<MarkT>, texts: List<TextT>, strokes: List<StrokeT>): Pair<String, Int>? {
    var best: Pair<String, Int>? = null; var bestD = Float.MAX_VALUE
    marks.forEachIndexed { i, m -> val d = d2(p, Offset(m.x, m.y)); if (d < bestD) { bestD = d; best = "M" to i } }
    texts.forEachIndexed { i, t -> val d = d2(p, Offset(t.x, t.y)); if (d < bestD) { bestD = d; best = "T" to i } }
    strokes.forEachIndexed { i, s ->
        val c = Offset(s.pts.map { it.x }.average().toFloat(), s.pts.map { it.y }.average().toFloat())
        val d = d2(p, c); if (d < bestD) { bestD = d; best = "S" to i }
    }
    return if (bestD <= 0.08f * 0.08f) best else null
}

private fun applyMove(
    kind: String?, idx: Int, dx: Float, dy: Float,
    marks: MutableList<MarkT>, texts: MutableList<TextT>, strokes: MutableList<StrokeT>
) {
    when (kind) {
        "M" -> if (idx in marks.indices) marks[idx] = marks[idx].copy(x = marks[idx].x + dx, y = marks[idx].y + dy)
        "T" -> if (idx in texts.indices) texts[idx] = texts[idx].copy(x = texts[idx].x + dx, y = texts[idx].y + dy)
        "S" -> if (idx in strokes.indices) strokes[idx] = strokes[idx].copy(pts = strokes[idx].pts.map { Offset(it.x + dx, it.y + dy) })
    }
}

private fun DrawScope.drawStroke(st: StrokeT, w: Float, h: Float) {
    val col = Color(st.colorL)
    val pts = st.pts.map { Offset(it.x * w, it.y * h) }
    if (pts.size < 2) return
    when (st.mode) {
        PenMode.FREE -> for (i in 0 until pts.size - 1) drawLine(col, pts[i], pts[i + 1], strokeWidth = 5f)
        PenMode.LINE -> drawLine(col, pts.first(), pts.last(), strokeWidth = 5f)
        PenMode.DASH -> drawLine(col, pts.first(), pts.last(), strokeWidth = 5f,
            pathEffect = PathEffect.dashPathEffect(floatArrayOf(20f, 14f)))
        PenMode.ARROW -> {
            val a = pts.first(); val b = pts.last()
            drawLine(col, a, b, strokeWidth = 5f)
            val ang = atan2((b.y - a.y).toDouble(), (b.x - a.x).toDouble()); val len = 26f
            listOf(ang + 2.6, ang - 2.6).forEach { s ->
                drawLine(col, b, Offset(b.x + (cos(s) * len).toFloat(), b.y + (sin(s) * len).toFloat()), strokeWidth = 5f)
            }
        }
    }
}

private fun DrawScope.drawMark(m: MarkT, w: Float, h: Float) {
    val cx = m.x * w; val cy = m.y * h
    val nc = drawContext.canvas.nativeCanvas
    if (isShape(m.type)) {
        val (hw, hh) = markHalf(m)
        if (m.type == "丸") {
            drawCircle(Color.White, radius = hw, center = Offset(cx, cy))
            drawCircle(Color(0xFF333333), radius = hw, center = Offset(cx, cy), style = Stroke(3f))
        } else {
            drawRect(Color.White, topLeft = Offset(cx - hw, cy - hh), size = Size(hw * 2, hh * 2))
            drawRect(Color(0xFF333333), topLeft = Offset(cx - hw, cy - hh), size = Size(hw * 2, hh * 2), style = Stroke(3f))
        }
        if (m.label.isNotBlank()) nc.drawText(m.label, cx, cy + 6f, Paint().apply {
            color = android.graphics.Color.BLACK; textSize = 21f; isAntiAlias = true
            typeface = Typeface.DEFAULT_BOLD; textAlign = Paint.Align.CENTER
        })
    } else {
        val ms = isMainSub(m.num)
        val halfW = if (ms) 35f else 19f
        val halfH = 19f
        drawRect(Color(numBoxColor(m.num, m.type)), topLeft = Offset(cx - halfW, cy - halfH), size = Size(halfW * 2, halfH * 2))
        nc.drawText(numLabel(m.num), cx, cy + 7f, Paint().apply {
            color = numTextColorInt(m.num); textSize = if (ms) 18f else 21f; isAntiAlias = true
            typeface = Typeface.DEFAULT_BOLD; textAlign = Paint.Align.CENTER
        })
        nc.drawText(m.type, cx, cy + halfH + 19f, Paint().apply {
            color = android.graphics.Color.BLACK; textSize = 18f; isAntiAlias = true
            typeface = Typeface.DEFAULT_BOLD; textAlign = Paint.Align.CENTER
        })
        if (m.label.isNotBlank()) nc.drawText(m.label, cx, cy + halfH + 38f, Paint().apply {
            color = android.graphics.Color.rgb(0x33, 0x33, 0x33); textSize = 18f; isAntiAlias = true
            textAlign = Paint.Align.CENTER
        })
    }
}

private fun DrawScope.drawTextItem(t: TextT, w: Float, h: Float) {
    val x = t.x * w; val y = t.y * h
    val nc = drawContext.canvas.nativeCanvas
    val paint = Paint().apply { color = t.colorL.toInt(); textSize = t.sizeF; isAntiAlias = true; typeface = Typeface.DEFAULT_BOLD }
    if (t.boxed) {
        val lines = t.s.split("\n"); val pad = 12f; val lineH = t.sizeF * 1.25f
        val maxW = lines.maxOf { paint.measureText(it) }
        val boxW = maxW + pad * 2; val boxH = lineH * lines.size + pad * 2
        val left = x - boxW / 2; val top = y - boxH / 2
        drawRect(Color.White, topLeft = Offset(left, top), size = Size(boxW, boxH))
        drawRect(Color(0xFF333333), topLeft = Offset(left, top), size = Size(boxW, boxH), style = Stroke(2f))
        paint.textAlign = Paint.Align.CENTER
        lines.forEachIndexed { i, ln -> nc.drawText(ln, x, top + pad + lineH * (i + 1) - lineH * 0.25f, paint) }
    } else nc.drawText(t.s, x, y, paint)
}

private fun loadBitmap(context: Context, uri: Uri): Bitmap? = try {
    context.contentResolver.openInputStream(uri)?.use {
        BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = 2 })
    }
} catch (e: Exception) { null }

private fun loadBitmapFile(file: File): Bitmap? = try {
    BitmapFactory.decodeFile(file.absolutePath, BitmapFactory.Options().apply { inSampleSize = 2 })
} catch (e: Exception) { null }

private fun stamp(): String = SimpleDateFormat("yyyyMMdd_HHmm", Locale.JAPAN).format(Date())

private fun renderToCanvas(
    c: android.graphics.Canvas, w: Int, h: Int,
    marks: List<MarkT>, texts: List<TextT>, strokes: List<StrokeT>, bg: Bitmap?
) {
    c.drawColor(android.graphics.Color.WHITE)
    bg?.let { c.drawBitmap(it, android.graphics.Rect(0, 0, it.width, it.height), android.graphics.Rect(0, 0, w, h), null) }
    strokes.forEach { st ->
        val p = Paint().apply {
            color = st.colorL.toInt(); strokeWidth = 6f; isAntiAlias = true
            if (st.mode == PenMode.DASH) pathEffect = DashPathEffect(floatArrayOf(24f, 16f), 0f)
        }
        val pts = st.pts.map { Offset(it.x * w, it.y * h) }
        if (pts.size >= 2) when (st.mode) {
            PenMode.FREE -> for (i in 0 until pts.size - 1) c.drawLine(pts[i].x, pts[i].y, pts[i + 1].x, pts[i + 1].y, p)
            else -> {
                c.drawLine(pts.first().x, pts.first().y, pts.last().x, pts.last().y, p)
                if (st.mode == PenMode.ARROW) {
                    val a = pts.first(); val b = pts.last()
                    val ang = atan2((b.y - a.y).toDouble(), (b.x - a.x).toDouble()); val len = 30f
                    val ph = Paint().apply { color = st.colorL.toInt(); strokeWidth = 6f; isAntiAlias = true }
                    listOf(ang + 2.6, ang - 2.6).forEach { s ->
                        c.drawLine(b.x, b.y, b.x + (cos(s) * len).toFloat(), b.y + (sin(s) * len).toFloat(), ph)
                    }
                }
            }
        }
    }
    marks.forEach { m ->
        val cx = m.x * w; val cy = m.y * h
        if (isShape(m.type)) {
            val (hw, hh) = markHalf(m)
            if (m.type == "丸") {
                c.drawCircle(cx, cy, hw, Paint().apply { color = android.graphics.Color.WHITE; isAntiAlias = true })
                c.drawCircle(cx, cy, hw, Paint().apply {
                    color = android.graphics.Color.rgb(0x33, 0x33, 0x33); style = Paint.Style.STROKE; strokeWidth = 3f; isAntiAlias = true
                })
            } else {
                c.drawRect(cx - hw, cy - hh, cx + hw, cy + hh, Paint().apply { color = android.graphics.Color.WHITE })
                c.drawRect(cx - hw, cy - hh, cx + hw, cy + hh, Paint().apply {
                    color = android.graphics.Color.rgb(0x33, 0x33, 0x33); style = Paint.Style.STROKE; strokeWidth = 3f
                })
            }
            if (m.label.isNotBlank()) c.drawText(m.label, cx, cy + 8f, Paint().apply {
                color = android.graphics.Color.BLACK; textSize = 26f; isAntiAlias = true
                typeface = Typeface.DEFAULT_BOLD; textAlign = Paint.Align.CENTER
            })
        } else {
            val ms = isMainSub(m.num)
            val halfW = if (ms) 48f else 26f
            val halfH = 26f
            c.drawRect(cx - halfW, cy - halfH, cx + halfW, cy + halfH, Paint().apply { color = numBoxColor(m.num, m.type).toInt(); isAntiAlias = true })
            c.drawText(numLabel(m.num), cx, cy + 10f, Paint().apply {
                color = numTextColorInt(m.num); textSize = if (ms) 26f else 30f; isAntiAlias = true
                typeface = Typeface.DEFAULT_BOLD; textAlign = Paint.Align.CENTER
            })
            c.drawText(m.type, cx, cy + halfH + 26f, Paint().apply {
                color = android.graphics.Color.BLACK; textSize = 24f; isAntiAlias = true
                typeface = Typeface.DEFAULT_BOLD; textAlign = Paint.Align.CENTER
            })
            if (m.label.isNotBlank()) c.drawText(m.label, cx, cy + halfH + 52f, Paint().apply {
                color = android.graphics.Color.rgb(0x33, 0x33, 0x33); textSize = 24f; isAntiAlias = true; textAlign = Paint.Align.CENTER
            })
        }
    }
    texts.forEach { t ->
        val x = t.x * w; val y = t.y * h
        val paint = Paint().apply { color = t.colorL.toInt(); textSize = t.sizeF; isAntiAlias = true; typeface = Typeface.DEFAULT_BOLD }
        if (t.boxed) {
            val lines = t.s.split("\n"); val pad = 14f; val lineH = t.sizeF * 1.25f
            val maxW = lines.maxOf { paint.measureText(it) }
            val boxW = maxW + pad * 2; val boxH = lineH * lines.size + pad * 2
            val left = x - boxW / 2; val top = y - boxH / 2
            c.drawRect(left, top, left + boxW, top + boxH, Paint().apply { color = android.graphics.Color.WHITE })
            c.drawRect(left, top, left + boxW, top + boxH, Paint().apply {
                color = android.graphics.Color.rgb(0x33, 0x33, 0x33); style = Paint.Style.STROKE; strokeWidth = 3f
            })
            paint.textAlign = Paint.Align.CENTER
            lines.forEachIndexed { i, ln -> c.drawText(ln, x, top + pad + lineH * (i + 1) - lineH * 0.25f, paint) }
        } else c.drawText(t.s, x, y, paint)
    }
}

private fun drawingHeaderInfo(context: Context): Pair<String, String> {
    val storeName = loadIdInfo(context).storeName
    val raw = context.getSharedPreferences("result_prefs", Context.MODE_PRIVATE).getString("data", "") ?: ""
    var date = ""
    raw.split("\n").forEach { line ->
        val i = line.indexOf('=')
        if (i > 0 && line.substring(0, i) == "日付") date = line.substring(i + 1)
    }
    if (date.isBlank()) date = SimpleDateFormat("yyyy/MM/dd", Locale.JAPAN).format(Date())
    return storeName to date
}

private fun drawHeader(c: android.graphics.Canvas, w: Int, headerH: Int, storeName: String, date: String, pageName: String = "") {
    val title = Paint().apply {
        color = android.graphics.Color.BLACK; textSize = 42f; isAntiAlias = true; typeface = Typeface.DEFAULT_BOLD
    }
    c.drawText("見取り図", 28f, 54f, title)
    if (pageName.isNotBlank()) {
        c.drawText("[$pageName]", 220f, 54f, Paint().apply {
            color = android.graphics.Color.rgb(0x15, 0x65, 0xC0); textSize = 32f; isAntiAlias = true; typeface = Typeface.DEFAULT_BOLD
        })
    }
    val info = Paint().apply {
        color = android.graphics.Color.rgb(0x33, 0x33, 0x33); textSize = 32f; isAntiAlias = true
    }
    c.drawText("店舗名：${storeName.ifBlank { "（未入力）" }}", 28f, 98f, info)
    val dateP = Paint().apply {
        color = android.graphics.Color.rgb(0x33, 0x33, 0x33); textSize = 32f; isAntiAlias = true
        textAlign = Paint.Align.RIGHT
    }
    c.drawText("日付：$date", w - 28f, 54f, dateP)
    c.drawLine(0f, headerH - 1f, w.toFloat(), headerH - 1f, Paint().apply {
        color = android.graphics.Color.rgb(0x99, 0x99, 0x99); strokeWidth = 2f
    })
}

private fun renderPage(
    c: android.graphics.Canvas, w: Int, totalH: Int, headerH: Int,
    marks: List<MarkT>, texts: List<TextT>, strokes: List<StrokeT>, bg: Bitmap?,
    storeName: String, date: String, pageName: String = ""
) {
    c.drawColor(android.graphics.Color.WHITE)
    c.save()
    c.clipRect(0f, headerH.toFloat(), w.toFloat(), totalH.toFloat())
    c.translate(0f, headerH.toFloat())
    renderToCanvas(c, w, totalH - headerH, marks, texts, strokes, bg)
    c.restore()
    drawHeader(c, w, headerH, storeName, date, pageName)
}

private fun drawingFileName(storeName: String, ext: String): String {
    val store = storeName.ifBlank { "" }.replace(Regex("[\\\\/:*?\"<>|\\n\\r]"), "_").trim()
    return if (store.isEmpty()) "見取り図_${stamp()}.$ext" else "見取り図_${store}_${stamp()}.$ext"
}

// 現在のページだけをPNG出力
private fun exportImage(
    context: Context, pageIdx: Int, marks: List<MarkT>, texts: List<TextT>, strokes: List<StrokeT>, bg: Bitmap?, pdf: Boolean
) {
    try {
        val w = 1080; val headerH = 120; val h = 1440 + headerH
        val (storeName, date) = drawingHeaderInfo(context)
        val meta = loadPageMeta(context)
        val pageName = meta.second.getOrElse(pageIdx) { "ページ${pageIdx + 1}" }
        val file: File
        if (pdf) {
            val doc = android.graphics.pdf.PdfDocument()
            val page = doc.startPage(android.graphics.pdf.PdfDocument.PageInfo.Builder(w, h, 1).create())
            renderPage(page.canvas, w, h, headerH, marks, texts, strokes, bg, storeName, date, pageName)
            doc.finishPage(page)
            file = File(context.cacheDir, drawingFileName(storeName, "pdf"))
            file.outputStream().use { doc.writeTo(it) }; doc.close()
        } else {
            val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            renderPage(android.graphics.Canvas(bmp), w, h, headerH, marks, texts, strokes, bg, storeName, date, pageName)
            file = File(context.cacheDir, drawingFileName(storeName, "png"))
            file.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
            type = if (pdf) "application/pdf" else "image/png"
            putExtra(Intent.EXTRA_STREAM, uri); addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }, "見取り図を保存・共有"))
    } catch (e: Exception) {
        Toast.makeText(context, "出力に失敗: ${e.message}", Toast.LENGTH_LONG).show()
    }
}

// 全ページをまとめてPDF出力
private fun exportAllPages(context: Context, pageCount: Int, pageNames: List<String>) {
    try {
        val w = 1080; val headerH = 120; val h = 1440 + headerH
        val (storeName, date) = drawingHeaderInfo(context)
        val doc = android.graphics.pdf.PdfDocument()
        for (i in 0 until pageCount) {
            val m = mutableListOf<MarkT>(); val t = mutableListOf<TextT>(); val s = mutableListOf<StrokeT>()
            loadDrawPage(context, i, m, t, s)
            val pageBg = loadBitmapFile(drawBgFile(context, i))
            val pageName = pageNames.getOrElse(i) { "ページ${i + 1}" }
            val page = doc.startPage(android.graphics.pdf.PdfDocument.PageInfo.Builder(w, h, i + 1).create())
            renderPage(page.canvas, w, h, headerH, m, t, s, pageBg, storeName, date, pageName)
            doc.finishPage(page)
        }
        val file = File(context.cacheDir, drawingFileName(storeName, "pdf"))
        file.outputStream().use { doc.writeTo(it) }; doc.close()
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
            type = "application/pdf"
            putExtra(Intent.EXTRA_STREAM, uri); addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }, "見取り図を保存・共有"))
    } catch (e: Exception) {
        Toast.makeText(context, "PDF出力に失敗: ${e.message}", Toast.LENGTH_LONG).show()
    }
}

// ── 背景写真の端末内保存（ページごと）──
private fun drawBgFile(context: Context, pageIdx: Int): File =
    File(context.cacheDir, if (pageIdx == 0) "draw_bg.jpg" else "draw_bg_$pageIdx.jpg")

private fun saveDrawBg(context: Context, pageIdx: Int, bmp: Bitmap?) {
    val f = drawBgFile(context, pageIdx)
    if (bmp == null) { f.delete(); return }
    runCatching { f.outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, 85, it) } }
}

/**
 * 端末に保存済みの作図データ（全ページ）からPDF/PNGファイルを生成する。
 * 結果タブのメール添付など、作図タブを開いていなくても見取り図を出力するために使う。
 * 作図が空（全ページとも要素も背景も無し）の場合は null を返す。
 */
fun renderSavedDrawing(context: Context, pdf: Boolean): File? {
    val (pageCount, pageNames) = loadPageMeta(context)
    var hasContent = false
    for (i in 0 until pageCount) {
        val m = mutableListOf<MarkT>(); val t = mutableListOf<TextT>(); val s = mutableListOf<StrokeT>()
        loadDrawPage(context, i, m, t, s)
        val bg = loadBitmapFile(drawBgFile(context, i))
        if (m.isNotEmpty() || t.isNotEmpty() || s.isNotEmpty() || bg != null) { hasContent = true; break }
    }
    if (!hasContent) return null
    return try {
        val w = 1080; val headerH = 120; val h = 1440 + headerH
        val (storeName, date) = drawingHeaderInfo(context)
        if (pdf) {
            val doc = android.graphics.pdf.PdfDocument()
            for (i in 0 until pageCount) {
                val m = mutableListOf<MarkT>(); val t = mutableListOf<TextT>(); val s = mutableListOf<StrokeT>()
                loadDrawPage(context, i, m, t, s)
                val bg = loadBitmapFile(drawBgFile(context, i))
                val pageName = pageNames.getOrElse(i) { "ページ${i + 1}" }
                val page = doc.startPage(android.graphics.pdf.PdfDocument.PageInfo.Builder(w, h, i + 1).create())
                renderPage(page.canvas, w, h, headerH, m, t, s, bg, storeName, date, pageName)
                doc.finishPage(page)
            }
            val file = File(context.cacheDir, drawingFileName(storeName, "pdf"))
            file.outputStream().use { doc.writeTo(it) }; doc.close()
            file
        } else {
            val m = mutableListOf<MarkT>(); val t = mutableListOf<TextT>(); val s = mutableListOf<StrokeT>()
            loadDrawPage(context, 0, m, t, s)
            val bg = loadBitmapFile(drawBgFile(context, 0))
            val pageName = pageNames.getOrElse(0) { "ページ1" }
            val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            renderPage(android.graphics.Canvas(bmp), w, h, headerH, m, t, s, bg, storeName, date, pageName)
            val file = File(context.cacheDir, drawingFileName(storeName, "png"))
            file.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
            file
        }
    } catch (e: Exception) { null }
}

// ── 端末内保存（ページ対応）──
private const val DRAW_PREFS = "draw_prefs"

private fun savePageMeta(context: Context, pageCount: Int, pageNames: List<String>) {
    val prefs = context.getSharedPreferences(DRAW_PREFS, Context.MODE_PRIVATE)
    prefs.edit()
        .putInt("page_count", pageCount)
        .putString("page_names", pageNames.joinToString("\t"))
        .apply()
}

private fun loadPageMeta(context: Context): Pair<Int, MutableList<String>> {
    val prefs = context.getSharedPreferences(DRAW_PREFS, Context.MODE_PRIVATE)
    val count = prefs.getInt("page_count", 0)
    if (count == 0) {
        val hasLegacy = prefs.getString("data", null)
        if (hasLegacy != null && hasLegacy.isNotBlank()) {
            prefs.edit().putString("data_0", hasLegacy).putInt("page_count", 1)
                .putString("page_names", "ページ1").apply()
            return 1 to mutableListOf("ページ1")
        }
        prefs.edit().putInt("page_count", 1).putString("page_names", "ページ1").apply()
        return 1 to mutableListOf("ページ1")
    }
    val namesRaw = prefs.getString("page_names", "") ?: ""
    val names = if (namesRaw.isNotBlank()) namesRaw.split("\t").toMutableList() else mutableListOf()
    while (names.size < count) names.add("ページ${names.size + 1}")
    return count to names
}

private fun saveDrawPage(context: Context, pageIdx: Int, marks: List<MarkT>, texts: List<TextT>, strokes: List<StrokeT>) {
    val sb = StringBuilder()
    marks.forEach { sb.append("M|${it.x}|${it.y}|${it.type}|${it.num}|${it.size}|${it.label.replace("\n", " ").replace("|", "/")}|${it.customHW}|${it.customHH}\n") }
    texts.forEach { sb.append("T|${it.x}|${it.y}|${it.sizeF}|${it.colorL}|${if (it.boxed) 1 else 0}|${it.s.replace("\n", "~~").replace("|", "/")}\n") }
    strokes.forEach { st -> sb.append("S|${st.mode.name}|${st.colorL}|").append(st.pts.joinToString(";") { "${it.x},${it.y}" }).append("\n") }
    context.getSharedPreferences(DRAW_PREFS, Context.MODE_PRIVATE).edit().putString("data_$pageIdx", sb.toString()).apply()
}

private fun loadDrawPage(
    context: Context, pageIdx: Int, marks: MutableList<MarkT>, texts: MutableList<TextT>,
    strokes: MutableList<StrokeT>
) {
    val s = context.getSharedPreferences(DRAW_PREFS, Context.MODE_PRIVATE).getString("data_$pageIdx", "") ?: ""
    if (s.isBlank()) return
    s.split("\n").forEach { line ->
        val f = line.split("|")
        when {
            f[0] == "M" && f.size >= 5 -> marks.add(MarkT(
                f[1].toFloat(), f[2].toFloat(), f[3], f[4].toInt(),
                if (f.size >= 7) f[6] else "", if (f.size >= 6) f[5] else "M",
                if (f.size >= 8) f[7].toFloatOrNull() ?: 0f else 0f,
                if (f.size >= 9) f[8].toFloatOrNull() ?: 0f else 0f
            ))
            f[0] == "T" && f.size >= 7 -> texts.add(TextT(
                f[1].toFloat(), f[2].toFloat(), f[6].replace("~~", "\n"), f[3].toFloat(), f[4].toLong(), f[5] == "1"
            ))
            f[0] == "S" && f.size >= 4 -> {
                val mode = runCatching { PenMode.valueOf(f[1]) }.getOrDefault(PenMode.FREE)
                val pts = f[3].split(";").mapNotNull { p ->
                    val xy = p.split(","); if (xy.size == 2) Offset(xy[0].toFloat(), xy[1].toFloat()) else null
                }
                if (pts.isNotEmpty()) strokes.add(StrokeT(pts, mode, f[2].toLong()))
            }
        }
    }
}

private fun deleteDrawPage(context: Context, pageIdx: Int) {
    context.getSharedPreferences(DRAW_PREFS, Context.MODE_PRIVATE).edit().remove("data_$pageIdx").apply()
}

private fun renameDrawPage(context: Context, fromIdx: Int, toIdx: Int) {
    val prefs = context.getSharedPreferences(DRAW_PREFS, Context.MODE_PRIVATE)
    val data = prefs.getString("data_$fromIdx", null)
    val editor = prefs.edit()
    if (data != null) editor.putString("data_$toIdx", data)
    else editor.remove("data_$toIdx")
    editor.remove("data_$fromIdx").apply()
}
