package com.voicetranscriber.app

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DragHandle
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.zIndex
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlin.math.roundToInt

/** 並び替えの計算に使う、定型文1行の高さ。 */
private val RowHeight = 62.dp

// ---------------------------------------------------------------------------
// 設定画面
//   上部のタブで「定型文」と「メール」を切り替える（中身は完全に別管理）。
//   ・共通項目（まとめるときに間へ挟む文言）
//   ・フォルダの追加／名前変更／並び替え／削除
//   ・定型文の追加／編集／削除、長押しドラッグで並び替え
// ---------------------------------------------------------------------------

@Composable
fun TemplateSettingsPane(
    innerPadding: PaddingValues,
    kind: TemplateKind,
    onKindChange: (TemplateKind) -> Unit,
    viewModel: TemplateViewModel = viewModel(),
) {
    val store by viewModel.store.collectAsStateWithLifecycle()
    val categories = store.categoriesOf(kind)
    val isMail = kind == TemplateKind.MAIL

    var newCategory by remember { mutableStateOf("") }
    var editorTarget by remember { mutableStateOf<Pair<String, Template?>?>(null) }
    var deleteCategoryTarget by remember { mutableStateOf<TemplateCategory?>(null) }
    var deleteTemplateTarget by remember { mutableStateOf<Pair<String, Template>?>(null) }
    var renameCategoryTarget by remember { mutableStateOf<TemplateCategory?>(null) }

    // 長押しドラッグによる並び替えの状態
    var draggingId by remember { mutableStateOf<String?>(null) }
    var dragOffset by remember { mutableStateOf(0f) }
    val rowPx = with(LocalDensity.current) { RowHeight.toPx() }

    fun onDrag(categoryId: String, templateId: String, dy: Float) {
        dragOffset += dy
        while (dragOffset >= rowPx) {
            if (!viewModel.moveTemplate(kind, categoryId, templateId, 1)) {
                dragOffset = rowPx
                break
            }
            dragOffset -= rowPx
        }
        while (dragOffset <= -rowPx) {
            if (!viewModel.moveTemplate(kind, categoryId, templateId, -1)) {
                dragOffset = -rowPx
                break
            }
            dragOffset += rowPx
        }
        dragOffset = dragOffset.coerceIn(-rowPx, rowPx)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(surfaceWashBrush())
            .padding(innerPadding)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        HeroHeader(
            title = "設定",
            subtitle = if (isMail) {
                "メール用の定型文・宛先・件名を登録します"
            } else {
                "コピー用の定型文を登録します"
            },
        )
        Spacer(Modifier.height(14.dp))

        KindSwitcher(kind = kind, onKindChange = onKindChange)
        Spacer(Modifier.height(16.dp))

        // ----- 共通項目 -----
        SectionLabel("共通項目")
        Spacer(Modifier.height(6.dp))
        CommonInsertEditor(
            key = kind,
            commonInsert = store.commonInsertOf(kind),
            onSave = { viewModel.setCommonInsert(kind, it) },
        )
        Spacer(Modifier.height(20.dp))

        // ----- 金額プリセット -----
        SectionLabel("金額プリセット")
        Spacer(Modifier.height(6.dp))
        AmountPresetEditor(
            key = kind,
            presets = store.amountPresetsOf(kind),
            onAdd = { viewModel.addAmountPreset(kind, it) },
            onRemove = { viewModel.removeAmountPreset(kind, it) },
        )
        Spacer(Modifier.height(20.dp))

        // ----- フォルダ追加 -----
        SectionLabel("フォルダ")
        Spacer(Modifier.height(6.dp))
        OutlinedTextField(
            value = newCategory,
            onValueChange = { newCategory = it },
            label = { Text("新しいフォルダ名") },
            singleLine = true,
            shape = RoundedCornerShape(14.dp),
            modifier = Modifier.fillMaxWidth(),
            colors = brandTextFieldColors(),
            trailingIcon = {
                IconButton(
                    onClick = {
                        viewModel.addCategory(kind, newCategory)
                        newCategory = ""
                    },
                    enabled = newCategory.isNotBlank(),
                ) {
                    Icon(
                        Icons.Filled.Add,
                        contentDescription = "フォルダを追加",
                        tint = if (newCategory.isNotBlank()) BrandOrangeDeep
                        else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                    )
                }
            },
        )
        Spacer(Modifier.height(14.dp))

        if (categories.isEmpty()) {
            Text(
                if (isMail) {
                    "メール用のフォルダがまだありません。上の欄から追加してください。"
                } else {
                    "フォルダがまだありません。上の欄から追加してください。"
                },
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 13.sp,
                lineHeight = 19.sp,
            )
        }

        categories.forEachIndexed { folderIndex, c ->
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 12.dp),
                shape = RoundedCornerShape(16.dp),
                elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            ) {
                Column(modifier = Modifier.padding(bottom = 10.dp)) {
                    // フォルダ見出し
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(58.dp)
                            .padding(start = 12.dp, end = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(
                            contentAlignment = Alignment.Center,
                            modifier = Modifier
                                .size(30.dp)
                                .clip(RoundedCornerShape(10.dp))
                                .background(BlueGradient),
                        ) {
                            Icon(
                                Icons.Filled.Folder,
                                contentDescription = null,
                                tint = Color.White,
                                modifier = Modifier.size(16.dp),
                            )
                        }
                        Spacer(Modifier.width(10.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                c.name,
                                fontWeight = FontWeight.Bold,
                                fontSize = 16.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                "${c.templates.size}件",
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                            )
                        }
                        RowMenu(
                            items = listOf(
                                MenuAction("名前を変更", Icons.Filled.Edit) { renameCategoryTarget = c },
                                MenuAction("上へ", Icons.Filled.ArrowUpward, folderIndex > 0) {
                                    viewModel.moveCategory(kind, c.id, -1)
                                },
                                MenuAction("下へ", Icons.Filled.ArrowDownward, folderIndex < categories.lastIndex) {
                                    viewModel.moveCategory(kind, c.id, 1)
                                },
                                MenuAction("削除", Icons.Filled.Delete, danger = true) {
                                    deleteCategoryTarget = c
                                },
                            ),
                        )
                    }

                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

                    if (c.templates.isEmpty()) {
                        Text(
                            "定型文がありません。下のボタンから追加してください。",
                            fontSize = 12.sp,
                            lineHeight = 18.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
                        )
                    } else {
                        c.templates.forEachIndexed { index, t ->
                            val isDragging = draggingId == t.id
                            val haptics = LocalHapticFeedback.current
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(RowHeight)
                                    .zIndex(if (isDragging) 1f else 0f)
                                    .offset {
                                        IntOffset(0, if (isDragging) dragOffset.roundToInt() else 0)
                                    }
                                    .padding(horizontal = 8.dp, vertical = 3.dp)
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(
                                        if (isDragging) BrandBlue.copy(alpha = 0.14f)
                                        else Color.Transparent,
                                    )
                                    .then(
                                        if (isDragging) {
                                            Modifier.border(
                                                1.dp,
                                                BrandBlue.copy(alpha = 0.5f),
                                                RoundedCornerShape(12.dp),
                                            )
                                        } else Modifier,
                                    )
                                    .padding(start = 6.dp, end = 0.dp),
                            ) {
                                // 長押し＋上下ドラッグで並び替え
                                Icon(
                                    Icons.Filled.DragHandle,
                                    contentDescription = "長押しして上下にドラッグで並び替え",
                                    tint = if (isDragging) BrandBlueDeep
                                    else MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier
                                        .size(28.dp)
                                        .pointerInput(t.id) {
                                            detectDragGesturesAfterLongPress(
                                                onDragStart = {
                                                    draggingId = t.id
                                                    dragOffset = 0f
                                                    haptics.performHapticFeedback(
                                                        HapticFeedbackType.LongPress,
                                                    )
                                                },
                                                onDrag = { change, amount ->
                                                    change.consume()
                                                    onDrag(c.id, t.id, amount.y)
                                                },
                                                onDragEnd = {
                                                    draggingId = null
                                                    dragOffset = 0f
                                                },
                                                onDragCancel = {
                                                    draggingId = null
                                                    dragOffset = 0f
                                                },
                                            )
                                        },
                                )
                                Spacer(Modifier.width(8.dp))
                                Column(
                                    modifier = Modifier
                                        .weight(1f)
                                        .clickable { editorTarget = c.id to t },
                                ) {
                                    Text(
                                        t.name,
                                        fontSize = 15.sp,
                                        fontWeight = FontWeight.Medium,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                    val sub = when {
                                        isMail && t.email.isNotBlank() -> t.email
                                        else -> t.body.replace("\n", " ").ifBlank { "（本文なし）" }
                                    }
                                    Text(
                                        sub,
                                        fontSize = 11.sp,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                                RowMenu(
                                    items = listOf(
                                        MenuAction("編集", Icons.Filled.Edit) {
                                            editorTarget = c.id to t
                                        },
                                        MenuAction("上へ", Icons.Filled.ArrowUpward, index > 0) {
                                            viewModel.moveTemplate(kind, c.id, t.id, -1)
                                        },
                                        MenuAction(
                                            "下へ",
                                            Icons.Filled.ArrowDownward,
                                            index < c.templates.lastIndex,
                                        ) {
                                            viewModel.moveTemplate(kind, c.id, t.id, 1)
                                        },
                                        MenuAction("削除", Icons.Filled.Delete, danger = true) {
                                            deleteTemplateTarget = c.id to t
                                        },
                                    ),
                                )
                            }
                        }
                        Text(
                            "行の「≡」を長押ししたまま上下に動かすと並び替えられます。",
                            fontSize = 10.sp,
                            lineHeight = 14.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 2.dp),
                        )
                    }

                    Spacer(Modifier.height(6.dp))
                    Row(
                        modifier = Modifier
                            .padding(horizontal = 12.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(BrandOrange.copy(alpha = 0.14f))
                            .clickable { editorTarget = c.id to null }
                            .padding(horizontal = 14.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            Icons.Filled.Add,
                            contentDescription = null,
                            tint = BrandOrangeDeep,
                            modifier = Modifier.size(18.dp),
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(
                            "定型文を追加",
                            color = BrandOrangeDeep,
                            fontWeight = FontWeight.Bold,
                            fontSize = 14.sp,
                        )
                    }
                }
            }
        }

        Spacer(Modifier.height(24.dp))
    }

    // ----- ダイアログ類 -----

    editorTarget?.let { (categoryId, existing) ->
        TemplateEditorDialog(
            existing = existing,
            kind = kind,
            onDismiss = { editorTarget = null },
            onSave = { nm, body, email, subject ->
                if (existing == null) {
                    viewModel.addTemplate(kind, categoryId, nm, body, email, subject)
                } else {
                    viewModel.updateTemplate(kind, categoryId, existing.id, nm, body, email, subject)
                }
                editorTarget = null
            },
        )
    }

    renameCategoryTarget?.let { c ->
        var newName by remember(c.id) { mutableStateOf(c.name) }
        AlertDialog(
            onDismissRequest = { renameCategoryTarget = null },
            title = { Text("フォルダ名を変更") },
            text = {
                OutlinedTextField(
                    value = newName,
                    onValueChange = { newName = it },
                    label = { Text("フォルダ名") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    colors = brandTextFieldColors(),
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.renameCategory(kind, c.id, newName)
                        renameCategoryTarget = null
                    },
                    enabled = newName.isNotBlank(),
                ) { Text("変更") }
            },
            dismissButton = {
                TextButton(onClick = { renameCategoryTarget = null }) { Text("キャンセル") }
            },
            properties = DialogProperties(dismissOnClickOutside = false),
        )
    }

    deleteCategoryTarget?.let { c ->
        AlertDialog(
            onDismissRequest = { deleteCategoryTarget = null },
            title = { Text("フォルダを削除") },
            text = { Text("「${c.name}」と、その中の定型文をすべて削除します。よろしいですか？") },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.deleteCategory(kind, c.id)
                    deleteCategoryTarget = null
                }) { Text("削除する") }
            },
            dismissButton = {
                TextButton(onClick = { deleteCategoryTarget = null }) { Text("キャンセル") }
            },
        )
    }

    deleteTemplateTarget?.let { (categoryId, t) ->
        AlertDialog(
            onDismissRequest = { deleteTemplateTarget = null },
            title = { Text("定型文を削除") },
            text = { Text("「${t.name}」を削除します。よろしいですか？") },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.deleteTemplate(kind, categoryId, t.id)
                    deleteTemplateTarget = null
                }) { Text("削除する") }
            },
            dismissButton = {
                TextButton(onClick = { deleteTemplateTarget = null }) { Text("キャンセル") }
            },
        )
    }
}

