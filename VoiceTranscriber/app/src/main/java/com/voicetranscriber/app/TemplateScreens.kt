package com.voicetranscriber.app

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.ArrowDropUp
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

// ---------------------------------------------------------------------------
// 定型文：使用画面
// フォルダ（開閉リスト）→ 中の定型文を選ぶ → 差し込み項目を入力 → コピー
// ---------------------------------------------------------------------------

@Composable
fun TemplatesPane(
    innerPadding: PaddingValues,
    snackbarHostState: SnackbarHostState,
    viewModel: TemplateViewModel = viewModel(),
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val store by viewModel.store.collectAsStateWithLifecycle()

    var expandedFolderId by remember { mutableStateOf<String?>(null) }
    var selectedTemplateId by remember { mutableStateOf<String?>(null) }
    var date by remember { mutableStateOf("") }
    var time1 by remember { mutableStateOf("") }
    var time2 by remember { mutableStateOf("") }
    var name by remember { mutableStateOf("") }

    val selectedTemplate = store.categories
        .flatMap { it.templates }
        .firstOrNull { it.id == selectedTemplateId }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(innerPadding)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        if (store.categories.isEmpty()) {
            EmptyFoldersHint()
            return@Column
        }

        Text(
            "フォルダ",
            fontWeight = FontWeight.Bold,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(Modifier.height(8.dp))

        store.categories.forEach { folder ->
            val isExpanded = folder.id == expandedFolderId ||
                (expandedFolderId == null && folder == store.categories.first())
            FolderCard(
                folder = folder,
                expanded = isExpanded,
                onToggle = {
                    expandedFolderId = if (isExpanded) "" else folder.id
                },
                selectedTemplateId = selectedTemplateId,
                onSelectTemplate = { selectedTemplateId = it },
            )
            Spacer(Modifier.height(10.dp))
        }

        // 差し込みフィールド＋プレビュー＋コピー
        if (selectedTemplate != null) {
            Spacer(Modifier.height(8.dp))
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(18.dp))
                    .background(
                        Brush.verticalGradient(
                            listOf(
                                AccentBlue.copy(alpha = 0.10f),
                                AccentBlue.copy(alpha = 0.02f),
                            ),
                        ),
                    )
                    .border(1.dp, AccentBlue.copy(alpha = 0.35f), RoundedCornerShape(18.dp))
                    .padding(14.dp),
            ) {
                Column {
                    Text(
                        selectedTemplate.name,
                        fontWeight = FontWeight.Bold,
                        fontSize = 15.sp,
                        color = AccentBlueDeep,
                    )
                    Spacer(Modifier.height(10.dp))

                    val body = selectedTemplate.body
                    if (TemplateTokens.contains(body, TemplateTokens.DATE)) {
                        DropdownField("日付", dateOptions(), date) { date = it }
                        Spacer(Modifier.height(10.dp))
                    }
                    if (TemplateTokens.contains(body, TemplateTokens.TIME1)) {
                        DropdownField("時間1", timeOptions(), time1) { time1 = it }
                        Spacer(Modifier.height(10.dp))
                    }
                    if (TemplateTokens.contains(body, TemplateTokens.TIME2)) {
                        DropdownField("時間2", timeOptions(), time2) { time2 = it }
                        Spacer(Modifier.height(10.dp))
                    }
                    if (TemplateTokens.contains(body, TemplateTokens.NAME)) {
                        OutlinedTextField(
                            value = name,
                            onValueChange = { name = it },
                            label = { Text("氏名") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                            colors = accentTextFieldColors(),
                        )
                        Spacer(Modifier.height(10.dp))
                    }

                    val filled = fillTemplate(body, date, time1, time2, name)
                    Text(
                        "プレビュー",
                        fontWeight = FontWeight.Bold,
                        style = MaterialTheme.typography.titleSmall,
                    )
                    Spacer(Modifier.height(6.dp))
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    ) {
                        Text(
                            filled,
                            modifier = Modifier.padding(14.dp),
                            fontSize = 18.sp,
                            lineHeight = 26.sp,
                        )
                    }
                    Spacer(Modifier.height(14.dp))
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(52.dp)
                            .clip(RoundedCornerShape(50))
                            .background(Brush.horizontalGradient(listOf(AccentBlue, AccentBlueDeep)))
                            .clickable {
                                copyText(context, filled)
                                scope.launch { snackbarHostState.showSnackbar("コピーしました") }
                            },
                    ) {
                        Icon(
                            Icons.Filled.ContentCopy,
                            contentDescription = null,
                            tint = Color.White,
                            modifier = Modifier.width(20.dp),
                        )
                        Spacer(Modifier.width(8.dp))
                        Text("コピー", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                    }
                }
            }
        }

        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun EmptyFoldersHint() {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(AccentBlue.copy(alpha = 0.08f))
            .padding(20.dp),
    ) {
        Icon(Icons.Filled.Folder, contentDescription = null, tint = AccentBlueDeep)
        Spacer(Modifier.height(8.dp))
        Text(
            "フォルダがまだありません。\n右上の設定（⚙）からフォルダと定型文を追加してください。",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontSize = 16.sp,
            lineHeight = 24.sp,
        )
    }
}

