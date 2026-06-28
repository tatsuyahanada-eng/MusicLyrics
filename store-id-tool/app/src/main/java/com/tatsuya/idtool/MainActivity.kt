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

// セクションごとの色（規格a / 規格b / Ch設定値 / 番号 / CD）— 見た目を明確に分ける
private val ColKikakuA = Color(0xFF455A64) // ブルーグレー
private val ColKikakuB = Color(0xFF5D4037) // ブラウン
private val ColCh = Color(0xFF37474F)
private val ColNum = Color(0xFF263238)
private val ColCd = Accent
private val ColEmpty = Color(0xFF20242A)

// 表のレイアウト寸法
private val CELL_H = 36.dp
private val LABEL_W = 52.dp

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
                label = { Text("店舗番号（5桁）") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                isError = storeNumber.isNotEmpty() && !valid,
                supportingText = if (storeNumber.isNotEmpty() && !valid) {
                    { Text("5桁の数字を入力してください（残り ${5 - storeNumber.length} 桁）") }
                } else null,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 4.dp)
            )

            Spacer(Modifier.height(10.dp))

            // ── 表（Excel のセル並びを再現。画面幅に収める）──
            Column(modifier = Modifier.fillMaxWidth()) {
                TableHeader()
                HorizontalDivider(color = MaterialTheme.colorScheme.outline)
                rows.forEach { row ->
                    TableRow(row = row, onClick = {
                        if (row.fullId.isNotEmpty()) copyToClipboard(context, row.fullId)
                    })
                    HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.4f))
                }
            }

            Spacer(Modifier.height(6.dp))
            Text(
                "行をタップで10桁IDをコピー",
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 16.dp)
            )
        }
    }
}

@Composable
private fun TableHeader() {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(modifier = Modifier.width(LABEL_W), contentAlignment = Alignment.Center) {
            Text("ch", fontSize = 11.sp, fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Row(modifier = Modifier.weight(1f)) {
            HeaderCell("規格a", 1f)
            HeaderCell("規格b", 1f)
            HeaderCell("Ch設定値", 2f)
            HeaderCell("番号", 5f)
            HeaderCell("CD", 1f)
        }
    }
}

@Composable
private fun TableRow(row: IdRow, onClick: () -> Unit) {
    val hasStore = row.store.isNotEmpty()
    val kikakuA = row.kikaku             // F（規格a）
    val kikakuB = row.chCode.take(1)     // G（規格b）
    val chSet = row.chCode.drop(1)       // H,I（Ch設定値 2桁）

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = row.fullId.isNotEmpty(), onClick = onClick)
            .padding(vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(modifier = Modifier.width(LABEL_W), contentAlignment = Alignment.Center) {
            Text(
                "ch:${row.ch}",
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary
            )
        }
        Row(modifier = Modifier.weight(1f)) {
            DigitCell(kikakuA, ColKikakuA)                              // 規格a
            DigitCell(kikakuB, ColKikakuB)                             // 規格b
            DigitCell(chSet.getOrNull(0)?.toString() ?: "", ColCh)    // Ch設定値 H
            DigitCell(chSet.getOrNull(1)?.toString() ?: "", ColCh)    // Ch設定値 I
            repeat(5) { i ->                                           // 番号 J〜N
                DigitCell(
                    if (hasStore) row.store[i].toString() else "",
                    if (hasStore) ColNum else ColEmpty
                )
            }
            DigitCell(                                                 // CD
                row.cd,
                if (row.cd.isNotEmpty()) ColCd else ColEmpty,
                cd = true
            )
        }
    }
}

@Composable
private fun androidx.compose.foundation.layout.RowScope.DigitCell(
    d: String,
    tint: Color,
    cd: Boolean = false
) {
    Box(
        modifier = Modifier
            .weight(1f)
            .height(CELL_H)
            .padding(1.dp)
            .background(tint, RoundedCornerShape(3.dp))
            .border(0.7.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(3.dp)),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = d,
            fontFamily = FontFamily.Monospace,
            fontWeight = if (cd) FontWeight.Bold else FontWeight.Medium,
            fontSize = if (cd) 16.sp else 14.sp,
            color = if (cd) MaterialTheme.colorScheme.onPrimary
            else MaterialTheme.colorScheme.onSurface
        )
    }
}

@Composable
private fun androidx.compose.foundation.layout.RowScope.HeaderCell(text: String, weight: Float) {
    Box(modifier = Modifier.weight(weight), contentAlignment = Alignment.Center) {
        Text(
            text = text,
            fontSize = 9.sp,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            maxLines = 1
        )
    }
}

private fun copyToClipboard(context: Context, text: String) {
    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    cm.setPrimaryClip(ClipData.newPlainText("ID", text))
    Toast.makeText(context, "コピー: $text", Toast.LENGTH_SHORT).show()
}