/** 設定の対象（定型文／メール）を切り替えるセグメント。 */
@Composable
private fun KindSwitcher(kind: TemplateKind, onKindChange: (TemplateKind) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(4.dp),
    ) {
        listOf(
            TemplateKind.COPY to "定型文",
            TemplateKind.MAIL to "メール",
        ).forEach { (k, label) ->
            val selected = k == kind
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .weight(1f)
                    .height(40.dp)
                    .clip(RoundedCornerShape(11.dp))
                    .background(if (selected) BlueGradient else SolidColor(Color.Transparent))
                    .clickable { onKindChange(k) },
            ) {
                Text(
                    label,
                    color = if (selected) Color.White else MaterialTheme.colorScheme.onSurfaceVariant,
                    fontWeight = FontWeight.Bold,
                    fontSize = 14.sp,
                )
            }
        }
    }
}

/** 行末の「⋮」メニュー。アイコンを並べず1つにまとめて、名前が折り返さないようにする。 */
private data class MenuAction(
    val label: String,
    val icon: androidx.compose.ui.graphics.vector.ImageVector,
    val enabled: Boolean = true,
    val danger: Boolean = false,
    val onClick: () -> Unit,
)

@Composable
private fun RowMenu(items: List<MenuAction>) {
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }) {
            Icon(
                Icons.Filled.MoreVert,
                contentDescription = "メニュー",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            items.forEachIndexed { i, item ->
                if (item.danger && i > 0) {
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                }
                val tint = if (item.danger) MaterialTheme.colorScheme.error else BrandBlueDeep
                DropdownMenuItem(
                    text = { Text(item.label, color = if (item.enabled) tint else tint.copy(alpha = 0.4f)) },
                    leadingIcon = {
                        Icon(
                            item.icon,
                            contentDescription = null,
                            tint = if (item.enabled) tint else tint.copy(alpha = 0.4f),
                            modifier = Modifier.size(18.dp),
                        )
                    },
                    enabled = item.enabled,
                    onClick = {
                        open = false
                        item.onClick()
                    },
                )
            }
        }
    }
}

