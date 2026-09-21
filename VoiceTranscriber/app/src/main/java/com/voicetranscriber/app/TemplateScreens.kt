package com.voicetranscriber.app

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.background
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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

// ---------------------------------------------------------------------------
// 定型文：使用画面（選んで差し込んでコピー）
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

    var selectedCategoryId by remember { mutableStateOf<String?>(null) }
    var selectedTemplateId by remember { mutableStateOf<String?>(null) }
    var date by remember { mutableStateOf("") }
    var time1 by remember { mutableStateOf("") }
    var time2 by remember { mutableStateOf("") }
    var name by remember { mutableStateOf("") }

    val category = store.categories.firstOrNull { it.id == selectedCategoryId }
        ?: store.categories.firstOrNull()
    val template = category?.templates?.firstOrNull { it.id == selectedTemplateId }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(innerPadding)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        if (store.categories.isEmpty()) {
            Text(
                "定型文がまだありません。\n右上の設定（⚙）から項目と定型文を追加してください。",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 16.sp,
                lineHeight = 24.sp,
            )
            return@Column
        }

        // カテゴリ選択
        Text("項目", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleSmall)
        Spacer(Modifier.height(6.dp))
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            store.categories.forEach { c ->
                SelectChip(
                    text = c.name,
                    selected = c.id == category?.id,
                    onClick = {
                        selectedCategoryId = c.id
                        selectedTemplateId = null
                    },
                )
            }
        }

        Spacer(Modifier.height(16.dp))

        // 定型文選択
        Text("定型文", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleSmall)
        Spacer(Modifier.height(6.dp))
        val templates = category?.templates.orEmpty()
        if (templates.isEmpty()) {
            Text(
                "この項目には定型文がありません。設定から追加してください。",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            templates.forEach { t ->
                val isSel = t.id == template?.id
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 8.dp)
                        .clickable { selectedTemplateId = t.id },
                    colors = CardDefaults.cardColors(
                        containerColor = if (isSel)
                            MaterialTheme.colorScheme.primaryContainer
                        else MaterialTheme.colorScheme.surfaceVariant,
                    ),
                ) {
                    Text(
                        t.name,
                        modifier = Modifier.padding(14.dp),
                        fontWeight = if (isSel) FontWeight.Bold else FontWeight.Normal,
                    )
                }
            }
        }

        // 差し込みフィールド＋プレビュー
        if (template != null) {
            Spacer(Modifier.height(12.dp))
            val body = template.body
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
                )
                Spacer(Modifier.height(10.dp))
            }

            val filled = fillTemplate(body, date, time1, time2, name)
            Spacer(Modifier.height(4.dp))
            Text("プレビュー", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleSmall)
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
            Spacer(Modifier.height(12.dp))
            Button(
                onClick = {
                    copyText(context, filled)
                    scope.launch { snackbarHostState.showSnackbar("コピーしました") }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp),
            ) {
                Icon(Icons.Filled.ContentCopy, contentDescription = null, modifier = Modifier.width(20.dp))
                Spacer(Modifier.width(8.dp))
                Text("コピー", fontWeight = FontWeight.Bold, fontSize = 16.sp)
            }
        }

        Spacer(Modifier.height(24.dp))
    }
}

// ---------------------------------------------------------------------------
// 定型文：設定画面（項目・定型文の追加/編集/削除）
// ---------------------------------------------------------------------------

@Composable
fun TemplateSettingsPane(
    innerPadding: PaddingValues,
    viewModel: TemplateViewModel = viewModel(),
) {
    val store by viewModel.store.collectAsStateWithLifecycle()
    var newCategory by remember { mutableStateOf("") }

    // 編集ダイアログの対象（カテゴリID, 既存テンプレ or null=新規）
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
            "項目（メイン）ごとに定型文を登録できます。\n本文には {日付}{時間1}{時間2}{氏名} を挿入してください。",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontSize = 13.sp,
            lineHeight = 19.sp,
        )
        Spacer(Modifier.height(12.dp))

        // 新規カテゴリ追加
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = newCategory,
                onValueChange = { newCategory = it },
                label = { Text("新しい項目名") },
                singleLine = true,
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(8.dp))
            Button(
                onClick = {
                    viewModel.addCategory(newCategory)
                    newCategory = ""
                },
                enabled = newCategory.isNotBlank(),
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
                        Text(
                            c.name,
                            fontWeight = FontWeight.Bold,
                            fontSize = 17.sp,
                            modifier = Modifier.weight(1f),
                        )
                        IconButton(onClick = { deleteCategoryTarget = c }) {
                            Icon(
                                Icons.Filled.Delete,
                                contentDescription = "項目を削除",
                                tint = MaterialTheme.colorScheme.error,
                            )
                        }
                    }
                    Spacer(Modifier.height(4.dp))
                    c.templates.forEach { t ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 2.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
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
                    OutlinedButton(onClick = { editorTarget = c.id to null }) {
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

    // カテゴリ削除の確認
    deleteCategoryTarget?.let { c ->
        AlertDialog(
            onDismissRequest = { deleteCategoryTarget = null },
            title = { Text("項目を削除") },
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
    var body by remember { mutableStateOf(existing?.body ?: "") }

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
                )
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(
                    value = body,
                    onValueChange = { body = it },
                    label = { Text("本文") },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(160.dp),
                )
                Spacer(Modifier.height(8.dp))
                Text("タップで差し込み項目を挿入:", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(4.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TemplateTokens.all.forEach { token ->
                        SelectChip(text = token, selected = false, onClick = { body += token })
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onSave(name, body) },
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
private fun SelectChip(text: String, selected: Boolean, onClick: () -> Unit) {
    val bg = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant
    val fg = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface
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
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    selected.ifBlank { "選択してください" },
                    modifier = Modifier.weight(1f),
                    color = if (selected.isBlank()) MaterialTheme.colorScheme.onSurfaceVariant
                    else MaterialTheme.colorScheme.onSurface,
                )
                Icon(Icons.Filled.ArrowDropDown, contentDescription = null)
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
