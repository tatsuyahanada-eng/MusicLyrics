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
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
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

// ───── 業務用ライト（薄い灰色）テーマ ─────
private val Accent = Color(0xFF00897B)
private val LightScheme = lightColorScheme(
    primary = Accent,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFB2DFDB),
    onPrimaryContainer = Color(0xFF00332C),
    secondaryContainer = Color(0xFFCDEFEA),
    onSecondaryContainer = Color(0xFF00332C),
    background = Color(0xFFECEFF1),
    onBackground = Color(0xFF1B1B1B),
    surface = Color(0xFFF5F6F8),
    onSurface = Color(0xFF1B1B1B),
    surfaceVariant = Color(0xFFE1E5E8),
    onSurfaceVariant = Color(0xFF49454F),
    outline = Color(0xFFAFB6BC),
)

// セクションごとの色（規格a / 規格b / Ch設定値 / 番号 / CD）— 淡色で区別、文字は濃色
private val ColKikakuA = Color(0xFFB0BEC5) // ブルーグレー
private val ColKikakuB = Color(0xFFD7CCC8) // ブラウン
private val ColCh = Color(0xFFCFD8DC)
private val ColNum = Color(0xFFE3E7EA)
private val ColCd = Color(0xFF80CBC4)      // ティール（CD強調）
private val ColEmpty = Color(0xFFEEF1F3)

// 表のレイアウト寸法
private val CELL_H = 36.dp
private val LABEL_W = 52.dp

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MaterialTheme(colorScheme = LightScheme) {
                AppRoot()
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppRoot() {
    var tab by remember { mutableIntStateOf(0) }
    val titles = listOf("無線チャンネル変更APP", "距離測定", "作図（見取り図）", "結果入力（無線テスト結果表）")
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(titles[tab], fontWeight = FontWeight.SemiBold) },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                    titleContentColor = MaterialTheme.colorScheme.onSurface
                )
            )
        },
        bottomBar = {
            NavigationBar(containerColor = MaterialTheme.colorScheme.surface) {
                NavigationBarItem(
                    selected = tab == 0, onClick = { tab = 0 },
                    icon = { Text("🔢") }, label = { Text("ID計算") }
                )
                NavigationBarItem(
                    selected = tab == 1, onClick = { tab = 1 },
                    icon = { Text("📏") }, label = { Text("距離") }
                )
                NavigationBarItem(
                    selected = tab == 2, onClick = { tab = 2 },
                    icon = { Text("🗺️") }, label = { Text("作図") }
                )
                NavigationBarItem(
                    selected = tab == 3, onClick = { tab = 3 },
                    icon = { Text("📝") }, label = { Text("結果") }
                )
            }
        }
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding)) {
            when (tab) {
                0 -> IdContent()
                1 -> MeasureScreen(MeasureType.DISTANCE)
                2 -> DrawScreen()
                else -> ResultScreen()
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun IdContent() {
    val context = LocalContext.current
    val saved = remember { loadIdInfo(context) }
    var brand by remember { mutableStateOf(saved.brand) }
    var storeName by remember { mutableStateOf(saved.storeName) }
    var storeNumber by remember { mutableStateOf(saved.storeNumber) }
    var expanded by remember { mutableStateOf(false) }

    val rows = buildRows(brand, storeNumber)
    val valid = isValidStoreNumber(storeNumber)

    // 結果入力タブへ自動反映するため店舗情報を保存
    LaunchedEffect(brand, storeNumber, storeName) {
        saveIdInfo(context, brand, storeNumber, storeName)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
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
                    label = { Text("対象機器") },
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
                label = { Text("共通番号（5桁）") },
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

            // ── 表（現行ID/変更後ID・規格a/b・ch・10桁ID）──
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .border(1.dp, Color(0xFF2E7D32), RoundedCornerShape(4.dp))
            ) {
                rows.forEachIndexed { index, row ->
                    TableRow(index = index, row = row, onClick = {
                        if (row.fullId.isNotEmpty()) copyToClipboard(context, row.fullId)
                    })
                    if (index < rows.size - 1) {
                        HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.5f))
                    }
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

// 規格a/b を表す小箱（該当側を黒で強調）
private val LetterA = Color(0xFFE65100) // a の文字色（橙）
private val LetterB = Color(0xFF1565C0) // b の文字色（青）

@Composable
private fun ABCell(letter: String, active: Boolean, letterColor: Color) {
    Box(
        modifier = Modifier
            .width(30.dp)
            .height(30.dp)
            .background(if (active) Color(0xFF111111) else Color.White, RoundedCornerShape(3.dp))
            .border(0.8.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(3.dp)),
        contentAlignment = Alignment.Center
    ) {
        Text(letter, color = letterColor, fontWeight = FontWeight.Bold, fontSize = 16.sp)
    }
}

@Composable
private fun TableRow(index: Int, row: IdRow, onClick: () -> Unit) {
    val kikakuB = row.chCode.take(1)     // G（規格b）: "0"→a側, "1"→b側
    val aActive = kikakuB == "0"
    val label = when (index) {
        0 -> "現行ID"
        1 -> "変更後ID"
        else -> ""
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = row.fullId.isNotEmpty(), onClick = onClick)
            .padding(vertical = 4.dp, horizontal = 6.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(modifier = Modifier.width(64.dp)) {
                Text(label, fontSize = 12.sp, fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface)
            }
            ABCell("a", aActive, LetterA)
            Spacer(Modifier.width(4.dp))
            ABCell("b", !aActive, LetterB)
            Spacer(Modifier.width(10.dp))
            Text("ch: ${row.ch}", fontSize = 16.sp, fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface)
        }
        // 10桁ID（改行して表示）
        Text(
            text = if (row.fullId.isNotEmpty()) row.fullId else "----------",
            modifier = Modifier.fillMaxWidth().padding(top = 2.dp),
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold,
            fontSize = 14.sp,
            letterSpacing = 1.sp,
            textAlign = TextAlign.Center,
            color = if (row.fullId.isNotEmpty()) MaterialTheme.colorScheme.primary
            else MaterialTheme.colorScheme.outline
        )
    }
}

private fun copyToClipboard(context: Context, text: String) {
    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    cm.setPrimaryClip(ClipData.newPlainText("ID", text))
    Toast.makeText(context, "コピー: $text", Toast.LENGTH_SHORT).show()
}
