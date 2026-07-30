package com.tatsuya.idtool

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ActivityInfo
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
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
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
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
    val context = LocalContext.current
    var tab by remember { mutableIntStateOf(0) }
    var resetKey by remember { mutableIntStateOf(0) }
    var showReset by remember { mutableStateOf(false) }
    var showSplash by remember { mutableStateOf(true) }
    val titles = listOf("無線チャンネル変更", "距離測定", "作図（見取り図）", "結果入力（無線テスト結果表）")

    // 起動時のスプラッシュ（ロゴ＋タイトルを中央表示）
    LaunchedEffect(Unit) {
        kotlinx.coroutines.delay(1900)
        showSplash = false
    }
    if (showSplash) {
        SplashScreen()
        return
    }

    // 作図(2)・結果入力(3)のみ横向きを許可。他タブは縦固定。
    LaunchedEffect(tab) {
        context.findActivity()?.requestedOrientation = when (tab) {
            2, 3 -> ActivityInfo.SCREEN_ORIENTATION_FULL_USER
            else -> ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        }
    }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(titles[tab], fontWeight = FontWeight.SemiBold) },
                actions = {
                    TextButton(onClick = { showReset = true }) { Text("初期化") }
                },
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
            key(resetKey) {
                when (tab) {
                    0 -> IdContent()
                    1 -> MeasureScreen(MeasureType.DISTANCE)
                    2 -> DrawScreen()
                    else -> ResultScreen()
                }
            }
        }
    }

    if (showReset) {
        AlertDialog(
            onDismissRequest = { showReset = false },
            title = { Text("初期化の確認") },
            text = { Text("ID・距離・結果・作図のすべての記録を消去し、最初からやり直します。よろしいですか？") },
            confirmButton = {
                TextButton(onClick = {
                    clearAllData(context)
                    showReset = false
                    tab = 0
                    resetKey++ // 各タブを作り直して空の状態から再読込
                }) { Text("初期化する") }
            },
            dismissButton = { TextButton(onClick = { showReset = false }) { Text("やめる") } }
        )
    }
}

