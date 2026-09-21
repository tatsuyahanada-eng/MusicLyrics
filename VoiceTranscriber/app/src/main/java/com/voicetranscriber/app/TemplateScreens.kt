package com.voicetranscriber.app

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
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
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AlternateEmail
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.ArrowDropUp
import androidx.compose.material.icons.filled.CheckBox
import androidx.compose.material.icons.filled.CheckBoxOutlineBlank
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material.icons.filled.Refresh
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
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
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
// フォルダ（開閉リスト）→ 定型文を選ぶ（まとめてモードなら複数選択可）→
//   差し込み項目を入力 → プレビューはその場で自由に編集できる →
//   コピー（定型文タブ） / メール送信（メールタブ）
// 2つのタブは同じ流れなので、仕上げの動作だけを [TemplateAction] で切り替える。
// ---------------------------------------------------------------------------

/** 定型文を組み立てたあとに何をするか。 */
enum class TemplateAction { COPY, MAIL }

@Composable
fun TemplatesPane(
    innerPadding: PaddingValues,
    snackbarHostState: SnackbarHostState,
    viewModel: TemplateViewModel = viewModel(),
) = TemplateWorkPane(innerPadding, snackbarHostState, TemplateAction.COPY, viewModel)

@Composable
fun MailPane(
    innerPadding: PaddingValues,
    snackbarHostState: SnackbarHostState,
    viewModel: TemplateViewModel = viewModel(),
) = TemplateWorkPane(innerPadding, snackbarHostState, TemplateAction.MAIL, viewModel)