/**
 * 複数の定型文をまとめて使うときに、間へ差し込む共通の文言（署名・区切りなど）。
 * 入力するたびにそのまま保存される。
 */
@Composable
private fun CommonInsertEditor(key: TemplateKind, commonInsert: String, onSave: (String) -> Unit) {
    var field by remember(key) {
        mutableStateOf(TextFieldValue(commonInsert, selection = TextRange(commonInsert.length)))
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Text(
                "複数の定型文をまとめるとき、間に挟む文言です。\n空のままなら改行だけで区切ります。",
                fontSize = 12.sp,
                lineHeight = 18.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = field,
                onValueChange = {
                    field = it
                    onSave(it.text)
                },
                placeholder = { Text("例：――――――――――") },
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(90.dp),
                colors = brandTextFieldColors(),
            )
            Spacer(Modifier.height(8.dp))
            TokenChipsRow { token ->
                field = insertTokenAt(field, token)
                onSave(field.text)
            }
        }
    }
}

/**
 * {金額1}{金額2} の入力でよく使う値をあらかじめ登録しておく欄。
 * 使用画面では、ここに登録した値がタップで選べるチップとして表示される。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AmountPresetEditor(
    key: TemplateKind,
    presets: List<String>,
    onAdd: (String) -> Unit,
    onRemove: (String) -> Unit,
) {
    var newValue by remember(key) { mutableStateOf("") }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Text(
                "{金額1}{金額2} でよく使う値を登録しておくと、使用画面でタップして選べます。",
                fontSize = 12.sp,
                lineHeight = 18.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = newValue,
                onValueChange = { newValue = it },
                placeholder = { Text("例：50,000円") },
                singleLine = true,
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth(),
                colors = brandTextFieldColors(),
                trailingIcon = {
                    IconButton(
                        onClick = {
                            onAdd(newValue)
                            newValue = ""
                        },
                        enabled = newValue.isNotBlank(),
                    ) {
                        Icon(
                            Icons.Filled.Add,
                            contentDescription = "追加",
                            tint = if (newValue.isNotBlank()) BrandOrangeDeep
                            else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                        )
                    }
                },
            )
            if (presets.isNotEmpty()) {
                Spacer(Modifier.height(10.dp))
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    presets.forEach { p ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .clip(RoundedCornerShape(20.dp))
                                .background(BrandBlue.copy(alpha = 0.12f))
                                .clickable { onRemove(p) }
                                .padding(start = 12.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
                        ) {
                            Text(p, fontSize = 13.sp, fontWeight = FontWeight.Medium, color = BrandBlueDeep)
                            Spacer(Modifier.width(4.dp))
                            Icon(
                                Icons.Filled.Close,
                                contentDescription = "削除",
                                tint = BrandBlueDeep,
                                modifier = Modifier.size(14.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}

/** 差し込みトークンを挿入する対象。直前にフォーカスしていた欄へ入れる。 */
private enum class EditorField { SUBJECT, BODY }

