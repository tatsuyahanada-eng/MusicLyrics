package com.tatsuya.idtool

import android.content.Context
import android.content.Intent
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
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
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import java.io.File
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

private enum class DTool { MARK, PEN, TEXT, DISTANCE, MOVE }
private enum class PenMode { FREE, LINE, DASH, ARROW }

private val MarkTypes = listOf("RST", "EST", "RPR", "EPR", "RMD", "EMD")
private val TableTypes = listOf("卓小", "卓中", "卓大")
private val Palette = listOf(0xFF000000L, 0xFFD32F2FL, 0xFF1565C0L, 0xFF2E7D32L, 0xFFEF6C00L, 0xFF6A1B9AL)

private fun typeColor(type: String): Long = when (type) {
    "RST" -> 0xFF1565C0
    "EST" -> 0xFF2E7D32
    "RPR" -> 0xFF6A1B9A
    "EPR" -> 0xFFEF6C00
    "RMD" -> 0xFF00838F
    "EMD" -> 0xFFC62828
    else -> 0xFF546E7A
}

private data class MarkT(val x: Float, val y: Float, val type: String, val num: Int)
private data class TextT(val x: Float, val y: Float, val s: String, val sizeF: Float, val colorL: Long, val boxed: Boolean)
private data class StrokeT(val pts: List<Offset>, val mode: PenMode, val colorL: Long)