@Composable
private fun TemplateWorkPane(
    innerPadding: PaddingValues,
    snackbarHostState: SnackbarHostState,
    action: TemplateAction,
    viewModel: TemplateViewModel,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val store by viewModel.store.collectAsStateWithLifecycle()
    val allTemplates = store.categories.flatMap { it.templates }

    var expandedFolderId by remember { mutableStateOf<String?>(null) }
    // まとめてモード：オンにすると複数の定型文を選んで1つに組み合わせられる
    var combineMode by remember { mutableStateOf(false) }
    // 選択した定型文の ID（選んだ順）。通常モードでは常に0〜1件。
    var selectedIds by remember { mutableStateOf(listOf<String>()) }
    var date by remember { mutableStateOf("") }
    var time1 by remember { mutableStateOf("") }
    var time2 by remember { mutableStateOf("") }
    var name by remember { mutableStateOf("") }
    // メールタブ専用：宛先は選択中の定型文の既定値を初期値にして、その場で直せる
    var mailTo by remember { mutableStateOf("") }

    // 件名・本文のプレビュー。差し込み項目から自動生成されるが、そのままタップして
    // 自由に手直しできる。一度手直しすると、日付などを変えても上書きされない。
    var subjectField by remember { mutableStateOf(TextFieldValue("")) }
    var bodyField by remember { mutableStateOf(TextFieldValue("")) }
    var subjectEdited by remember { mutableStateOf(false) }
    var bodyEdited by remember { mutableStateOf(false) }

    val selectedTemplates = selectedIds.mapNotNull { id -> allTemplates.firstOrNull { it.id == id } }
    val primaryTemplate = selectedTemplates.firstOrNull()
    val isCombining = combineMode && selectedTemplates.size > 1

    fun toggleTemplate(id: String) {
        selectedIds = if (combineMode) {
            if (id in selectedIds) selectedIds - id else selectedIds + id
        } else {
            if (selectedIds == listOf(id)) selectedIds else listOf(id)
        }
    }

    fun combinedBody(): String {
        val sep = fillTemplate(store.commonInsert, date, time1, time2, name)
        val joiner = if (sep.isBlank()) "\n\n" else "\n\n$sep\n\n"
        return selectedTemplates.joinToString(separator = joiner) {
            fillTemplate(it.body, date, time1, time2, name)
        }
    }

    fun combinedSubject(): String = fillTemplate(primaryTemplate?.subject.orEmpty(), date, time1, time2, name)

    // 選択が変わったら、宛先とプレビューを選択内容から作り直す
    LaunchedEffect(selectedIds) {
        mailTo = primaryTemplate?.email.orEmpty()
        subjectEdited = false
        bodyEdited = false
        bodyField = TextFieldValue(combinedBody())
        subjectField = TextFieldValue(combinedSubject())
    }
    // 差し込み項目や共通項目が変わったら、手直し前のプレビューだけ更新する
    LaunchedEffect(date, time1, time2, name, store.commonInsert) {
        if (!bodyEdited) bodyField = TextFieldValue(combinedBody())
        if (!subjectEdited) subjectField = TextFieldValue(combinedSubject())
    }

    val accent = if (action == TemplateAction.MAIL) BrandRed else BrandBlue
    val accentDeep = if (action == TemplateAction.MAIL) BrandRedDeep else BrandBlueDeep
    val actionGradient = if (action == TemplateAction.MAIL) MailGradient else BlueGradient

    val needDate = selectedTemplates.any { it.usesToken(TemplateTokens.DATE) } ||
        (isCombining && TemplateTokens.contains(store.commonInsert, TemplateTokens.DATE))
    val needTime1 = selectedTemplates.any { it.usesToken(TemplateTokens.TIME1) } ||
        (isCombining && TemplateTokens.contains(store.commonInsert, TemplateTokens.TIME1))
    val needTime2 = selectedTemplates.any { it.usesToken(TemplateTokens.TIME2) } ||
        (isCombining && TemplateTokens.contains(store.commonInsert, TemplateTokens.TIME2))
    val needName = selectedTemplates.any { it.usesToken(TemplateTokens.NAME) } ||
        (isCombining && TemplateTokens.contains(store.commonInsert, TemplateTokens.NAME))

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(surfaceWashBrush(accent))
            .padding(innerPadding)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        HeroHeader(
            title = if (action == TemplateAction.MAIL) "メール送信" else "定型文コピー",
            subtitle = if (action == TemplateAction.MAIL) {
                "定型文から宛先・件名・本文を組み立てて送信します"
            } else {
                "フォルダから選んで、差し込み項目を入れてコピー"
            },
            gradient = actionGradient,
        )
        Spacer(Modifier.height(16.dp))

        if (store.categories.isEmpty()) {
            EmptyFoldersHint()
            return@Column
        }

        // まとめてモードの切り替え
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(14.dp))
                .background(MaterialTheme.colorScheme.surface)
                .padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text("複数の定型文をまとめる", fontWeight = FontWeight.Bold, fontSize = 14.sp)
                Text(
                    "オンにすると複数選べます。間には設定した共通項目が入ります。",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    lineHeight = 15.sp,
                )
            }
            Switch(
                checked = combineMode,
                onCheckedChange = { checked ->
                    combineMode = checked
                    if (!checked) selectedIds = selectedIds.take(1)
                },
                colors = SwitchDefaults.colors(
                    checkedThumbColor = Color.White,
                    checkedTrackColor = accent,
                ),
            )
        }
        Spacer(Modifier.height(12.dp))

        SectionLabel("フォルダ", accentDeep)
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
                selectedIds = selectedIds.toSet(),
                combineMode = combineMode,
                onSelectTemplate = { toggleTemplate(it) },
                accent = accent,
                accentDeep = accentDeep,
                gradient = actionGradient,
            )
            Spacer(Modifier.height(10.dp))
        }

        // 選択中チップ（まとめてモードで2件以上のときに順番がわかるように表示）
        if (selectedTemplates.size > 1) {
            Spacer(Modifier.height(4.dp))
            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
            ) {
                selectedTemplates.forEachIndexed { index, t ->
                    SelectedChip(index = index + 1, text = t.name, accent = accent) {
                        toggleTemplate(t.id)
                    }
                }
            }
        }

        // 差し込みフィールド＋プレビュー＋実行ボタン
        if (selectedTemplates.isNotEmpty()) {
            Spacer(Modifier.height(8.dp))
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(20.dp))
                    .background(
                        Brush.verticalGradient(
                            listOf(accent.copy(alpha = 0.12f), accent.copy(alpha = 0.02f)),
                        ),
                    )
                    .border(1.dp, accent.copy(alpha = 0.35f), RoundedCornerShape(20.dp))
                    .padding(14.dp),
            ) {
                Column {
                    Text(
                        if (isCombining) {
                            "${selectedTemplates.size}件をまとめています"
                        } else {
                            primaryTemplate?.name.orEmpty()
                        },
                        fontWeight = FontWeight.Bold,
                        fontSize = 15.sp,
                        color = accentDeep,
                    )
                    Spacer(Modifier.height(10.dp))

                    if (needDate) {
                        DropdownField("日付", dateOptions(), date, accent, accentDeep) { date = it }
                        Spacer(Modifier.height(10.dp))
                    }
                    if (needTime1) {
                        DropdownField("時間1", timeOptions(), time1, accent, accentDeep) { time1 = it }
                        Spacer(Modifier.height(10.dp))
                    }
                    if (needTime2) {
                        DropdownField("時間2", timeOptions(), time2, accent, accentDeep) { time2 = it }
                        Spacer(Modifier.height(10.dp))
                    }
                    if (needName) {
                        OutlinedTextField(
                            value = name,
                            onValueChange = { name = it },
                            label = { Text("氏名") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                            colors = accentTextFieldColors(accentDeep),
                        )
                        Spacer(Modifier.height(10.dp))
                    }

                    if (action == TemplateAction.MAIL) {
                        OutlinedTextField(
                            value = mailTo,
                            onValueChange = { mailTo = it },
                            label = { Text("宛先メールアドレス") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                            colors = accentTextFieldColors(accentDeep),
                        )
                        Spacer(Modifier.height(10.dp))
                    }

                    // 件名（メールのみ）・本文：自動生成されるが、その場で自由に編集できる
                    if (action == TemplateAction.MAIL) {
                        EditablePreviewField(
                            label = "件名",
                            value = subjectField,
                            singleLine = true,
                            minHeight = 0.dp,
                            accent = accent,
                            accentDeep = accentDeep,
                            showReset = subjectEdited,
                            onReset = {
                                subjectField = TextFieldValue(combinedSubject())
                                subjectEdited = false
                            },
                            onValueChange = {
                                subjectField = it
                                subjectEdited = true
                            },
                        )
                        Spacer(Modifier.height(10.dp))
                    }
                    EditablePreviewField(
                        label = "本文（タップして自由に編集できます）",
                        value = bodyField,
                        singleLine = false,
                        minHeight = 140.dp,
                        accent = accent,
                        accentDeep = accentDeep,
                        showReset = bodyEdited,
                        onReset = {
                            bodyField = TextFieldValue(combinedBody())
                            bodyEdited = false
                        },
                        onValueChange = {
                            bodyField = it
                            bodyEdited = true
                        },
                    )
                    Spacer(Modifier.height(14.dp))

                    if (action == TemplateAction.MAIL) {
                        GradientActionButton(
                            text = "メールを作成して送信",
                            icon = Icons.AutoMirrored.Filled.Send,
                            gradient = actionGradient,
                            enabled = mailTo.isNotBlank(),
                        ) {
                            val ok = sendMail(context, mailTo, subjectField.text, bodyField.text)
                            scope.launch {
                                snackbarHostState.showSnackbar(
                                    if (ok) "メールアプリを開きました" else "メールアプリが見つかりません",
                                )
                            }
                        }
                        Spacer(Modifier.height(8.dp))
                        OutlinedButton(
                            onClick = {
                                copyText(context, bodyField.text)
                                scope.launch { snackbarHostState.showSnackbar("本文をコピーしました") }
                            },
                            shape = RoundedCornerShape(50),
                            modifier = Modifier.fillMaxWidth(),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = accentDeep),
                        ) {
                            Icon(
                                Icons.Filled.ContentCopy,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp),
                            )
                            Spacer(Modifier.width(6.dp))
                            Text("本文だけコピー")
                        }
                    } else {
                        GradientActionButton(
                            text = "コピー",
                            icon = Icons.Filled.ContentCopy,
                            gradient = actionGradient,
                            enabled = true,
                        ) {
                            copyText(context, bodyField.text)
                            scope.launch { snackbarHostState.showSnackbar("コピーしました") }
                        }
                    }
                }
            }
        }

        Spacer(Modifier.height(24.dp))
    }
}