@Composable
private fun TemplateEditorDialog(
    existing: Template?,
    kind: TemplateKind,
    onDismiss: () -> Unit,
    onSave: (name: String, body: String, email: String, subject: String) -> Unit,
) {
    val isMail = kind == TemplateKind.MAIL
    var name by remember { mutableStateOf(existing?.name ?: "") }
    var email by remember { mutableStateOf(existing?.email ?: "") }
    var subject by remember {
        mutableStateOf(
            TextFieldValue(
                existing?.subject ?: "",
                selection = TextRange((existing?.subject ?: "").length),
            ),
        )
    }
    var body by remember {
        mutableStateOf(
            TextFieldValue(existing?.body ?: "", selection = TextRange((existing?.body ?: "").length)),
        )
    }
    var focused by remember { mutableStateOf(EditorField.BODY) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                when {
                    existing == null && isMail -> "メール定型文を追加"
                    existing == null -> "定型文を追加"
                    isMail -> "メール定型文を編集"
                    else -> "定型文を編集"
                },
            )
        },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("名前") },
                    singleLine = true,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth(),
                    colors = brandTextFieldColors(),
                )
                if (isMail) {
                    Spacer(Modifier.height(10.dp))
                    OutlinedTextField(
                        value = email,
                        onValueChange = { email = it },
                        label = { Text("宛先メールアドレス（任意）") },
                        singleLine = true,
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth(),
                        colors = brandTextFieldColors(),
                    )
                    Spacer(Modifier.height(10.dp))
                    OutlinedTextField(
                        value = subject,
                        onValueChange = { subject = it },
                        label = { Text("件名（任意）") },
                        singleLine = true,
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .onFocusChanged { if (it.isFocused) focused = EditorField.SUBJECT },
                        colors = brandTextFieldColors(),
                    )
                }
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(
                    value = body,
                    onValueChange = { body = it },
                    label = { Text("本文") },
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(170.dp)
                        .onFocusChanged { if (it.isFocused) focused = EditorField.BODY },
                    colors = brandTextFieldColors(),
                )
                Spacer(Modifier.height(10.dp))
                Text(
                    if (isMail && focused == EditorField.SUBJECT) {
                        "タップで「件名」のカーソル位置に挿入"
                    } else {
                        "タップで「本文」のカーソル位置に挿入"
                    },
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(6.dp))
                TokenChipsRow { token ->
                    if (isMail && focused == EditorField.SUBJECT) {
                        subject = insertTokenAt(subject, token)
                    } else {
                        body = insertTokenAt(body, token)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onSave(name, body.text, email, subject.text) },
                enabled = name.isNotBlank(),
            ) { Text("保存") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("キャンセル") }
        },
        // ダイアログ外（スクリム）をタップしても閉じないようにする。
        // デフォルトの挙動だと、キーボード表示中にちょっと外側に触れただけで
        // ダイアログが閉じ、入力中の本文がそのまま失われてしまうため。
        // 閉じるには「キャンセル」か端末の戻るボタンを使う。
        properties = DialogProperties(dismissOnClickOutside = false),
    )
}
