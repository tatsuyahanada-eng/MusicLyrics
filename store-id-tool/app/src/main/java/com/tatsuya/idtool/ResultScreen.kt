package com.tatsuya.idtool

import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import java.io.File
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

private val ResultOptions = listOf("最適", "良", "圏外", "送受信エラー")
// 送信先メールの初期値（変更可）
private const val DEFAULT_EMAIL = "jrss-03@alljrs.co.jp"
private val GRID_LEFT_W = 96.dp
private val GRID_CELL_W = 190.dp
private val GRID_HEADER_H = 62.dp
// 表の見出し（1番左の列・1番上の行・左上角）に色を付ける
private val GridHeaderBg = Color(0xFFCFD8DC)
private val GridCornerBg = Color(0xFFB0BEC5)

private fun stamp(): String = SimpleDateFormat("yyyyMMdd_HHmm", Locale.JAPAN).format(Date())

@Composable
fun ResultScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current

    val idInfo = remember { loadIdInfo(context) }
    val rows = remember { buildRows(idInfo.brand, idInfo.storeNumber) }
    // 測定順（古い順）。距離タブは新しい順に保存されるため反転して場所1=最初の測定に合わせる
    val ordered = remember { loadDistanceRecords(context).asReversed() }
    val unit = remember { loadDistUnit(context) }

    val data = remember { mutableStateMapOf<String, String>().apply { putAll(loadResultMap(context)) } }
    val set: (String, String) -> Unit = { k, v -> data[k] = v; saveResultMap(context, data) }

    var locCount by remember { mutableIntStateOf((data["場所数"]?.toIntOrNull() ?: 5).coerceAtLeast(5)) }

    LaunchedEffect(Unit) {
        // 変更後システムIDは、ID計算タブでタップ（選択）した最新の10桁IDを反映する
        val selectedId = loadSelectedSystemId(context)
        if (selectedId.isNotBlank()) data["変更後システムID"] = selectedId
        if (data["店舗名"].isNullOrBlank() && idInfo.storeName.isNotBlank()) data["店舗名"] = idInfo.storeName
        // 未入力、または旧初期値のままの端末は、現在の初期値に合わせる（手入力の宛先は保持）
        val curEmail = data["送信先メール"]
        if (curEmail.isNullOrBlank() || curEmail == "tatsuya.hanada@gmail.com") data["送信先メール"] = DEFAULT_EMAIL
        saveResultMap(context, data)
    }

    Column(
        modifier = modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)
    ) {
        Text("無線テスト結果表", fontWeight = FontWeight.Bold, fontSize = 18.sp,
            color = MaterialTheme.colorScheme.onBackground)

        Spacer(Modifier.height(8.dp))

        // ── ヘッダー ──
        TextField2("変更後システムID", data, set)
        TextField2("店舗名", data, set)

        // 日付（カレンダー）
        PickerField("日付", data["日付"] ?: "", onClick = {
            val c = Calendar.getInstance()
            DatePickerDialog(context, { _, y, m, d ->
                set("日付", "%04d/%02d/%02d".format(y, m + 1, d))
            }, c.get(Calendar.YEAR), c.get(Calendar.MONTH), c.get(Calendar.DAY_OF_MONTH)).show()
        })
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PickerField("開始時間", data["開始時間"] ?: "", modifier = Modifier.weight(1f), onClick = {
                val c = Calendar.getInstance()
                TimePickerDialog(context, { _, h, mi -> set("開始時間", "%02d:%02d".format(h, mi)) },
                    c.get(Calendar.HOUR_OF_DAY), c.get(Calendar.MINUTE), true).show()
            })
            PickerField("終了時間", data["終了時間"] ?: "", modifier = Modifier.weight(1f), onClick = {
                val c = Calendar.getInstance()
                TimePickerDialog(context, { _, h, mi -> set("終了時間", "%02d:%02d".format(h, mi)) },
                    c.get(Calendar.HOUR_OF_DAY), c.get(Calendar.MINUTE), true).show()
            })
        }
        TextField2("作業員", data, set)
        TextField2("備考", data, set, single = false)
        TextField2("送信先メール", data, set)

        Spacer(Modifier.height(6.dp))
        Text("対象機器: ${idInfo.brand.label}", fontSize = 13.sp,
            fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
        if (!isValidStoreNumber(idInfo.storeNumber)) {
            Text("※ ID計算タブで共通番号（5桁）を入力すると10桁IDが反映されます",
                fontSize = 11.sp, color = MaterialTheme.colorScheme.error)
        }

        // ── 結果入力グリッド（左=検証場所 / 上=Ch）──
        Spacer(Modifier.height(10.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("検証場所 × Ch（結果入力）", fontWeight = FontWeight.Bold, fontSize = 14.sp,
                color = MaterialTheme.colorScheme.onBackground)
            Spacer(Modifier.weight(1f))
            OutlinedButton(onClick = { locCount += 1; set("場所数", locCount.toString()) }) { Text("＋場所追加") }
        }
        Text("既定は48chまで表示。列見出しの × で列を隠す／下の「戻す」で48ch超を追加できます",
            fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)

        // 非表示にしたch（不要な列を隠す）。CSV出力からも除外される。
        // 未設定（初回）は既定で48ch超（100ch以降）を非表示にし、必要時に「戻す」で追加できる。
        val hiddenRaw = data["非表示Ch"]
        val hiddenChs = if (hiddenRaw == null) rows.filter { it.ch > 48 }.map { it.ch }.toSet()
            else hiddenRaw.split(",").mapNotNull { it.trim().toIntOrNull() }.toSet()
        val visibleRows = rows.filter { it.ch !in hiddenChs }
        if (hiddenChs.isNotEmpty()) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 4.dp).horizontalScroll(rememberScrollState()),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Text("非表示の列:", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                hiddenChs.sorted().forEach { ch ->
                    OutlinedButton(
                        onClick = { set("非表示Ch", (hiddenChs - ch).sorted().joinToString(",")) },
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)
                    ) { Text("ch:$ch を戻す", fontSize = 11.sp) }
                }
                TextButton(onClick = { set("非表示Ch", "") }) { Text("すべて表示", fontSize = 11.sp) }
            }
        }
        Spacer(Modifier.height(6.dp))

        Column(modifier = Modifier.horizontalScroll(rememberScrollState())) {
            // ヘッダー行（左上 + 各Ch）
            Row {
                GridCorner()
                visibleRows.forEach { row ->
                    GridChHeader(row) { set("非表示Ch", (hiddenChs + row.ch).sorted().joinToString(",")) }
                }
            }
            // 場所ごとの行
            (1..locCount).forEach { l ->
                Row {
                    val rec = ordered.getOrNull(l - 1)
                    GridLocCell(l, rec, unit) {
                        Toast.makeText(context,
                            if (rec != null) "場所$l: ${rec.memo} = ${rec.display(unit)}" else "場所$l: 距離記録なし",
                            Toast.LENGTH_SHORT).show()
                    }
                    visibleRows.forEach { row -> GridCell(l, row, data, set) }
                }
            }
        }

        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(
                onClick = { copyText(context, buildCsv(idInfo, visibleRows, data, locCount, ordered, unit)) },
                modifier = Modifier.weight(1f)
            ) { Text("CSVコピー") }
            OutlinedButton(
                onClick = { exportCsv(context, buildCsv(idInfo, visibleRows, data, locCount, ordered, unit)) },
                modifier = Modifier.weight(1f)
            ) { Text("CSV出力") }
        }
        Spacer(Modifier.height(8.dp))
        Button(
            onClick = {
                sendByEmail(
                    context = context,
                    email = data["送信先メール"].orEmpty().ifBlank { DEFAULT_EMAIL },
                    storeName = data["店舗名"].orEmpty(),
                    date = data["日付"].orEmpty(),
                    idInfo = idInfo,
                    csv = buildCsv(idInfo, visibleRows, data, locCount, ordered, unit),
                    data = data
                )
            },
            modifier = Modifier.fillMaxWidth()
        ) { Text("メールで送信（作図PDF＋結果CSVを添付）", fontWeight = FontWeight.Bold) }
        Text("作図（見取り図）と結果CSVを添付してメール送信します。宛先は上の「送信先メール」欄で変更できます。",
            fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 4.dp))
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun GridCorner() {
    Box(modifier = Modifier.width(GRID_LEFT_W).height(GRID_HEADER_H)
        .background(GridCornerBg)
        .border(0.7.dp, MaterialTheme.colorScheme.outline), contentAlignment = Alignment.Center) {
        Text("場所＼Ch", fontSize = 11.sp, fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun GridChHeader(row: IdRow, onHide: () -> Unit) {
    Box(
        modifier = Modifier.width(GRID_CELL_W).height(GRID_HEADER_H)
            .background(GridHeaderBg)
            .border(0.7.dp, MaterialTheme.colorScheme.outline)
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 6.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Text("ch:${row.ch}", fontWeight = FontWeight.Bold, fontSize = 13.sp,
                color = MaterialTheme.colorScheme.primary)
            Text(if (row.fullId.isNotEmpty()) row.fullId else "（番号未入力）",
                fontFamily = FontFamily.Monospace, fontSize = 12.sp, maxLines = 1, softWrap = false,
                color = MaterialTheme.colorScheme.onSurface)
        }
        // 列を非表示にする（× をタップ）
        Text("✕", fontSize = 12.sp, fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.error,
            modifier = Modifier.align(Alignment.TopEnd)
                .clickable { onHide() }
                .padding(horizontal = 6.dp, vertical = 2.dp))
    }
}

@Composable
private fun GridLocCell(loc: Int, rec: Record?, unit: DistUnit, onClick: () -> Unit) {
    Column(
        modifier = Modifier.width(GRID_LEFT_W)
            .background(GridHeaderBg)
            .border(0.7.dp, MaterialTheme.colorScheme.outline)
            .clickable { onClick() }.padding(4.dp)
    ) {
        Text("場所$loc", fontWeight = FontWeight.Bold, fontSize = 14.sp,
            color = MaterialTheme.colorScheme.onBackground)
        if (rec != null) {
            Text(rec.memo, fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(rec.display(unit), fontSize = 11.sp, fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary)
        }
    }
}

@Composable
private fun GridCell(
    loc: Int, row: IdRow,
    data: androidx.compose.runtime.snapshots.SnapshotStateMap<String, String>,
    set: (String, String) -> Unit
) {
    fun key(f: String) = "loc${loc}_ch${row.ch}_$f"
    Column(
        modifier = Modifier.width(GRID_CELL_W)
            .border(0.7.dp, MaterialTheme.colorScheme.outline).padding(4.dp)
    ) {
        ResultDropdown(data[key("結果")] ?: "") { set(key("結果"), it) }
        Spacer(Modifier.height(3.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            NumField("電波送", data[key("電波強度送信")] ?: "", { set(key("電波強度送信"), it) }, Modifier.weight(1f))
            NumField("電波受", data[key("電波強度受信")] ?: "", { set(key("電波強度受信"), it) }, Modifier.weight(1f))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            NumField("ﾉｲｽﾞ送", data[key("ノイズ送信")] ?: "", { set(key("ノイズ送信"), it) }, Modifier.weight(1f))
            NumField("ﾉｲｽﾞ受", data[key("ノイズ受信")] ?: "", { set(key("ノイズ受信"), it) }, Modifier.weight(1f))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            NumField("ﾊﾟｹ送", data[key("送信ﾊﾟｹ")] ?: "", { set(key("送信ﾊﾟｹ"), it) }, Modifier.weight(1f))
            NumField("ﾊﾟｹ受", data[key("受信ﾊﾟｹ")] ?: "", { set(key("受信ﾊﾟｹ"), it) }, Modifier.weight(1f))
        }
    }
}

@Composable
private fun TextField2(
    label: String,
    data: androidx.compose.runtime.snapshots.SnapshotStateMap<String, String>,
    set: (String, String) -> Unit,
    single: Boolean = true
) {
    OutlinedTextField(
        value = data[label] ?: "",
        onValueChange = { set(label, it) },
        label = { Text(label, fontSize = 13.sp) },
        singleLine = single,
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)
    )
}

@Composable
private fun PickerField(label: String, value: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Box(modifier = modifier.fillMaxWidth().padding(vertical = 4.dp).clickable { onClick() }) {
        OutlinedTextField(
            value = value,
            onValueChange = {},
            readOnly = true,
            enabled = false,
            label = { Text(label, fontSize = 13.sp) },
            modifier = Modifier.fillMaxWidth()
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ResultDropdown(value: String, onSelect: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
        OutlinedTextField(
            value = value,
            onValueChange = {},
            readOnly = true,
            label = { Text("結果", fontSize = 11.sp) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier.menuAnchor(MenuAnchorType.PrimaryNotEditable).fillMaxWidth()
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            ResultOptions.forEach { o ->
                DropdownMenuItem(text = { Text(o) }, onClick = { onSelect(o); expanded = false })
            }
        }
    }
}

@Composable
private fun NumField(label: String, value: String, onChange: (String) -> Unit, modifier: Modifier) {
    OutlinedTextField(
        value = value,
        onValueChange = { onChange(it.filter { c -> c.isDigit() }.take(3)) },
        label = { Text(label, fontSize = 10.sp) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        modifier = modifier
    )
}

private fun csvEscape(s: String): String =
    if (s.contains(',') || s.contains('"') || s.contains('\n'))
        "\"" + s.replace("\"", "\"\"") + "\"" else s

private fun buildCsv(
    idInfo: IdInfo, rows: List<IdRow>, data: Map<String, String>,
    locCount: Int, ordered: List<Record>, unit: DistUnit
): String {
    val sb = StringBuilder()
    sb.append("変更後システムID,${csvEscape(data["変更後システムID"].orEmpty())}\n")
    sb.append("店舗名,${csvEscape(data["店舗名"].orEmpty())}\n")
    sb.append("日付,${csvEscape(data["日付"].orEmpty())}\n")
    sb.append("開始時間,${csvEscape(data["開始時間"].orEmpty())}\n")
    sb.append("終了時間,${csvEscape(data["終了時間"].orEmpty())}\n")
    sb.append("作業員,${csvEscape(data["作業員"].orEmpty())}\n")
    sb.append("備考,${csvEscape(data["備考"].orEmpty())}\n")
    sb.append("対象機器,${csvEscape(idInfo.brand.label)}\n\n")

    // 画面と同じ「場所=縦 / Ch=横」レイアウト
    // ヘッダー: 1列目は項目、以降は各Ch（ch番号＋10桁ID）
    val fieldDefs = listOf("結果", "電波強度送信", "電波強度受信", "ノイズ送信", "ノイズ受信", "送信ﾊﾟｹ", "受信ﾊﾟｹ")
    sb.append("項目＼Ch")
    rows.forEach { row -> sb.append(",${csvEscape("ch:${row.ch} (${row.fullId.ifEmpty { "-" }})")}") }
    sb.append("\n")

    for (loc in 1..locCount) {
        val rec = ordered.getOrNull(loc - 1)
        val distLabel = if (rec != null) "${rec.memo} = ${rec.display(unit)}" else ""
        // 場所見出し行（距離メモ＋距離）
        sb.append("【場所$loc】,${csvEscape(distLabel)}\n")
        // 項目ごとの行（Ch列に値）
        fieldDefs.forEach { f ->
            sb.append(csvEscape(f))
            rows.forEach { row -> sb.append(",${csvEscape(data["loc${loc}_ch${row.ch}_$f"].orEmpty())}") }
            sb.append("\n")
        }
        sb.append("\n")
    }
    return sb.toString().trimEnd()
}

private fun writeCsvFile(context: Context, csv: String): File {
    val file = File(context.cacheDir, "無線テスト結果_${stamp()}.csv")
    file.writeText("﻿$csv") // BOM付きでExcel文字化け防止
    return file
}

/** 作図PDFと結果CSVを添付してメール送信する。 */
private fun sendByEmail(
    context: Context, email: String, storeName: String, date: String,
    idInfo: IdInfo, csv: String, data: Map<String, String>
) {
    try {
        val uris = ArrayList<Uri>()
        val auth = "${context.packageName}.fileprovider"

        // 結果CSV
        val csvFile = writeCsvFile(context, csv)
        uris.add(FileProvider.getUriForFile(context, auth, csvFile))

        // 作図PDF（端末に保存済みの作図データから生成。無い場合は添付なし）
        val drawFile = renderSavedDrawing(context, pdf = true)
        if (drawFile != null) uris.add(FileProvider.getUriForFile(context, auth, drawFile))

        val subject = listOf("無線チャンネル変更", storeName, date)
            .filter { it.isNotBlank() }.joinToString(" ")

        val body = buildString {
            append("お疲れ様です。\n")
            append("無線チャンネル変更作業の結果を送付いたします。\n\n")
            if (storeName.isNotBlank()) append("店舗名：$storeName\n")
            if (!data["変更後システムID"].isNullOrBlank()) append("変更後システムID：${data["変更後システムID"]}\n")
            if (date.isNotBlank()) append("日付：$date\n")
            append("対象機器：${idInfo.brand.label}\n")
            if (!data["作業員"].isNullOrBlank()) append("作業員：${data["作業員"]}\n")
            append("\n添付ファイル：\n")
            append("・見取り図（PDF）")
            if (drawFile == null) append("　※作図データが無いため未添付")
            append("\n・無線テスト結果表（CSV）\n\n")
            append("ご確認のほど、よろしくお願いいたします。")
        }

        val intent = Intent(Intent.ACTION_SEND_MULTIPLE).apply {
            type = "application/octet-stream"
            if (email.isNotBlank()) putExtra(Intent.EXTRA_EMAIL, arrayOf(email))
            putExtra(Intent.EXTRA_SUBJECT, subject)
            putExtra(Intent.EXTRA_TEXT, body)
            putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(intent, "メールアプリを選択"))
    } catch (e: Exception) {
        Toast.makeText(context, "メール作成に失敗: ${e.message}", Toast.LENGTH_LONG).show()
    }
}

private fun exportCsv(context: Context, csv: String) {
    try {
        val file = File(context.cacheDir, "無線テスト結果_${stamp()}.csv")
        file.writeText("\uFEFF$csv") // BOM付きでExcel文字化け防止
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/csv"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(intent, "CSVを保存・共有"))
    } catch (e: Exception) {
        Toast.makeText(context, "CSV出力に失敗: ${e.message}", Toast.LENGTH_LONG).show()
    }
}

// ── 端末内保存 ──
private const val RESULT_PREFS = "result_prefs"
private const val RESULT_KEY = "data"

private fun saveResultMap(context: Context, data: Map<String, String>) {
    val s = data.entries.joinToString("\n") { "${it.key}=${it.value.replace("\n", " ")}" }
    context.getSharedPreferences(RESULT_PREFS, Context.MODE_PRIVATE).edit().putString(RESULT_KEY, s).apply()
}

private fun loadResultMap(context: Context): Map<String, String> {
    val s = context.getSharedPreferences(RESULT_PREFS, Context.MODE_PRIVATE).getString(RESULT_KEY, "") ?: ""
    if (s.isBlank()) return emptyMap()
    return s.split("\n").mapNotNull { line ->
        val idx = line.indexOf('=')
        if (idx <= 0) null else line.substring(0, idx) to line.substring(idx + 1)
    }.toMap()
}

private fun copyText(context: Context, text: String) {
    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
    cm.setPrimaryClip(android.content.ClipData.newPlainText("結果CSV", text))
    Toast.makeText(context, "コピーしました", Toast.LENGTH_SHORT).show()
}