@Composable
fun DrawScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current

    val marks = remember { mutableStateListOf<MarkT>() }
    val texts = remember { mutableStateListOf<TextT>() }
    val strokes = remember { mutableStateListOf<StrokeT>() }
    val ops = remember { mutableStateListOf<String>() } // 操作履歴（undo用）
    remember { loadDraw(context, marks, texts, strokes, ops); 0 }

    var bg by remember { mutableStateOf<Bitmap?>(null) }
    var tool by remember { mutableStateOf(DTool.MARK) }
    var penMode by remember { mutableStateOf(PenMode.FREE) }
    var selType by remember { mutableStateOf("RST") }
    var selNum by remember { mutableIntStateOf(1) }
    var selColor by remember { mutableStateOf(0xFFD32F2FL) }
    var selTextSize by remember { mutableStateOf(36f) }

    var live by remember { mutableStateOf<List<Offset>>(emptyList()) }
    var dragStart by remember { mutableStateOf(Offset.Zero) }
    var moveKind by remember { mutableStateOf<String?>(null) }
    var moveIdx by remember { mutableIntStateOf(-1) }
    var lastP by remember { mutableStateOf(Offset.Zero) }

    var textPoint by remember { mutableStateOf<Offset?>(null) }
    var textInput by remember { mutableStateOf("") }
    var distPoint by remember { mutableStateOf<Offset?>(null) }
    var showClear by remember { mutableStateOf(false) }

    val distance = remember { loadDistanceRecords(context) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri: Uri? ->
        if (uri != null) bg = loadBitmap(context, uri)
    }

    fun persist() = saveDraw(context, marks, texts, strokes, ops)

    Column(modifier = modifier.fillMaxSize().padding(8.dp)) {
        // ツール
        Row(modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            FilterChip(tool == DTool.MARK, { tool = DTool.MARK }, label = { Text("機器") })
            FilterChip(tool == DTool.PEN, { tool = DTool.PEN }, label = { Text("ペン") })
            FilterChip(tool == DTool.TEXT, { tool = DTool.TEXT }, label = { Text("文字") })
            FilterChip(tool == DTool.DISTANCE, { tool = DTool.DISTANCE }, label = { Text("距離") })
            FilterChip(tool == DTool.MOVE, { tool = DTool.MOVE }, label = { Text("移動") })
        }

        // サブ設定
        when (tool) {
            DTool.MARK -> {
                Row(modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    (MarkTypes + TableTypes).forEach { t ->
                        FilterChip(selType == t, { selType = t }, label = { Text(t) })
                    }
                }
                Row(modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    (1..10).forEach { n -> FilterChip(selNum == n, { selNum = n }, label = { Text("$n") }) }
                }
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
            DTool.MOVE -> Text("図形/マークをドラッグして移動できます", fontSize = 11.sp)
            else -> {}
        }

        Spacer(Modifier.height(6.dp))

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .background(Color.White, RoundedCornerShape(4.dp))
                .border(1.dp, Color(0xFFBDBDBD), RoundedCornerShape(4.dp))
                .pointerInput(tool, penMode, selType, selNum, selColor, selTextSize) {
                    when (tool) {
                        DTool.PEN -> detectDragGestures(
                            onDragStart = { off -> dragStart = norm(off, size.width, size.height); live = listOf(dragStart) },
                            onDrag = { ch, _ ->
                                val cur = norm(ch.position, size.width, size.height)
                                live = if (penMode == PenMode.FREE) live + cur else listOf(dragStart, cur)
                            },
                            onDragEnd = {
                                if (live.size >= 2) {
                                    val pts = if (penMode == PenMode.FREE) live else listOf(live.first(), live.last())
                                    strokes.add(StrokeT(pts, penMode, selColor)); ops.add("S"); persist()
                                }
                                live = emptyList()
                            }
                        )
                        DTool.MOVE -> detectDragGestures(
                            onDragStart = { off ->
                                val p = norm(off, size.width, size.height)
                                val n = nearestKind(p, marks, texts, strokes)
                                moveKind = n?.first; moveIdx = n?.second ?: -1; lastP = p
                            },
                            onDrag = { ch, _ ->
                                val np = norm(ch.position, size.width, size.height)
                                applyMove(moveKind, moveIdx, np.x - lastP.x, np.y - lastP.y, marks, texts, strokes)
                                lastP = np
                            },
                            onDragEnd = { persist(); moveKind = null }
                        )
                        else -> detectTapGestures { off ->
                            val p = norm(off, size.width, size.height)
                            when (tool) {
                                DTool.MARK -> { marks.add(MarkT(p.x, p.y, selType, selNum)); ops.add("M"); persist() }
                                DTool.TEXT -> { textPoint = p; textInput = "" }
                                DTool.DISTANCE -> { distPoint = p }
                                else -> {}
                            }
                        }
                    }
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
            }
        }

        Spacer(Modifier.height(6.dp))
        Row(modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            OutlinedButton(onClick = {
                picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
            }) { Text("写真取込") }
            OutlinedButton(onClick = { bg = null }) { Text("背景白") }
            OutlinedButton(onClick = {
                if (ops.isNotEmpty()) {
                    when (ops.removeAt(ops.size - 1)) {
                        "M" -> if (marks.isNotEmpty()) marks.removeAt(marks.size - 1)
                        "T" -> if (texts.isNotEmpty()) texts.removeAt(texts.size - 1)
                        "S" -> if (strokes.isNotEmpty()) strokes.removeAt(strokes.size - 1)
                    }
                    persist()
                }
            }) { Text("取消") }
            OutlinedButton(onClick = { showClear = true }) { Text("全消去") }
            OutlinedButton(onClick = {
                exportPng(context, marks, texts, strokes, bg)
            }) { Text("保存・共有") }
        }
    }

    // 文字入力
    if (textPoint != null) {
        AlertDialog(
            onDismissRequest = { textPoint = null },
            title = { Text("文字を入力") },
            text = { OutlinedTextField(value = textInput, onValueChange = { textInput = it }, singleLine = true) },
            confirmButton = {
                TextButton(onClick = {
                    val p = textPoint!!
                    if (textInput.isNotBlank()) { texts.add(TextT(p.x, p.y, textInput, selTextSize, selColor, false)); ops.add("T"); persist() }
                    textPoint = null
                }) { Text("配置") }
            },
            dismissButton = { TextButton(onClick = { textPoint = null }) { Text("取消") } }
        )
    }

    // 距離挿入（メモ改行＋距離、白背景□）
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
                            texts.add(TextT(p.x, p.y, "${r.memo}\n${r.display()}", 32f, 0xFF000000L, true)); ops.add("T"); persist()
                            distPoint = null
                        }) { Text("${r.memo}: ${r.display()}") }
                    }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = { distPoint = null }) { Text("閉じる") } }
        )
    }

    // 全消去の確認
    if (showClear) {
        AlertDialog(
            onDismissRequest = { showClear = false },
            title = { Text("全消去の確認") },
            text = { Text("配置した機器・線・文字をすべて消去します。よろしいですか？") },
            confirmButton = {
                TextButton(onClick = {
                    marks.clear(); texts.clear(); strokes.clear(); ops.clear(); persist(); showClear = false
                }) { Text("消去する") }
            },
            dismissButton = { TextButton(onClick = { showClear = false }) { Text("やめる") } }
        )
    }
}