/** 差し込みから自動生成されるが、タップしてそのまま書き換えられるプレビュー欄。 */
@Composable
private fun EditablePreviewField(
    label: String,
    value: TextFieldValue,
    singleLine: Boolean,
    minHeight: androidx.compose.ui.unit.Dp,
    accent: Color,
    accentDeep: Color,
    showReset: Boolean,
    onReset: () -> Unit,
    onValueChange: (TextFieldValue) -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            label,
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        if (showReset) {
            TextButton(onClick = onReset, contentPadding = PaddingValues(horizontal = 6.dp)) {
                Icon(
                    Icons.Filled.Refresh,
                    contentDescription = null,
                    tint = accentDeep,
                    modifier = Modifier.size(14.dp),
                )
                Spacer(Modifier.width(3.dp))
                Text("自動入力に戻す", fontSize = 11.sp, color = accentDeep)
            }
        }
    }
    Spacer(Modifier.height(2.dp))
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            singleLine = singleLine,
            modifier = Modifier
                .fillMaxWidth()
                .let { if (minHeight > 0.dp) it.height(minHeight) else it },
            textStyle = if (singleLine) {
                MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Bold)
            } else {
                MaterialTheme.typography.bodyLarge.copy(fontSize = 17.sp, lineHeight = 25.sp)
            },
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = accent,
                cursorColor = accent,
                focusedContainerColor = MaterialTheme.colorScheme.surface,
                unfocusedContainerColor = MaterialTheme.colorScheme.surface,
                unfocusedBorderColor = Color.Transparent,
            ),
        )
    }
}