// ── 起動時スプラッシュ（ロゴ＋タイトル中央／下部にProduced by Welsys）──
@Composable
private fun SplashScreen() {
    Box(
        modifier = Modifier.fillMaxSize().background(Color.White),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Image(
                painter = painterResource(R.drawable.welsys_logo),
                contentDescription = "WELSYS",
                modifier = Modifier.width(240.dp)
            )
            Spacer(Modifier.height(4.dp))
            Text(
                "無線チャンネル変更アプリ",
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold,
                color = Color(0xFF37474F)
            )
        }
        Text(
            "Produced by Welsys",
            fontSize = 11.sp,
            color = Color(0xFF90A4AE),
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 28.dp)
        )
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
    // 長押しで「現行／変更後 どちらのシステムIDにコピーするか」を選ぶ対象行
    var pickRow by remember { mutableStateOf<IdRow?>(null) }

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

            // ── 共通番号（5桁）入力：目立たせて、チェックデジットを入力しないことを明示 ──
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp)
                    .background(Color(0xFFFFF8E1), RoundedCornerShape(10.dp))
                    .border(2.dp, Color(0xFFFFB300), RoundedCornerShape(10.dp))
                    .padding(horizontal = 14.dp, vertical = 12.dp)
            ) {
                Text(
                    "ここに『共通番号（5桁）』を入力",
                    fontWeight = FontWeight.Bold, fontSize = 16.sp, color = Color(0xFFE65100)
                )
                Spacer(Modifier.height(3.dp))
                Text(
                    "システムID（10桁）の一番うしろ1桁は「チェックデジット」です。入力するのは、その手前の 5桁 だけ！",
                    fontSize = 13.sp, color = Color(0xFF5D4037)
                )
                Spacer(Modifier.height(8.dp))
                // 例示：太字の5桁が入力対象、末尾のCDは打ち消し線
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("例）", fontSize = 12.sp, color = Color(0xFF5D4037))
                    Text("5106", fontSize = 18.sp, fontFamily = FontFamily.Monospace, color = Color(0xFFBCAAA4))
                    Text(
                        "67200",
                        fontSize = 24.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace,
                        color = Color(0xFFE65100)
                    )
                    Text(
                        "7",
                        fontSize = 18.sp, fontFamily = FontFamily.Monospace, color = Color(0xFFBCAAA4),
                        textDecoration = TextDecoration.LineThrough
                    )
                    Spacer(Modifier.width(8.dp))
                    Text("→ 入力は ", fontSize = 12.sp, color = Color(0xFF5D4037))
                    Text("67200", fontSize = 14.sp, fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace, color = Color(0xFFE65100))
                }
                Text(
                    "↑ 太字の5桁だけ入力（末尾のチェックデジット 1桁 は入力しない）",
                    fontSize = 11.sp, color = Color(0xFFE65100), fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(top = 2.dp)
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = storeNumber,
                    onValueChange = { input -> storeNumber = input.filter { it.isDigit() }.take(5) },
                    label = { Text("共通番号（5桁）") },
                    placeholder = { Text("例）67200", fontFamily = FontFamily.Monospace) },
                    singleLine = true,
                    textStyle = TextStyle(
                        fontSize = 26.sp, fontWeight = FontWeight.Bold,
                        letterSpacing = 8.sp, fontFamily = FontFamily.Monospace
                    ),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    isError = storeNumber.isNotEmpty() && !valid,
                    supportingText = {
                        if (storeNumber.isNotEmpty() && !valid) {
                            Text("あと ${5 - storeNumber.length} 桁（5桁の数字を入力）", color = MaterialTheme.colorScheme.error)
                        } else {
                            Text("末尾のチェックデジットは入れず、その手前の5桁を入力します")
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                )
            }

            Spacer(Modifier.height(10.dp))

            // ── 表（規格a/b・ch・10桁IDの□枠／ヘッダーは最上部に1回）──
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .border(1.dp, Color(0xFF2E7D32), RoundedCornerShape(4.dp))
            ) {
                IdTableHeader()
                HorizontalDivider(color = MaterialTheme.colorScheme.outline)
                rows.forEachIndexed { index, row ->
                    TableRow(
                        row = row,
                        onClick = {
                            if (row.fullId.isNotEmpty()) {
                                val v = "ch${row.ch} ${row.fullId}"
                                copyToClipboard(context, v)
                                saveSelectedSystemId(context, v) // 結果タブの変更後システムIDへ反映
                            }
                        },
                        onLongClick = { if (row.fullId.isNotEmpty()) pickRow = row }
                    )
                    if (index < rows.size - 1) {
                        HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.4f))
                    }
                }
            }

            Spacer(Modifier.height(6.dp))
            Text(
                "行をタップでch番号＋10桁IDをコピー／長押しで「現行・変更後」どちらに反映するか選べます",
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 16.dp)
            )
        }

    // 長押しメニュー：タップした行の10桁IDを「現行／変更後」どちらのシステムIDに反映するか選ぶ
    pickRow?.let { r ->
        val v = "ch${r.ch} ${r.fullId}"
        AlertDialog(
            onDismissRequest = { pickRow = null },
            title = { Text("コピー先を選択") },
            text = {
                Column {
                    Text("ch${r.ch}　${r.fullId}", fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(4.dp))
                    Text("この10桁IDを、結果報告のどちらの欄に反映しますか？",
                        fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(14.dp))
                    Button(
                        onClick = {
                            copyToClipboard(context, v)
                            saveResultField(context, "現行システムID", v)
                            Toast.makeText(context, "現行システムIDにコピーしました", Toast.LENGTH_SHORT).show()
                            pickRow = null
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("現行のシステムIDにコピー") }
                    Spacer(Modifier.height(8.dp))
                    Button(
                        onClick = {
                            copyToClipboard(context, v)
                            saveSelectedSystemId(context, v)
                            saveResultField(context, "変更後システムID", v)
                            Toast.makeText(context, "変更後システムIDにコピーしました", Toast.LENGTH_SHORT).show()
                            pickRow = null
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("変更後のシステムIDにコピー") }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = { pickRow = null }) { Text("キャンセル") } }
        )
    }
}

private val LEFT_W = 58.dp
private val DCELL_H = 30.dp

// 規格a/b を表す小箱（選択側＝黒塗り＋白文字で明確に、非選択＝淡色）
@Composable
private fun ABCell(letter: String, active: Boolean) {
    Box(
        modifier = Modifier
            .width(24.dp)
            .height(24.dp)
            .background(if (active) Color(0xFF111111) else Color(0xFFF0F0F0), RoundedCornerShape(3.dp))
            .border(
                if (active) 1.5.dp else 0.8.dp,
                if (active) Color(0xFF111111) else Color(0xFFCCCCCC),
                RoundedCornerShape(3.dp)
            ),
        contentAlignment = Alignment.Center
    ) {
        Text(
            letter,
            color = if (active) Color.White else Color(0xFFBDBDBD),
            fontWeight = FontWeight.Bold,
            fontSize = 15.sp
        )
    }
}

@Composable
private fun IdTableHeader() {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(modifier = Modifier.width(LEFT_W), contentAlignment = Alignment.Center) {
            Text("規格", fontSize = 10.sp, fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Row(modifier = Modifier.weight(1f)) {
            HeaderCell("規格", 2f)
            HeaderCell("Ch設定値", 2f)
            HeaderCell("番号", 5f)
            HeaderCell("CD", 1f)
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun TableRow(row: IdRow, onClick: () -> Unit, onLongClick: () -> Unit) {
    val kikakuB = row.chCode.take(1)     // G（規格b）: "0"→a側, "1"→b側
    val aActive = kikakuB == "0"
    val hasStore = row.store.isNotEmpty()
    val chSet = row.chCode.drop(1)       // H,I（Ch設定値 2桁）

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(
                enabled = row.fullId.isNotEmpty(),
                onClick = onClick,
                onLongClick = onLongClick
            )
            .padding(vertical = 2.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // 左: a/b（選択側を強調）＋ ch を下に
        Column(
            modifier = Modifier.width(LEFT_W),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                ABCell("a", aActive)
                ABCell("b", !aActive)
            }
            Text("ch:${row.ch}", fontSize = 11.sp, fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary)
        }
        // 右: 10桁を□枠で
        Row(modifier = Modifier.weight(1f)) {
            DigitCell(row.kikaku, ColKikakuA)
            DigitCell(kikakuB, ColKikakuB)
            DigitCell(chSet.getOrNull(0)?.toString() ?: "", ColCh)
            DigitCell(chSet.getOrNull(1)?.toString() ?: "", ColCh)
            repeat(5) { i ->
                DigitCell(
                    if (hasStore) row.store[i].toString() else "",
                    if (hasStore) ColNum else ColEmpty
                )
            }
            DigitCell(row.cd, if (row.cd.isNotEmpty()) ColCd else ColEmpty, cd = true)
        }
    }
}

@Composable
private fun androidx.compose.foundation.layout.RowScope.DigitCell(d: String, tint: Color, cd: Boolean = false) {
    Box(
        modifier = Modifier
            .weight(1f)
            .height(DCELL_H)
            .padding(1.dp)
            .background(tint, RoundedCornerShape(3.dp))
            .border(0.7.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(3.dp)),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = d,
            fontFamily = FontFamily.Monospace,
            fontWeight = if (cd) FontWeight.Bold else FontWeight.Medium,
            fontSize = if (cd) 15.sp else 13.sp,
            color = MaterialTheme.colorScheme.onSurface
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

private fun Context.findActivity(): Activity? {
    var c: Context = this
    while (c is ContextWrapper) {
        if (c is Activity) return c
        c = c.baseContext
    }
    return null
}

private fun copyToClipboard(context: Context, text: String) {
    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    cm.setPrimaryClip(ClipData.newPlainText("ID", text))
    Toast.makeText(context, "コピー: $text", Toast.LENGTH_SHORT).show()
}
