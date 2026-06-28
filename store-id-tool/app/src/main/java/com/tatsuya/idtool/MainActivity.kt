package com.tatsuya.idtool

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.material3.ExposedDropdownMenu
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// ───── シックな業務用ダークテーマ ─────
private val Accent = Color(0xFF26A69A)
private val DarkScheme = darkColorScheme(
    primary = Accent,
    onPrimary = Color(0xFF00251A),
    primaryContainer = Color(0xFF18222A),
    onPrimaryContainer = Color(0xFFDCE3E8),
    secondaryContainer = Color(0xFF005048),
    onSecondaryContainer = Color(0xFFB8F1E8),
    background = Color(0xFF121417),
    onBackground = Color(0xFFE2E5E8),
    surface = Color(0xFF1B1F24),
    onSurface = Color(0xFFE2E5E8),
    surfaceVariant = Color(0xFF2A2F36),
    onSurfaceVariant = Color(0xFFAEB6BE),
    outline = Color(0xFF49525B),
)

// セクションごとの色（規格 / Ch設定値 / 番号 / CD）
private val ColKikaku = Color(0xFF37474F)
private val ColCh = Color(0xFF2C3E50)
private val ColNum = Color(0xFF243B49)
private val ColCd = Accent
private val ColEmpty = Color(0xFF20242A)

// 表のレイアウト寸法
private val BOX = 30.dp
private val CELL_H = 38.dp
private val LABEL_W = 104.dp
private val IDCOL_W = 118.dp
private val GROUP_GAP = 6.dp

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MaterialTheme(colorScheme = DarkScheme) {
                IdToolScreen()
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun IdToolScreen() {
    var brand by remember { mutableStateOf(Brand.RELIER) }
    var storeName by remember { mutableStateOf("") }
    var storeNumber by remember { mutableStateOf("") }
    var expanded by remember { mutableStateOf(false) }
    val context = LocalContext.current

    val rows = buildRows(brand, storeNumber)
    val valid = isValidStoreNumber(storeNumber)

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("無線チャンネル変更APP", fontWeight = FontWeight.SemiBold) },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                    titleContentColor = MaterialTheme.colorScheme.onSurface
                )
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
        ) {
            // ── ブランド選択（プルダウン）──
            ExposedDropdownMenuBox(
                expanded = expanded,
                onExpandedChange = { expanded = it },
                modifier = Modifier.padding(top = 12.dp)
            ) {
                OutlinedTextField(
                    value = brand.label,
                    onValueChange = {},
                    readOnly = true,
                    label = { Text("ブランド") },
                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
                    modifier = Modifier
                        .menuAnchor(MenuAnchorType.PrimaryNotEditable)
                        .fillMaxWidth()
                )
                ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                    Brand.entries.forEach { b ->
                        DropdownMenuItem(
                            text = { Text(b.label) },
                            onClick = {
                                brand = b
                                expanded = false
                            }
                        )
                    }
                }
            }

            OutlinedTextField(
                value = storeName,
                onValueChange = { storeName = it },
                label = { Text("店名（空欄で可）") },
                singleLine = true,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 10.dp)
            )

            OutlinedTextField(
                value = storeNumber,
                onValueChange = { input -> storeNumber = input.filter { it.isDigit() }.take(5) },
                label = { Text("店舗番号 5桁（中央の 67200 部分）") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                isError = storeNumber.isNotEmpty() && !valid,
                supportingText = {
                    Text(
                        if (valid || storeNumber.isEmpty())
                            "規格・Ch設定値 は固定。番号を入れると下の表（10桁ID）が自動計算されます。"
                        else "5桁の数字を入力してください（残り ${5 - storeNumber.length} 桁）"
                    )
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 4.dp)
            )

            Spacer(Modifier.height(12.dp))

            // ── 表（Excel のセル並びを再現。横スクロール可）──
            val hScroll = rememberScrollState()
            Column(modifier = Modifier.horizontalScroll(hScroll)) {
                TableHeader()
                HorizontalDivider(color = MaterialTheme.colorScheme.outline)
                rows.forEach { row ->
                    TableRow(row = row, onClick = {
                        if (row.fullId.isNotEmpty()) copyToClipboard(context, row.fullId)
                    })
                    HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.4f))
                }
            }

            Spacer(Modifier.height(8.dp))
            Text(
                "行をタップすると10桁IDをコピーします。",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 20.dp)
            )
        }
    }
}