/** 選択中の定型文チップ（まとめてモード）。番号つきで、タップで選択解除。 */
@Composable
private fun SelectedChip(index: Int, text: String, accent: Color, onRemove: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .clip(RoundedCornerShape(20.dp))
            .background(accent.copy(alpha = 0.14f))
            .clickable { onRemove() }
            .padding(start = 10.dp, end = 6.dp, top = 6.dp, bottom = 6.dp),
    ) {
        Text("$index. $text", fontSize = 12.sp, fontWeight = FontWeight.Medium, color = accent)
        Spacer(Modifier.width(4.dp))
        Icon(Icons.Filled.Close, contentDescription = "選択解除", tint = accent, modifier = Modifier.size(14.dp))
    }
}

/** 画面上部のグラデーション見出し。全体がのっぺりしないようにアクセントを置く。 */
@Composable
private fun HeroHeader(title: String, subtitle: String, gradient: Brush) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(gradient)
            .padding(horizontal = 18.dp, vertical = 16.dp),
    ) {
        Column {
            Text(title, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 19.sp)
            Spacer(Modifier.height(4.dp))
            Text(
                subtitle,
                color = Color.White.copy(alpha = 0.85f),
                fontSize = 13.sp,
                lineHeight = 18.sp,
            )
        }
    }
}

@Composable
private fun SectionLabel(text: String, color: Color) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier
                .size(width = 4.dp, height = 16.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(color),
        )
        Spacer(Modifier.width(8.dp))
        Text(text, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleSmall)
    }
}

@Composable
private fun GradientActionButton(
    text: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    gradient: Brush,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
        modifier = Modifier
            .fillMaxWidth()
            .height(52.dp)
            .clip(RoundedCornerShape(50))
            .background(gradient)
            .then(if (enabled) Modifier.clickable { onClick() } else Modifier)
            .then(
                if (enabled) Modifier
                else Modifier.background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.35f)),
            ),
    ) {
        Icon(icon, contentDescription = null, tint = Color.White, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(8.dp))
        Text(text, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 16.sp)
    }
}