/** 開閉できるフォルダ（アコーディオン）。開くと中の定型文一覧が表示される。 */
@Composable
private fun FolderCard(
    folder: TemplateCategory,
    expanded: Boolean,
    onToggle: () -> Unit,
    selectedTemplateId: String?,
    onSelectTemplate: (String) -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        shape = RoundedCornerShape(16.dp),
    ) {
        Column {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onToggle() }
                    .padding(horizontal = 14.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .size(34.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(Brush.linearGradient(listOf(AccentBlue, AccentBlueDeep))),
                ) {
                    Icon(Icons.Filled.Folder, contentDescription = null, tint = Color.White, modifier = Modifier.width(18.dp))
                }
                Spacer(Modifier.width(10.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(folder.name, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                    Text(
                        "${folder.templates.size}件の定型文",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Icon(
                    if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            if (expanded) {
                if (folder.templates.isEmpty()) {
                    Text(
                        "このフォルダには定型文がありません。設定から追加してください。",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 13.sp,
                        modifier = Modifier.padding(start = 58.dp, end = 14.dp, bottom = 12.dp),
                    )
                } else {
                    Column(modifier = Modifier.padding(start = 12.dp, end = 12.dp, bottom = 10.dp)) {
                        folder.templates.forEach { t ->
                            val isSel = t.id == selectedTemplateId
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(
                                        if (isSel) AccentBlue.copy(alpha = 0.14f) else Color.Transparent,
                                    )
                                    .clickable { onSelectTemplate(t.id) }
                                    .padding(horizontal = 10.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Icon(
                                    if (isSel) Icons.Filled.CheckCircle else Icons.Filled.RadioButtonUnchecked,
                                    contentDescription = null,
                                    tint = if (isSel) AccentBlueDeep else MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.width(20.dp),
                                )
                                Spacer(Modifier.width(10.dp))
                                Text(
                                    t.name,
                                    fontWeight = if (isSel) FontWeight.Bold else FontWeight.Normal,
                                    color = if (isSel) AccentBlueDeep else MaterialTheme.colorScheme.onSurface,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------
// 定型文：設定画面（フォルダ・定型文の追加/編集/削除）
// ---------------------------------------------------------------------------

@Composable
fun TemplateSettingsPane(
    innerPadding: PaddingValues,
    viewModel: TemplateViewModel = viewModel(),
) {
    val store by viewModel.store.collectAsStateWithLifecycle()
    var newCategory by remember { mutableStateOf("") }

    // 編集ダイアログの対象（フォルダID, 既存テンプレ or null=新規）
    var editorTarget by remember { mutableStateOf<Pair<String, Template?>?>(null) }
    var deleteCategoryTarget by remember { mutableStateOf<TemplateCategory?>(null) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(innerPadding)
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
    ) {
        Text(
            "フォルダごとに複数の定型文を登録できます。\n本文には {日付}{時間1}{時間2}{氏名} を挿入してください。",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontSize = 13.sp,
            lineHeight = 19.sp,
        )
        Spacer(Modifier.height(12.dp))

        // 新規フォルダ追加
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = newCategory,
                onValueChange = { newCategory = it },
                label = { Text("新しいフォルダ名") },
                singleLine = true,
                modifier = Modifier.weight(1f),
                colors = accentTextFieldColors(),
            )
            Spacer(Modifier.width(8.dp))
            Button(
                onClick = {
                    viewModel.addCategory(newCategory)
                    newCategory = ""
                },
                enabled = newCategory.isNotBlank(),
                colors = ButtonDefaults.buttonColors(containerColor = AccentBlueDeep),
            ) {
                Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.width(18.dp))
                Text("追加")
            }
        }

        Spacer(Modifier.height(16.dp))

        store.categories.forEach { c ->
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 12.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Filled.Folder,
                            contentDescription = null,
                            tint = AccentBlueDeep,
                            modifier = Modifier.width(20.dp),
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            c.name,
                            fontWeight = FontWeight.Bold,
                            fontSize = 17.sp,
                            modifier = Modifier.weight(1f),
                        )
                        IconButton(onClick = { deleteCategoryTarget = c }) {
                            Icon(
                                Icons.Filled.Delete,
                                contentDescription = "フォルダを削除",
                                tint = MaterialTheme.colorScheme.error,
                            )
                        }
                    }
                    Spacer(Modifier.height(4.dp))
                    c.templates.forEachIndexed { index, t ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 2.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            // 並び替え（上へ／下へ）
                            Column {
                                IconButton(
                                    onClick = { viewModel.moveTemplate(c.id, t.id, -1) },
                                    enabled = index > 0,
                                    modifier = Modifier.size(28.dp),
                                ) {
                                    Icon(
                                        Icons.Filled.ArrowDropUp,
                                        contentDescription = "上へ移動",
                                        tint = if (index > 0) AccentBlueDeep else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                                    )
                                }
                                IconButton(
                                    onClick = { viewModel.moveTemplate(c.id, t.id, 1) },
                                    enabled = index < c.templates.lastIndex,
                                    modifier = Modifier.size(28.dp),
                                ) {
                                    Icon(
                                        Icons.Filled.ArrowDropDown,
                                        contentDescription = "下へ移動",
                                        tint = if (index < c.templates.lastIndex) AccentBlueDeep else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                                    )
                                }
                            }
                            Spacer(Modifier.width(4.dp))
                            Text(t.name, modifier = Modifier.weight(1f))
                            IconButton(onClick = { editorTarget = c.id to t }) {
                                Icon(Icons.Filled.Edit, contentDescription = "編集")
                            }
                            IconButton(onClick = { viewModel.deleteTemplate(c.id, t.id) }) {
                                Icon(
                                    Icons.Filled.Delete,
                                    contentDescription = "削除",
                                    tint = MaterialTheme.colorScheme.error,
                                )
                            }
                        }
                    }
                    Spacer(Modifier.height(4.dp))
                    OutlinedButton(
                        onClick = { editorTarget = c.id to null },
                        colors = ButtonDefaults.outlinedButtonColors(
                            contentColor = AccentBlueDeep,
                        ),
                    ) {
                        Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.width(18.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("定型文を追加")
                    }
                }
            }
        }

        Spacer(Modifier.height(24.dp))
    }

    // 定型文エディタ
    editorTarget?.let { (categoryId, existing) ->
        TemplateEditorDialog(
            existing = existing,
            onDismiss = { editorTarget = null },
            onSave = { nm, body ->
                if (existing == null) viewModel.addTemplate(categoryId, nm, body)
                else viewModel.updateTemplate(categoryId, existing.id, nm, body)
                editorTarget = null
            },
        )
    }

    // フォルダ削除の確認
    deleteCategoryTarget?.let { c ->
        AlertDialog(
            onDismissRequest = { deleteCategoryTarget = null },
            title = { Text("フォルダを削除") },
            text = { Text("「${c.name}」と、その中の定型文をすべて削除します。よろしいですか？") },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.deleteCategory(c.id)
                    deleteCategoryTarget = null
                }) { Text("削除する") }
            },
            dismissButton = {
                TextButton(onClick = { deleteCategoryTarget = null }) { Text("キャンセル") }
            },
        )
    }
}

@Composable
private fun TemplateEditorDialog(
    existing: Template?,
    onDismiss: () -> Unit,
    onSave: (name: String, body: String) -> Unit,
) {
    var name by remember { mutableStateOf(existing?.name ?: "") }
    // カーソル（選択範囲）の位置を保持し、差し込み項目はそこへ挿入する
    var body by remember {
        mutableStateOf(
            TextFieldValue(existing?.body ?: "", selection = TextRange((existing?.body ?: "").length)),
        )
    }

    fun insertTokenAtCursor(token: String) {
        val text = body.text
        val start = body.selection.start.coerceIn(0, text.length)
        val end = body.selection.end.coerceIn(0, text.length)
        val newText = text.substring(0, start) + token + text.substring(end)
        val newCursor = start + token.length
        body = TextFieldValue(newText, selection = TextRange(newCursor))
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (existing == null) "定型文を追加" else "定型文を編集") },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("定型文の名前") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    colors = accentTextFieldColors(),
                )
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(
                    value = body,
                    onValueChange = { body = it },
                    label = { Text("本文") },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(160.dp),
                    colors = accentTextFieldColors(),
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    "タップでカーソル位置に挿入:",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(4.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TemplateTokens.all.forEach { token ->
                        SelectChip(text = token, selected = false, onClick = { insertTokenAtCursor(token) })
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onSave(name, body.text) },
                enabled = name.isNotBlank(),
            ) { Text("保存") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("キャンセル") }
        },
    )
}