@Composable
private fun ColorPalette(sel: Long, onPick: (Long) -> Unit) {
    Row(modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(top = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Palette.forEach { c ->
            Box(
                modifier = Modifier
                    .size(28.dp)
                    .background(Color(c), CircleShape)
                    .border(if (sel == c) 3.dp else 1.dp,
                        if (sel == c) Color(0xFF111111) else Color(0xFFBBBBBB), CircleShape)
                    .clickable { onPick(c) }
            )
        }
    }
}

private fun norm(off: Offset, w: Int, h: Int) =
    Offset(if (w > 0) off.x / w else 0f, if (h > 0) off.y / h else 0f)

private fun d2(a: Offset, b: Offset): Float {
    val dx = a.x - b.x; val dy = a.y - b.y; return dx * dx + dy * dy
}

private fun nearestKind(
    p: Offset, marks: List<MarkT>, texts: List<TextT>, strokes: List<StrokeT>
): Pair<String, Int>? {
    var best: Pair<String, Int>? = null
    var bestD = Float.MAX_VALUE
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
        "S" -> if (idx in strokes.indices)
            strokes[idx] = strokes[idx].copy(pts = strokes[idx].pts.map { Offset(it.x + dx, it.y + dy) })
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
            val ang = atan2((b.y - a.y).toDouble(), (b.x - a.x).toDouble())
            val len = 26f
            listOf(ang + 2.6, ang - 2.6).forEach { s ->
                drawLine(col, b, Offset(b.x + (cos(s) * len).toFloat(), b.y + (sin(s) * len).toFloat()), strokeWidth = 5f)
            }
        }
    }
}

private fun DrawScope.drawMark(m: MarkT, w: Float, h: Float) {
    val cx = m.x * w; val cy = m.y * h
    val col = Color(typeColor(m.type))
    val half = when (m.type) { "卓小" -> 18f; "卓中" -> 28f; "卓大" -> 40f; else -> 24f }
    drawRect(col, topLeft = Offset(cx - half, cy - half), size = Size(half * 2, half * 2))
    drawContext.canvas.nativeCanvas.apply {
        drawText("${m.num}", cx, cy + 9f, Paint().apply {
            color = android.graphics.Color.WHITE; textSize = 26f; isAntiAlias = true
            typeface = Typeface.DEFAULT_BOLD; textAlign = Paint.Align.CENTER
        })
        if (!m.type.startsWith("卓")) drawText(m.type, cx, cy + half + 26f, Paint().apply {
            color = android.graphics.Color.BLACK; textSize = 22f; isAntiAlias = true
            typeface = Typeface.DEFAULT_BOLD; textAlign = Paint.Align.CENTER
        })
    }
}

private fun DrawScope.drawTextItem(t: TextT, w: Float, h: Float) {
    val x = t.x * w; val y = t.y * h
    val nc = drawContext.canvas.nativeCanvas
    val paint = Paint().apply {
        color = t.colorL.toInt(); textSize = t.sizeF; isAntiAlias = true; typeface = Typeface.DEFAULT_BOLD
    }
    if (t.boxed) {
        val lines = t.s.split("\n")
        val pad = 12f
        val lineH = t.sizeF * 1.25f
        val maxW = lines.maxOf { paint.measureText(it) }
        val boxW = maxW + pad * 2; val boxH = lineH * lines.size + pad * 2
        val left = x - boxW / 2; val top = y - boxH / 2
        drawRect(Color.White, topLeft = Offset(left, top), size = Size(boxW, boxH))
        drawRect(Color(0xFF333333), topLeft = Offset(left, top), size = Size(boxW, boxH), style = Stroke(width = 2f))
        paint.textAlign = Paint.Align.CENTER
        lines.forEachIndexed { i, ln ->
            nc.drawText(ln, x, top + pad + lineH * (i + 1) - lineH * 0.25f, paint)
        }
    } else {
        nc.drawText(t.s, x, y, paint)
    }
}

private fun loadBitmap(context: Context, uri: Uri): Bitmap? = try {
    context.contentResolver.openInputStream(uri)?.use {
        BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = 2 })
    }
} catch (e: Exception) { null }

