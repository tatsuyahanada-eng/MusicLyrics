package com.tatsuya.idtool

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Paint
import android.graphics.Typeface
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import java.io.File

private enum class DTool { MARK, PEN, TEXT, DISTANCE }

// 機器種別 → 色（メイン/サブは同種=同色、番号で区別）
private val MarkTypes = listOf("RST", "EST", "RPR", "EPR", "RMD", "EMD")
private val TableTypes = listOf("卓小", "卓中", "卓大")
private fun typeColor(type: String): Long = when (type) {
    "RST" -> 0xFF1565C0
    "EST" -> 0xFF2E7D32
    "RPR" -> 0xFF6A1B9A
    "EPR" -> 0xFFEF6C00
    "RMD" -> 0xFF00838F
    "EMD" -> 0xFFC62828
    else -> 0xFF546E7A // テーブル
}

private data class MarkT(val x: Float, val y: Float, val type: String, val num: Int)
private data class TextT(val x: Float, val y: Float, val s: String)
private data class StrokeT(val pts: List<Offset>)

@Composable
fun DrawScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current

    val marks = remember { mutableStateListOf<MarkT>() }
    val texts = remember { mutableStateListOf<TextT>() }
    val strokes = remember { mutableStateListOf<StrokeT>() }
    remember { loadDraw(context, marks, texts, strokes); 0 }

    var bg by remember { mutableStateOf<Bitmap?>(null) }
    var tool by remember { mutableStateOf(DTool.MARK) }
    var selType by remember { mutableStateOf("RST") }
    var selNum by remember { mutableIntStateOf(1) }

    var sizeW by remember { mutableStateOf(1f) }
    var sizeH by remember { mutableStateOf(1f) }
    var live by remember { mutableStateOf<List<Offset>>(emptyList()) }

    var textPoint by remember { mutableStateOf<Offset?>(null) }
    var textInput by remember { mutableStateOf("") }
    var distPoint by remember { mutableStateOf<Offset?>(null) }

    val distance = remember { loadDistanceRecords(context) }

    val picker = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { uri: Uri? -> if (uri != null) bg = loadBitmap(context, uri) }

    fun persist() = saveDraw(context, marks, texts, strokes)

    Column(modifier = modifier.fillMaxSize().padding(8.dp)) {
        // ツール選択
        Row(modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            FilterChip(tool == DTool.MARK, { tool = DTool.MARK }, label = { Text("機器マーク") })
            FilterChip(tool == DTool.PEN, { tool = DTool.PEN }, label = { Text("ペン") })
            FilterChip(tool == DTool.TEXT, { tool = DTool.TEXT }, label = { Text("文字") })
            FilterChip(tool == DTool.DISTANCE, { tool = DTool.DISTANCE }, label = { Text("距離") })
        }

        if (tool == DTool.MARK) {
            Row(modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                (MarkTypes + TableTypes).forEach { t ->
                    FilterChip(selType == t, { selType = t }, label = { Text(t) })
                }
            }
            Row(modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                (1..10).forEach { n ->
                    FilterChip(selNum == n, { selNum = n }, label = { Text("$n") })
                }
            }
        }

        Spacer(Modifier.height(6.dp))

        // 作図キャンバス
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .background(Color.White, RoundedCornerShape(4.dp))
                .onSizeChanged { sizeW = it.width.toFloat(); sizeH = it.height.toFloat() }
                .pointerInput(tool, selType, selNum) {
                    if (tool == DTool.PEN) {
                        detectDragGestures(
                            onDragStart = { off -> live = listOf(norm(off, size.width, size.height)) },
                            onDrag = { ch, _ -> live = live + norm(ch.position, size.width, size.height) },
                            onDragEnd = {
                                if (live.size > 1) { strokes.add(StrokeT(live)); persist() }
                                live = emptyList()
                            }
                        )
                    } else {
                        detectTapGestures { off ->
                            val p = norm(off, size.width, size.height)
                            when (tool) {
                                DTool.MARK -> { marks.add(MarkT(p.x, p.y, selType, selNum)); persist() }
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
                // 背景画像
                bg?.let { b ->
                    drawImage(
                        image = b.asImageBitmap(),
                        srcOffset = IntOffset.Zero,
                        srcSize = IntSize(b.width, b.height),
                        dstOffset = IntOffset.Zero,
                        dstSize = IntSize(w.toInt(), h.toInt())
                    )
                }
                // 線
                strokes.forEach { st -> drawPolyline(st.pts, w, h, Color(0xFFD32F2F)) }
                if (live.size > 1) drawPolyline(live, w, h, Color(0xFFD32F2F))
                // マーク
                marks.forEach { m -> drawMark(m, w, h) }
                // テキスト
                texts.forEach { t ->
                    drawContext.canvas.nativeCanvas.apply {
                        val pt = Paint().apply {
                            color = android.graphics.Color.BLACK; textSize = 34f
                            isAntiAlias = true; typeface = Typeface.DEFAULT_BOLD
                        }
                        drawText(t.s, t.x * w, t.y * h, pt)
                    }
                }
            }
        }

        // 下部の操作
        Spacer(Modifier.height(6.dp))
        Row(modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            OutlinedButton(onClick = {
                picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
            }) { Text("写真取込") }
            OutlinedButton(onClick = { bg = null }) { Text("まっさら") }
            OutlinedButton(onClick = {
                if (strokes.isNotEmpty()) strokes.removeAt(strokes.size - 1)
                else if (texts.isNotEmpty()) texts.removeAt(texts.size - 1)
                else if (marks.isNotEmpty()) marks.removeAt(marks.size - 1)
                persist()
            }) { Text("取消") }
            OutlinedButton(onClick = {
                marks.clear(); texts.clear(); strokes.clear(); persist()
            }) { Text("全消去") }
            OutlinedButton(onClick = {
                exportPng(context, sizeW.toInt().coerceAtLeast(1), sizeH.toInt().coerceAtLeast(1),
                    bg, marks, texts, strokes)
            }) { Text("保存・共有") }
        }
    }

    // 文字入力ダイアログ
    if (textPoint != null) {
        AlertDialog(
            onDismissRequest = { textPoint = null },
            title = { Text("文字を入力") },
            text = {
                OutlinedTextField(value = textInput, onValueChange = { textInput = it }, singleLine = true)
            },
            confirmButton = {
                TextButton(onClick = {
                    val p = textPoint!!
                    if (textInput.isNotBlank()) { texts.add(TextT(p.x, p.y, textInput)); persist() }
                    textPoint = null
                }) { Text("配置") }
            },
            dismissButton = { TextButton(onClick = { textPoint = null }) { Text("取消") } }
        )
    }

    // 距離挿入ダイアログ
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
                            texts.add(TextT(p.x, p.y, "${r.memo} ${r.display()}")); persist()
                            distPoint = null
                        }) { Text("${r.memo}: ${r.display()}") }
                    }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = { distPoint = null }) { Text("閉じる") } }
        )
    }
}