// ---------------------------------------------------------------------------
// 共通パーツ
// ---------------------------------------------------------------------------

@Composable
private fun accentTextFieldColors() = OutlinedTextFieldDefaults.colors(
    focusedBorderColor = AccentBlueDeep,
    focusedLabelColor = AccentBlueDeep,
    cursorColor = AccentBlueDeep,
)

@Composable
private fun SelectChip(text: String, selected: Boolean, onClick: () -> Unit) {
    val bg = if (selected) AccentBlueDeep else MaterialTheme.colorScheme.surfaceVariant
    val fg = if (selected) Color.White else MaterialTheme.colorScheme.onSurface
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(20.dp))
            .background(bg)
            .clickable { onClick() }
            .padding(horizontal = 14.dp, vertical = 8.dp),
    ) {
        Text(text, color = fg, fontSize = 14.sp, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun DropdownField(
    label: String,
    options: List<String>,
    selected: String,
    onSelect: (String) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(label, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(2.dp))
        Box {
            OutlinedButton(
                onClick = { expanded = true },
                colors = ButtonDefaults.outlinedButtonColors(
                    contentColor = MaterialTheme.colorScheme.onSurface,
                ),
                border = androidx.compose.foundation.BorderStroke(1.dp, AccentBlue.copy(alpha = 0.5f)),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    selected.ifBlank { "選択してください" },
                    modifier = Modifier.weight(1f),
                    color = if (selected.isBlank()) MaterialTheme.colorScheme.onSurfaceVariant
                    else MaterialTheme.colorScheme.onSurface,
                )
                Icon(Icons.Filled.ArrowDropDown, contentDescription = null, tint = AccentBlueDeep)
            }
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                options.forEach { opt ->
                    DropdownMenuItem(
                        text = { Text(opt) },
                        onClick = {
                            onSelect(opt)
                            expanded = false
                        },
                    )
                }
            }
        }
    }
}

private fun dateOptions(): List<String> {
    val fmt = DateTimeFormatter.ofPattern("M月d日(E)", Locale.JAPANESE)
    val today = LocalDate.now()
    return (0..30L).map { today.plusDays(it).format(fmt) }
}

private fun timeOptions(): List<String> {
    val list = ArrayList<String>(96)
    for (h in 0..23) for (m in intArrayOf(0, 15, 30, 45)) {
        list.add(String.format(Locale.US, "%02d:%02d", h, m))
    }
    return list
}

private fun copyText(context: Context, text: String) {
    val cb = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    cb.setPrimaryClip(ClipData.newPlainText("定型文", text))
}
