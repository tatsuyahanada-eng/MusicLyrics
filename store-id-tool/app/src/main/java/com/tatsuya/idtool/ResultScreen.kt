package com.tatsuya.idtool

import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshots.SnapshotStateMap
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val ChFields = listOf(
    "結果", "電波強度送信", "電波強度受信", "ノイズ送信", "ノイズ受信", "送信ﾊﾟｹ", "受信ﾊﾟｹ"
)

@Composable
fun ResultScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current

    // ID計算・距離タブから自動反映（タブを開くたびに最新を読み込む）
    val idInfo = remember { loadIdInfo(context) }
    val rows = remember { buildRows(idInfo.brand, idInfo.storeNumber) }
    val distance = remember { loadDistanceRecords(context) }

    val data = remember { mutableStateMapOf<String, String>().apply { putAll(loadResultMap(context)) } }
    val set: (String, String) -> Unit = { k, v -> data[k] = v; saveResultMap(context, data) }

    // ヘッダーの初期反映（未入力なら店舗情報を流し込む）
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
        Text("ID計算・距離タブの内容を自動反映しています",
            fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)

        Spacer(Modifier.height(8.dp))

        // ── ヘッダー ──
        Field("店番", data, set)
        Field("店舗名", data, set)
        Field("日付", data, set)
        Field("作業員", data, set)
        Field("備考", data, set, single = false)

        Spacer(Modifier.height(8.dp))
        Text("対象ブランド: ${idInfo.brand.label}", fontSize = 13.sp,
            fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
        if (!isValidStoreNumber(idInfo.storeNumber)) {
            Text("※ ID計算タブで店舗番号5桁を入力すると10桁IDが反映されます",
                fontSize = 11.sp, color = MaterialTheme.colorScheme.error)
        }

        // ── 検証場所（距離タブの記録を自動反映）──
        Spacer(Modifier.height(10.dp))
        SectionTitle("検証場所（距離タブより自動反映）")
        if (distance.isEmpty()) {
            Text("（距離タブで測定すると一覧表示されます）",
                fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            Column(
                modifier = Modifier.fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(8.dp))
                    .padding(8.dp)
            ) {
                distance.forEachIndexed { i, r ->
                    Text("${i + 1}. ${r.memo}: ${r.display()}", fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurface)
                }
            }
        }

        // ── Ch ごとの結果入力（Ch=10桁ID 自動反映）──
        Spacer(Modifier.height(10.dp))
        SectionTitle("Ch別 結果入力（Ch列=10桁IDを自動反映）")

        rows.forEach { row ->
            val idText = if (row.fullId.isNotEmpty()) row.fullId else "（番号未入力）"
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
                ChFields.forEach { f ->
                    Field("$f", data, set, keyOverride = "ch${row.ch}_$f", compact = true)
                }
            }
        }

        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(
                onClick = { copyResult(context, buildResultReport(idInfo, rows, distance, data)) },
                modifier = Modifier.weight(1f)
            ) { Text("コピー") }
            OutlinedButton(
                onClick = { shareResult(context, buildResultReport(idInfo, rows, distance, data)) },
                modifier = Modifier.weight(1f)
            ) { Text("共有（保存）") }
        }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(text, fontWeight = FontWeight.Bold, fontSize = 14.sp,
        color = MaterialTheme.colorScheme.onBackground,
        modifier = Modifier.padding(bottom = 4.dp))
}

@Composable
private fun Field(
    label: String,
    data: SnapshotStateMap<String, String>,
    set: (String, String) -> Unit,
    keyOverride: String? = null,
    single: Boolean = true,
    compact: Boolean = false
) {
    val key = keyOverride ?: label
    OutlinedTextField(
        value = data[key] ?: "",
        onValueChange = { set(key, it) },
        label = { Text(label, fontSize = if (compact) 11.sp else 13.sp) },
        singleLine = single,
        modifier = Modifier.fillMaxWidth().padding(vertical = if (compact) 2.dp else 4.dp)
    )
}

private fun buildResultReport(
    idInfo: IdInfo,
    rows: List<IdRow>,
    distance: List<Record>,
    data: Map<String, String>
): String {
    val sb = StringBuilder()
    sb.append("無線テスト結果表\n")
    sb.append("店番: ${data["店番"].orEmpty()}\n")
    sb.append("店舗名: ${data["店舗名"].orEmpty()}\n")
    sb.append("日付: ${data["日付"].orEmpty()}\n")
    sb.append("作業員: ${data["作業員"].orEmpty()}\n")
    sb.append("備考: ${data["備考"].orEmpty()}\n")
    sb.append("対象ブランド: ${idInfo.brand.label}\n\n")

    sb.append("【検証場所（距離）】\n")
    if (distance.isEmpty()) sb.append("（なし）\n")
    else distance.forEachIndexed { i, r -> sb.append("${i + 1}. ${r.memo}: ${r.display()}\n") }
    sb.append("\n")

    sb.append("【Ch別 結果】\n")
    rows.forEach { row ->
        val id = if (row.fullId.isNotEmpty()) row.fullId else "-"
        sb.append("■ ch:${row.ch}  ID:$id\n")
        fun v(f: String) = data["ch${row.ch}_$f"].orEmpty()
        sb.append("  結果: ${v("結果")}\n")
        sb.append("  電波強度平均 送信: ${v("電波強度送信")} / 受信: ${v("電波強度受信")}\n")
        sb.append("  ノイズ 送信: ${v("ノイズ送信")} / 受信: ${v("ノイズ受信")}\n")
        sb.append("  送信ﾊﾟｹ: ${v("送信ﾊﾟｹ")} / 受信ﾊﾟｹ: ${v("受信ﾊﾟｹ")}\n")
    }
    return sb.toString().trimEnd()
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

private fun copyResult(context: Context, text: String) {
    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
    cm.setPrimaryClip(android.content.ClipData.newPlainText("結果", text))
    Toast.makeText(context, "コピーしました", Toast.LENGTH_SHORT).show()
}

private fun shareResult(context: Context, text: String) {
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"; putExtra(Intent.EXTRA_TEXT, text)
    }
    context.startActivity(Intent.createChooser(intent, "結果を保存・共有"))
}
