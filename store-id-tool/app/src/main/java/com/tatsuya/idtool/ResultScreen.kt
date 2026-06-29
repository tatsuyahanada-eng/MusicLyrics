package com.tatsuya.idtool

import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
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

private val ResultOptions = listOf("最適", "良", "圏外")

private fun stamp(): String = SimpleDateFormat("yyyyMMdd_HHmm", Locale.JAPAN).format(Date())

@Composable
fun ResultScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current

    val idInfo = remember { loadIdInfo(context) }
    val rows = remember { buildRows(idInfo.brand, idInfo.storeNumber) }
    // 測定順（古い順）。距離タブは新しい順に保存されるため反転して場所1=最初の測定に合わせる
    val ordered = remember { loadDistanceRecords(context).asReversed() }

    val data = remember { mutableStateMapOf<String, String>().apply { putAll(loadResultMap(context)) } }
    val set: (String, String) -> Unit = { k, v -> data[k] = v; saveResultMap(context, data) }

    var loc by remember { mutableIntStateOf(1) }
    var locCount by remember { mutableIntStateOf((data["場所数"]?.toIntOrNull() ?: 5).coerceAtLeast(5)) }

    LaunchedEffect(Unit) {
        if (data["店番"].isNullOrBlank() && idInfo.storeNumber.isNotBlank()) data["店番"] = idInfo.storeNumber
        if (data["店舗名"].isNullOrBlank() && idInfo.storeName.isNotBlank()) data["店舗名"] = idInfo.storeName
        saveResultMap(context, data)
    }

    Column(
        modifier = modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)
    ) {
        Text("無線テスト結果表", fontWeight = FontWeight.Bold, fontSize = 18.sp,
            color = MaterialTheme.colorScheme.onBackground)

        Spacer(Modifier.height(8.dp))

        // ── ヘッダー ──
        TextField2("店番", data, set)
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

        Spacer(Modifier.height(6.dp))
        Text("対象機器: ${idInfo.brand.label}", fontSize = 13.sp,
            fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
        if (!isValidStoreNumber(idInfo.storeNumber)) {
            Text("※ ID計算タブで店舗番号5桁を入力すると10桁IDが反映されます",
                fontSize = 11.sp, color = MaterialTheme.colorScheme.error)
        }

        // ── 場所切替（始点→終点で計測した地点ごと、デフォルト5）──
        Spacer(Modifier.height(10.dp))
        Text("場所を選択（始点→終点の計測地点ごと）", fontWeight = FontWeight.Bold, fontSize = 13.sp,
            color = MaterialTheme.colorScheme.onBackground)
        Row(modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            (1..locCount).forEach { n ->
                FilterChip(selected = loc == n, onClick = { loc = n },
                    label = { Text("場所$n") })
            }
            FilterChip(selected = false, onClick = { locCount += 1; set("場所数", locCount.toString()) },
                label = { Text("＋追加") })
        }
        // 対応する距離記録（測定順：場所1=最初に測定）
        ordered.getOrNull(loc - 1)?.let {
            Text("距離(${loc}番目の測定): ${it.memo} = ${it.display()}", fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp))
        }

        Spacer(Modifier.height(8.dp))
        Text("Ch別 結果入力（場所$loc）", fontWeight = FontWeight.Bold, fontSize = 14.sp,
            color = MaterialTheme.colorScheme.onBackground)

        rows.forEach { row ->
            val idText = if (row.fullId.isNotEmpty()) row.fullId else "（番号未入力）"
            fun key(f: String) = "loc${loc}_ch${row.ch}_$f"
            Column(
                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)
                    .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(8.dp))
                    .padding(8.dp)
            ) {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("ch:${row.ch}", fontWeight = FontWeight.Bold, fontSize = 14.sp,
                        color = MaterialTheme.colorScheme.primary)
                    Text(idText, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold,
                        fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurface)
                }
                Spacer(Modifier.height(4.dp))
                ResultDropdown(data[key("結果")] ?: "", { set(key("結果"), it) })
                Spacer(Modifier.height(4.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    NumField("電波送信", data[key("電波強度送信")] ?: "", { set(key("電波強度送信"), it) }, Modifier.weight(1f))
                    NumField("電波受信", data[key("電波強度受信")] ?: "", { set(key("電波強度受信"), it) }, Modifier.weight(1f))
                }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    NumField("ﾉｲｽﾞ送信", data[key("ノイズ送信")] ?: "", { set(key("ノイズ送信"), it) }, Modifier.weight(1f))
                    NumField("ﾉｲｽﾞ受信", data[key("ノイズ受信")] ?: "", { set(key("ノイズ受信"), it) }, Modifier.weight(1f))
                }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    NumField("送信ﾊﾟｹ", data[key("送信ﾊﾟｹ")] ?: "", { set(key("送信ﾊﾟｹ"), it) }, Modifier.weight(1f))
                    NumField("受信ﾊﾟｹ", data[key("受信ﾊﾟｹ")] ?: "", { set(key("受信ﾊﾟｹ"), it) }, Modifier.weight(1f))
                }
            }
        }

        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(
                onClick = { copyText(context, buildCsv(idInfo, rows, data, locCount, ordered)) },
                modifier = Modifier.weight(1f)
            ) { Text("CSVコピー") }
            OutlinedButton(
                onClick = { exportCsv(context, buildCsv(idInfo, rows, data, locCount, ordered)) },
                modifier = Modifier.weight(1f)
            ) { Text("CSV出力") }
        }
        Spacer(Modifier.height(24.dp))
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
    locCount: Int, ordered: List<Record>
): String {
    val sb = StringBuilder()
    sb.append("店番,${csvEscape(data["店番"].orEmpty())}\n")
    sb.append("店舗名,${csvEscape(data["店舗名"].orEmpty())}\n")
    sb.append("日付,${csvEscape(data["日付"].orEmpty())}\n")
    sb.append("開始時間,${csvEscape(data["開始時間"].orEmpty())}\n")
    sb.append("終了時間,${csvEscape(data["終了時間"].orEmpty())}\n")
    sb.append("作業員,${csvEscape(data["作業員"].orEmpty())}\n")
    sb.append("備考,${csvEscape(data["備考"].orEmpty())}\n")
    sb.append("対象機器,${csvEscape(idInfo.brand.label)}\n\n")
    sb.append("場所,距離メモ,距離,Ch,ID,結果,電波強度送信,電波強度受信,ノイズ送信,ノイズ受信,送信ﾊﾟｹ,受信ﾊﾟｹ\n")
    for (loc in 1..locCount) {
        val rec = ordered.getOrNull(loc - 1)
        val distMemo = rec?.memo ?: ""
        val distVal = rec?.display() ?: ""
        rows.forEach { row ->
            fun v(f: String) = data["loc${loc}_ch${row.ch}_$f"].orEmpty()
            val fields = listOf(
                v("結果"), v("電波強度送信"), v("電波強度受信"),
                v("ノイズ送信"), v("ノイズ受信"), v("送信ﾊﾟｹ"), v("受信ﾊﾟｹ")
            )
            if (fields.any { it.isNotEmpty() }) {
                val id = if (row.fullId.isNotEmpty()) row.fullId else ""
                sb.append("場所$loc,${csvEscape(distMemo)},${csvEscape(distVal)},${row.ch},$id,")
                sb.append(fields.joinToString(",") { csvEscape(it) })
                sb.append("\n")
            }
        }
    }
    return sb.toString().trimEnd()
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