private fun norm(off: Offset, w: Int, h: Int) =
    Offset(if (w > 0) off.x / w else 0f, if (h > 0) off.y / h else 0f)

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawPolyline(
    pts: List<Offset>, w: Float, h: Float, color: Color
) {
    for (i in 0 until pts.size - 1) {
        drawLine(color,
            Offset(pts[i].x * w, pts[i].y * h),
            Offset(pts[i + 1].x * w, pts[i + 1].y * h),
            strokeWidth = 5f)
    }
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawMark(m: MarkT, w: Float, h: Float) {
    val cx = m.x * w; val cy = m.y * h
    val col = Color(typeColor(m.type))
    val isTable = m.type.startsWith("卓")
    if (isTable) {
        val half = when (m.type) { "卓小" -> 18f; "卓中" -> 28f; else -> 40f }
        drawRect(col, topLeft = Offset(cx - half, cy - half),
            size = androidx.compose.ui.geometry.Size(half * 2, half * 2))
    } else {
        drawCircle(col, radius = 26f, center = Offset(cx, cy))
    }
    drawContext.canvas.nativeCanvas.apply {
        val pt = Paint().apply {
            color = android.graphics.Color.WHITE; textSize = 26f; isAntiAlias = true
            typeface = Typeface.DEFAULT_BOLD; textAlign = Paint.Align.CENTER
        }
        drawText("${m.num}", cx, cy + 9f, pt)
        val tp = Paint().apply {
            color = android.graphics.Color.BLACK; textSize = 24f; isAntiAlias = true
            typeface = Typeface.DEFAULT_BOLD; textAlign = Paint.Align.CENTER
        }
        drawText(m.type, cx, cy + 52f, tp)
    }
}

private fun loadBitmap(context: Context, uri: Uri): Bitmap? = try {
    context.contentResolver.openInputStream(uri)?.use { input ->
        val opts = BitmapFactory.Options().apply { inSampleSize = 2 }
        BitmapFactory.decodeStream(input, null, opts)
    }
} catch (e: Exception) { null }

private fun exportPng(
    context: Context, w: Int, h: Int, bg: Bitmap?,
    marks: List<MarkT>, texts: List<TextT>, strokes: List<StrokeT>
) {
    try {
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = android.graphics.Canvas(bmp)
        c.drawColor(android.graphics.Color.WHITE)
        bg?.let {
            c.drawBitmap(it, android.graphics.Rect(0, 0, it.width, it.height),
                android.graphics.Rect(0, 0, w, h), null)
        }
        val line = Paint().apply {
            color = android.graphics.Color.rgb(0xD3, 0x2F, 0x2F)
            strokeWidth = 5f; isAntiAlias = true
        }
        strokes.forEach { st ->
            for (i in 0 until st.pts.size - 1) {
                c.drawLine(st.pts[i].x * w, st.pts[i].y * h,
                    st.pts[i + 1].x * w, st.pts[i + 1].y * h, line)
            }
        }
        marks.forEach { m ->
            val cx = m.x * w; val cy = m.y * h
            val p = Paint().apply { color = typeColor(m.type).toInt(); isAntiAlias = true }
            if (m.type.startsWith("卓")) {
                val half = when (m.type) { "卓小" -> 18f; "卓中" -> 28f; else -> 40f }
                c.drawRect(cx - half, cy - half, cx + half, cy + half, p)
            } else c.drawCircle(cx, cy, 26f, p)
            c.drawText("${m.num}", cx, cy + 9f, Paint().apply {
                color = android.graphics.Color.WHITE; textSize = 26f; isAntiAlias = true
                typeface = Typeface.DEFAULT_BOLD; textAlign = Paint.Align.CENTER
            })
            c.drawText(m.type, cx, cy + 52f, Paint().apply {
                color = android.graphics.Color.BLACK; textSize = 24f; isAntiAlias = true
                typeface = Typeface.DEFAULT_BOLD; textAlign = Paint.Align.CENTER
            })
        }
        texts.forEach { t ->
            c.drawText(t.s, t.x * w, t.y * h, Paint().apply {
                color = android.graphics.Color.BLACK; textSize = 34f; isAntiAlias = true
                typeface = Typeface.DEFAULT_BOLD
            })
        }
        val file = File(context.cacheDir, "見取り図.png")
        file.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "image/png"; putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(intent, "見取り図を保存・共有"))
    } catch (e: Exception) {
        Toast.makeText(context, "出力に失敗: ${e.message}", Toast.LENGTH_LONG).show()
    }
}