private fun exportPng(
    context: Context, marks: List<MarkT>, texts: List<TextT>, strokes: List<StrokeT>, bg: Bitmap?
) {
    try {
        val w = 1080; val h = 1440
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = android.graphics.Canvas(bmp)
        c.drawColor(android.graphics.Color.WHITE)
        bg?.let {
            c.drawBitmap(it, android.graphics.Rect(0, 0, it.width, it.height),
                android.graphics.Rect(0, 0, w, h), null)
        }
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
            val half = when (m.type) { "卓小" -> 22f; "卓中" -> 34f; "卓大" -> 48f; else -> 30f }
            c.drawRect(cx - half, cy - half, cx + half, cy + half, Paint().apply { color = typeColor(m.type).toInt(); isAntiAlias = true })
            c.drawText("${m.num}", cx, cy + 11f, Paint().apply {
                color = android.graphics.Color.WHITE; textSize = 32f; isAntiAlias = true
                typeface = Typeface.DEFAULT_BOLD; textAlign = Paint.Align.CENTER
            })
            if (!m.type.startsWith("卓")) c.drawText(m.type, cx, cy + half + 30f, Paint().apply {
                color = android.graphics.Color.BLACK; textSize = 26f; isAntiAlias = true
                typeface = Typeface.DEFAULT_BOLD; textAlign = Paint.Align.CENTER
            })
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
        val file = File(context.cacheDir, "見取り図.png")
        file.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
            type = "image/png"; putExtra(Intent.EXTRA_STREAM, uri); addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }, "見取り図を保存・共有"))
    } catch (e: Exception) {
        Toast.makeText(context, "出力に失敗: ${e.message}", Toast.LENGTH_LONG).show()
    }
}

// ── 端末内保存 ──
private const val DRAW_PREFS = "draw_prefs"

private fun saveDraw(
    context: Context, marks: List<MarkT>, texts: List<TextT>, strokes: List<StrokeT>, ops: List<String>
) {
    val sb = StringBuilder()
    marks.forEach { sb.append("M|${it.x}|${it.y}|${it.type}|${it.num}\n") }
    texts.forEach {
        sb.append("T|${it.x}|${it.y}|${it.sizeF}|${it.colorL}|${if (it.boxed) 1 else 0}|${it.s.replace("\n", "~~").replace("|", "/")}\n")
    }
    strokes.forEach { st ->
        sb.append("S|${st.mode.name}|${st.colorL}|").append(st.pts.joinToString(";") { "${it.x},${it.y}" }).append("\n")
    }
    sb.append("O|${ops.joinToString(",")}\n")
    context.getSharedPreferences(DRAW_PREFS, Context.MODE_PRIVATE).edit().putString("data", sb.toString()).apply()
}

private fun loadDraw(
    context: Context, marks: MutableList<MarkT>, texts: MutableList<TextT>,
    strokes: MutableList<StrokeT>, ops: MutableList<String>
) {
    val s = context.getSharedPreferences(DRAW_PREFS, Context.MODE_PRIVATE).getString("data", "") ?: ""
    if (s.isBlank()) return
    s.split("\n").forEach { line ->
        val f = line.split("|")
        when {
            f[0] == "M" && f.size >= 5 -> marks.add(MarkT(f[1].toFloat(), f[2].toFloat(), f[3], f[4].toInt()))
            f[0] == "T" && f.size >= 7 ->
                texts.add(TextT(f[1].toFloat(), f[2].toFloat(), f[6].replace("~~", "\n"),
                    f[3].toFloat(), f[4].toLong(), f[5] == "1"))
            f[0] == "S" && f.size >= 4 -> {
                val mode = runCatching { PenMode.valueOf(f[1]) }.getOrDefault(PenMode.FREE)
                val pts = f[3].split(";").mapNotNull { p ->
                    val xy = p.split(","); if (xy.size == 2) Offset(xy[0].toFloat(), xy[1].toFloat()) else null
                }
                if (pts.isNotEmpty()) strokes.add(StrokeT(pts, mode, f[2].toLong()))
            }
            f[0] == "O" && f.size >= 2 -> ops.addAll(f[1].split(",").filter { it.isNotBlank() })
        }
    }
    // 旧データ（O行なし）の場合は概算で履歴を復元
    if (ops.isEmpty()) {
        repeat(marks.size) { ops.add("M") }
        repeat(texts.size) { ops.add("T") }
        repeat(strokes.size) { ops.add("S") }
    }
}