@Composable
private fun TableHeader() {
    Row(
        modifier = Modifier.padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        HeaderText("規格 / ch", LABEL_W)
        GroupCaption("規格", 1)
        Spacer(Modifier.width(GROUP_GAP))
        GroupCaption("Ch設定値", 3)
        Spacer(Modifier.width(GROUP_GAP))
        GroupCaption("番号", 5)
        Spacer(Modifier.width(GROUP_GAP))
        GroupCaption("CD", 1)
        Spacer(Modifier.width(GROUP_GAP))
        HeaderText("ID（10桁）", IDCOL_W)
    }
}

@Composable
private fun TableRow(row: IdRow, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .clickable(enabled = row.fullId.isNotEmpty(), onClick = onClick)
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // 左ラベル：規格：a b / ｃｈ：N
        Column(modifier = Modifier.width(LABEL_W)) {
            Text("規格：a b", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurface)
            Text(
                "ｃｈ：${row.ch}",
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary
            )
        }

        val hasStore = row.store.isNotEmpty()

        // 規格（1桁・常に表示）
        DigitGroup(row.kikaku.toList().map { it.toString() }, ColKikaku)
        Spacer(Modifier.width(GROUP_GAP))
        // Ch設定値（3桁・常に表示）
        DigitGroup(row.chCode.toList().map { it.toString() }, ColCh)
        Spacer(Modifier.width(GROUP_GAP))
        // 番号（5桁・未入力なら空欄）
        DigitGroup(
            if (hasStore) row.store.toList().map { it.toString() } else List(5) { "" },
            if (hasStore) ColNum else ColEmpty
        )
        Spacer(Modifier.width(GROUP_GAP))
        // CD（1桁・自動／未入力なら空欄）
        DigitGroup(listOf(row.cd.ifEmpty { "" }), if (row.cd.isNotEmpty()) ColCd else ColEmpty, cd = true)
        Spacer(Modifier.width(GROUP_GAP))

        // 10桁ID（連結表示）
        Box(modifier = Modifier.width(IDCOL_W), contentAlignment = Alignment.Center) {
            Text(
                text = row.fullId.ifEmpty { "—" },
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                fontSize = 15.sp,
                color = if (row.fullId.isNotEmpty()) MaterialTheme.colorScheme.onSurface
                else MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun DigitGroup(digits: List<String>, tint: Color, cd: Boolean = false) {
    Row {
        digits.forEach { d ->
            Box(
                modifier = Modifier
                    .width(BOX)
                    .height(CELL_H)
                    .background(tint, RoundedCornerShape(3.dp))
                    .border(0.7.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(3.dp)),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = d,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = if (cd) FontWeight.Bold else FontWeight.Medium,
                    fontSize = if (cd) 18.sp else 16.sp,
                    color = if (cd) MaterialTheme.colorScheme.onPrimary
                    else MaterialTheme.colorScheme.onSurface
                )
            }
        }
    }
}

@Composable
private fun HeaderText(text: String, width: androidx.compose.ui.unit.Dp) {
    Box(modifier = Modifier.width(width), contentAlignment = Alignment.Center) {
        Text(
            text = text,
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
    }
}

@Composable
private fun GroupCaption(text: String, cells: Int) {
    Box(
        modifier = Modifier.width(BOX * cells),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = text,
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
    }
}

private fun copyToClipboard(context: Context, text: String) {
    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    cm.setPrimaryClip(ClipData.newPlainText("ID", text))
    Toast.makeText(context, "コピー: $text", Toast.LENGTH_SHORT).show()
}