@Composable
private fun EmptyFoldersHint() {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(BrandBlue.copy(alpha = 0.08f))
            .border(1.dp, BrandBlue.copy(alpha = 0.25f), RoundedCornerShape(16.dp))
            .padding(20.dp),
    ) {
        Icon(Icons.Filled.Folder, contentDescription = null, tint = BrandBlueDeep)
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
    selectedIds: Set<String>,
    combineMode: Boolean,
    onSelectTemplate: (String) -> Unit,
    accent: Color,
    accentDeep: Color,
    gradient: Brush,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
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
                        .clip(RoundedCornerShape(11.dp))
                        .background(gradient),
                ) {
                    Icon(
                        Icons.Filled.Folder,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(18.dp),
                    )
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
                    tint = accentDeep,
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
                            val isSel = t.id in selectedIds
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(
                                        if (isSel) accent.copy(alpha = 0.14f) else Color.Transparent,
                                    )
                                    .clickable { onSelectTemplate(t.id) }
                                    .padding(horizontal = 10.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Icon(
                                    when {
                                        combineMode && isSel -> Icons.Filled.CheckBox
                                        combineMode -> Icons.Filled.CheckBoxOutlineBlank
                                        isSel -> Icons.Filled.CheckCircle
                                        else -> Icons.Filled.RadioButtonUnchecked
                                    },
                                    contentDescription = null,
                                    tint = if (isSel) accentDeep else MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(20.dp),
                                )
                                Spacer(Modifier.width(10.dp))
                                Text(
                                    t.name,
                                    modifier = Modifier.weight(1f),
                                    fontWeight = if (isSel) FontWeight.Bold else FontWeight.Normal,
                                    color = if (isSel) accentDeep else MaterialTheme.colorScheme.onSurface,
                                )
                                if (t.email.isNotBlank()) {
                                    Icon(
                                        Icons.Filled.AlternateEmail,
                                        contentDescription = "メール宛先あり",
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.size(16.dp),
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------
// 定型文：設定画面（フォルダ・定型文の追加/編集/削除、共通項目の設定）
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
            .background(surfaceWashBrush(BrandBlue))
            .padding(innerPadding)
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
    ) {
        HeroHeader(
            title = "定型文の設定",
            subtitle = "フォルダごとに定型文・宛先・件名を登録できます",
            gradient = BlueGradient,
        )
        Spacer(Modifier.height(14.dp))

        Text(
            "本文と件名には {日付}{時間1}{時間2}{氏名} を挿入できます。\n" +
                "宛先メールアドレスを登録しておくと、メールタブからそのまま送信できます。",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontSize = 13.sp,
            lineHeight = 19.sp,
        )
        Spacer(Modifier.height(16.dp))

        CommonInsertEditor(commonInsert = store.commonInsert, onSave = viewModel::setCommonInsert)
        Spacer(Modifier.height(20.dp))

        // 新規フォルダ追加
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = newCategory,
                onValueChange = { newCategory = it },
                label = { Text("新しいフォルダ名") },
                singleLine = true,
                modifier = Modifier.weight(1f),
                colors = accentTextFieldColors(BrandBlueDeep),
            )
            Spacer(Modifier.width(8.dp))
            Button(
                onClick = {
                    viewModel.addCategory(newCategory)
                    newCategory = ""
                },
                enabled = newCategory.isNotBlank(),
                shape = RoundedCornerShape(14.dp),
                colors = ButtonDefaults.buttonColors(containerColor = BrandBlueDeep),
            ) {
                Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                Text("追加")
            }
        }

        Spacer(Modifier.height(16.dp))

        store.categories.forEach { c ->
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 12.dp),
                shape = RoundedCornerShape(16.dp),
                elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
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
                                        tint = if (index > 0) BrandBlueDeep
                                        else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
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
                                        tint = if (index < c.templates.lastIndex) BrandBlueDeep
                                        else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                                    )
                                }
                            }
                            Spacer(Modifier.width(4.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(t.name)
                                if (t.email.isNotBlank()) {
                                    Text(
                                        t.email,
                                        fontSize = 11.sp,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                            IconButton(onClick = { editorTarget = c.id to t }) {
                                Icon(Icons.Filled.Edit, contentDescription = "編集", tint = BrandBlueDeep)
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
                        shape = RoundedCornerShape(14.dp),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = BrandBlueDeep),
                    ) {
                        Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(18.dp))
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
            onSave = { nm, body, email, subject ->
                if (existing == null) {
                    viewModel.addTemplate(categoryId, nm, body, email, subject)
                } else {
                    viewModel.updateTemplate(categoryId, existing.id, nm, body, email, subject)
                }
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

/**
 * 複数の定型文をまとめて使うときに、間へ差し込む共通の文言（署名・区切りなど）の設定欄。
 * 入力するたびにそのまま保存される。
 */
@Composable
private fun CommonInsertEditor(commonInsert: String, onSave: (String) -> Unit) {
    var field by remember {
        mutableStateOf(TextFieldValue(commonInsert, selection = TextRange(commonInsert.length)))
    }

    fun insertAtCursor(token: String) {
        val text = field.text
        val start = field.selection.start.coerceIn(0, text.length)
        val end = field.selection.end.coerceIn(0, text.length)
        val newText = text.substring(0, start) + token + text.substring(end)
        field = TextFieldValue(newText, selection = TextRange(start + token.length))
        onSave(newText)
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Text("共通項目（複数の定型文をまとめるときに間へ挿入）", fontWeight = FontWeight.Bold, fontSize = 14.sp)
            Spacer(Modifier.height(4.dp))
            Text(
                "署名や区切り線など。空のままなら改行だけで区切ります。入力するたびに自動で保存されます。",
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                lineHeight = 15.sp,
            )
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = field,
                onValueChange = {
                    field = it
                    onSave(it.text)
                },
                placeholder = { Text("例：以上、よろしくお願いいたします。") },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(90.dp),
                colors = accentTextFieldColors(BrandBlueDeep),
            )
            Spacer(Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TemplateTokens.all.forEach { token ->
                    SelectChip(text = token) { insertAtCursor(token) }
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
    onDismiss: () -> Unit,
    onSave: (name: String, body: String, email: String, subject: String) -> Unit,
) {
    var name by remember { mutableStateOf(existing?.name ?: "") }
    var email by remember { mutableStateOf(existing?.email ?: "") }
    // カーソル（選択範囲）の位置を保持し、差し込み項目はそこへ挿入する
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

    fun insertAtCursor(current: TextFieldValue, token: String): TextFieldValue {
        val text = current.text
        val start = current.selection.start.coerceIn(0, text.length)
        val end = current.selection.end.coerceIn(0, text.length)
        return TextFieldValue(
            text.substring(0, start) + token + text.substring(end),
            selection = TextRange(start + token.length),
        )
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
                    colors = accentTextFieldColors(BrandBlueDeep),
                )
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(
                    value = email,
                    onValueChange = { email = it },
                    label = { Text("宛先メールアドレス（任意）") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    colors = accentTextFieldColors(BrandRedDeep),
                )
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(
                    value = subject,
                    onValueChange = { subject = it },
                    label = { Text("件名（任意）") },
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .onFocusChanged { if (it.isFocused) focused = EditorField.SUBJECT },
                    colors = accentTextFieldColors(BrandRedDeep),
                )
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(
                    value = body,
                    onValueChange = { body = it },
                    label = { Text("本文") },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(160.dp)
                        .onFocusChanged { if (it.isFocused) focused = EditorField.BODY },
                    colors = accentTextFieldColors(BrandBlueDeep),
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    if (focused == EditorField.SUBJECT) {
                        "タップで「件名」のカーソル位置に挿入:"
                    } else {
                        "タップで「本文」のカーソル位置に挿入:"
                    },
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(4.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TemplateTokens.all.forEach { token ->
                        SelectChip(text = token) {
                            if (focused == EditorField.SUBJECT) {
                                subject = insertAtCursor(subject, token)
                            } else {
                                body = insertAtCursor(body, token)
                            }
                        }
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
    )
}

// ---------------------------------------------------------------------------
// 共通パーツ
// ---------------------------------------------------------------------------

/** 背景にうっすらブランドカラーを敷いて、白一色ののっぺり感をなくす。 */
@Composable
private fun surfaceWashBrush(accent: Color): Brush = Brush.verticalGradient(
    listOf(
        accent.copy(alpha = 0.07f),
        MaterialTheme.colorScheme.background,
    ),
)

@Composable
private fun accentTextFieldColors(accent: Color) = OutlinedTextFieldDefaults.colors(
    focusedBorderColor = accent,
    focusedLabelColor = accent,
    cursorColor = accent,
)

@Composable
private fun SelectChip(text: String, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(20.dp))
            .background(BrandBlue.copy(alpha = 0.12f))
            .border(1.dp, BrandBlue.copy(alpha = 0.35f), RoundedCornerShape(20.dp))
            .clickable { onClick() }
            .padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        Text(text, color = BrandBlueDeep, fontSize = 13.sp, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun DropdownField(
    label: String,
    options: List<String>,
    selected: String,
    accent: Color,
    accentDeep: Color,
    onSelect: (String) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(label, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(2.dp))
        Box {
            OutlinedButton(
                onClick = { expanded = true },
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.outlinedButtonColors(
                    contentColor = MaterialTheme.colorScheme.onSurface,
                    containerColor = MaterialTheme.colorScheme.surface,
                ),
                border = androidx.compose.foundation.BorderStroke(1.dp, accent.copy(alpha = 0.5f)),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    selected.ifBlank { "選択してください" },
                    modifier = Modifier.weight(1f),
                    color = if (selected.isBlank()) MaterialTheme.colorScheme.onSurfaceVariant
                    else MaterialTheme.colorScheme.onSurface,
                )
                Icon(Icons.Filled.ArrowDropDown, contentDescription = null, tint = accentDeep)
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

/** 端末のメールアプリを宛先・件名・本文つきで開く。 */
private fun sendMail(context: Context, to: String, subject: String, body: String): Boolean {
    val intent = Intent(Intent.ACTION_SENDTO).apply {
        data = Uri.parse("mailto:")
        putExtra(Intent.EXTRA_EMAIL, arrayOf(to.trim()))
        putExtra(Intent.EXTRA_SUBJECT, subject)
        putExtra(Intent.EXTRA_TEXT, body)
    }
    return try {
        context.startActivity(Intent.createChooser(intent, "メールアプリを選択"))
        true
    } catch (e: ActivityNotFoundException) {
        false
    }
}