// ── 端末内保存（要素のみ。背景画像は保存対象外）──
private const val DRAW_PREFS = "draw_prefs"

private fun saveDraw(context: Context, marks: List<MarkT>, texts: List<TextT>, strokes: List<StrokeT>) {
    val sb = StringBuilder()
    marks.forEach { sb.append("M|${it.x}|${it.y}|${it.type}|${it.num}\n") }
    texts.forEach { sb.append("T|${it.x}|${it.y}|${it.s.replace("\n", " ").replace("|", "/")}\n") }
    strokes.forEach { st ->
        sb.append("S|").append(st.pts.joinToString(";") { "${it.x},${it.y}" }).append("\n")
    }
    context.getSharedPreferences(DRAW_PREFS, Context.MODE_PRIVATE).edit()
        .putString("data", sb.toString()).apply()
}

private fun loadDraw(
    context: Context,
    marks: MutableList<MarkT>, texts: MutableList<TextT>, strokes: MutableList<StrokeT>
) {
    val s = context.getSharedPreferences(DRAW_PREFS, Context.MODE_PRIVATE).getString("data", "") ?: ""
    if (s.isBlank()) return
    s.split("\n").forEach { line ->
        val f = line.split("|")
        when {
            f[0] == "M" && f.size >= 5 ->
                marks.add(MarkT(f[1].toFloat(), f[2].toFloat(), f[3], f[4].toInt()))
            f[0] == "T" && f.size >= 4 ->
                texts.add(TextT(f[1].toFloat(), f[2].toFloat(), f[3]))
            f[0] == "S" && f.size >= 2 -> {
                val pts = f[1].split(";").mapNotNull { p ->
                    val xy = p.split(","); if (xy.size == 2) Offset(xy[0].toFloat(), xy[1].toFloat()) else null
                }
                if (pts.isNotEmpty()) strokes.add(StrokeT(pts))
            }
        }
    }
}
