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
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.AlternateEmail
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.CheckBox
import androidx.compose.material.icons.filled.CheckBoxOutlineBlank
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

// ---------------------------------------------------------------------------
// 定型文／メール：使用画面
// フォルダ（開閉リスト）→ 定型文を選ぶ（まとめてモードなら複数選択可）→
//   差し込み項目を入力 → プレビューはその場で自由に編集できる →
//   コピー（定型文タブ） / メール送信（メールタブ）
// 配色は全タブ共通（ブルー＋オレンジ）。タブによって色は変えない。
// ---------------------------------------------------------------------------

@Composable
fun TemplatesPane(
    innerPadding: PaddingValues,
    snackbarHostState: SnackbarHostState,
    viewModel: TemplateViewModel = viewModel(),
) = TemplateWorkPane(innerPadding, snackbarHostState, TemplateKind.COPY, viewModel)

@Composable
fun MailPane(
    innerPadding: PaddingValues,
    snackbarHostState: SnackbarHostState,
    viewModel: TemplateViewModel = viewModel(),
) = TemplateWorkPane(innerPadding, snackbarHostState, TemplateKind.MAIL, viewModel)

@Composable
private fun TemplateWorkPane(
    innerPadding: PaddingValues,
    snackbarHostState: SnackbarHostState,
    kind: TemplateKind,
    viewModel: TemplateViewModel,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val store by viewModel.store.collectAsStateWithLifecycle()
    val categories = store.categoriesOf(kind)
    val commonInsert = store.commonInsertOf(kind)
    val amountPresets = store.amountPresetsOf(kind)
    val isMail = kind == TemplateKind.MAIL
    val allTemplates = categories.flatMap { it.templates }

    var expandedFolderId by remember(kind) { mutableStateOf<String?>(null) }
    // まとめてモード：オンにすると複数の定型文を選んで1つに組み合わせられる
    var combineMode by remember(kind) { mutableStateOf(false) }
    // 選択した定型文の ID（選んだ順）。通常モードでは常に0〜1件。
    var selectedIds by remember(kind) { mutableStateOf(listOf<String>()) }
    var date by remember { mutableStateOf("") }
    // 時間1・時間2は 9:00〜10:00 が最も使われる想定なので、既定値として入れておく
    var time1 by remember { mutableStateOf("09:00") }
    var time2 by remember { mutableStateOf("10:00") }
    var amount1 by remember { mutableStateOf("") }
    var amount2 by remember { mutableStateOf("") }
    var name by remember { mutableStateOf("") }
    var mailTo by remember(kind) { mutableStateOf("") }

    // 件名・本文のプレビュー。差し込み項目から自動生成されるが、そのままタップして
    // 自由に手直しできる。一度手直しすると、日付などを変えても上書きされない。
    var subjectField by remember(kind) { mutableStateOf(TextFieldValue("")) }
    var bodyField by remember(kind) { mutableStateOf(TextFieldValue("")) }
    var subjectEdited by remember(kind) { mutableStateOf(false) }
    var bodyEdited by remember(kind) { mutableStateOf(false) }

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

    val tokenValues = TokenValues(date, time1, time2, amount1, amount2, name)

    fun combinedBody(): String {
        val sep = tokenValues.fill(commonInsert)
        val joiner = if (sep.isBlank()) "\n\n" else "\n\n$sep\n\n"
        return selectedTemplates.joinToString(separator = joiner) { tokenValues.fill(it.body) }
    }

    fun combinedSubject(): String = tokenValues.fill(primaryTemplate?.subject.orEmpty())

    // 選択が変わったら、宛先とプレビューを選択内容から作り直す
    LaunchedEffect(selectedIds, kind) {
        mailTo = primaryTemplate?.email.orEmpty()
        subjectEdited = false
        bodyEdited = false
        bodyField = TextFieldValue(combinedBody())
        subjectField = TextFieldValue(combinedSubject())
    }
    // 差し込み項目や共通項目が変わったら、手直し前のプレビューだけ更新する
    LaunchedEffect(date, time1, time2, amount1, amount2, name, commonInsert) {
        if (!bodyEdited) bodyField = TextFieldValue(combinedBody())
        if (!subjectEdited) subjectField = TextFieldValue(combinedSubject())
    }

    fun needs(token: String) = selectedTemplates.any { it.usesToken(token) } ||
        (isCombining && TemplateTokens.contains(commonInsert, token))

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(surfaceWashBrush())
            .padding(innerPadding)
            // キーボード表示ぶんの余白を確保し、本文欄などがキーボードに
            // 隠れたままにならず、スクロールして引き上げられるようにする
            .imePadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        HeroHeader(
            title = if (isMail) "メール送信" else "定型文コピー",
            // 自動折り返しに任せると「ー」などが1文字だけ孤立して見苦しいので、
            // 読点の位置で明示的に改行する
            subtitle = if (isMail) {
                "メール用の定型文から、\n宛先・件名・本文を組み立てて送信します"
            } else {
                "フォルダから選んで、\n差し込み項目を入れてコピー"
            },
        )
        Spacer(Modifier.height(16.dp))

        if (categories.isEmpty()) {
            EmptyFoldersHint(isMail)
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
                    "オンにすると複数選べます。間には共通項目が入ります。",
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
                    checkedTrackColor = BrandOrange,
                ),
            )
        }
        Spacer(Modifier.height(12.dp))

        SectionLabel("フォルダ")
        Spacer(Modifier.height(8.dp))

        categories.forEach { folder ->
            val isExpanded = folder.id == expandedFolderId ||
                (expandedFolderId == null && folder == categories.first())
            FolderCard(
                folder = folder,
                expanded = isExpanded,
                onToggle = { expandedFolderId = if (isExpanded) "" else folder.id },
                selectedIds = selectedIds.toSet(),
                combineMode = combineMode,
                showMailBadge = isMail,
                onSelectTemplate = { toggleTemplate(it) },
            )
            Spacer(Modifier.height(10.dp))
        }

        // 選択中チップ（まとめてモードで2件以上のときに順番がわかるように表示）
        if (selectedTemplates.size > 1) {
            Spacer(Modifier.height(4.dp))
            SelectedChips(selectedTemplates) { toggleTemplate(it) }
        }

        // 差し込みフィールド＋プレビュー＋実行ボタン
        if (selectedTemplates.isNotEmpty()) {
            Spacer(Modifier.height(10.dp))
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(20.dp))
                    .background(
                        Brush.verticalGradient(
                            listOf(BrandBlue.copy(alpha = 0.10f), BrandOrange.copy(alpha = 0.05f)),
                        ),
                    )
                    .border(1.dp, BrandBlue.copy(alpha = 0.30f), RoundedCornerShape(20.dp))
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
                        color = BrandBlueDeep,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(Modifier.height(10.dp))

                    if (needs(TemplateTokens.DATE)) {
                        DropdownField("日付", dateOptions(), date) { date = it }
                        Spacer(Modifier.height(10.dp))
                    }
                    val showTime1 = needs(TemplateTokens.TIME1)
                    val showTime2 = needs(TemplateTokens.TIME2)
                    if (showTime1 || showTime2) {
                        // 縦に積むと幅を取りすぎるので、時間1・時間2は横並びにする
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            if (showTime1) {
                                Box(modifier = Modifier.weight(1f)) {
                                    DropdownField("時間1", timeOptions(), time1) { time1 = it }
                                }
                            }
                            if (showTime2) {
                                Box(modifier = Modifier.weight(1f)) {
                                    DropdownField("時間2", timeOptions(), time2) { time2 = it }
                                }
                            }
                        }
                        Spacer(Modifier.height(10.dp))
                    }
                    val showAmount1 = needs(TemplateTokens.AMOUNT1)
                    val showAmount2 = needs(TemplateTokens.AMOUNT2)
                    if (showAmount1 || showAmount2) {
                        // 日付・時間と同じく、金額1・金額2も横並びにする
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            if (showAmount1) {
                                Box(modifier = Modifier.weight(1f)) {
                                    AmountField("金額1", amount1, amountPresets) { amount1 = it }
                                }
                            }
                            if (showAmount2) {
                                Box(modifier = Modifier.weight(1f)) {
                                    AmountField("金額2", amount2, amountPresets) { amount2 = it }
                                }
                            }
                        }
                        Spacer(Modifier.height(10.dp))
                    }
                    if (needs(TemplateTokens.NAME)) {
                        OutlinedTextField(
                            value = name,
                            onValueChange = { name = it },
                            label = { Text("氏名") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                            colors = brandTextFieldColors(),
                        )
                        Spacer(Modifier.height(10.dp))
                    }

                    if (isMail) {
                        OutlinedTextField(
                            value = mailTo,
                            onValueChange = { mailTo = it },
                            label = { Text("宛先メールアドレス") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                            colors = brandTextFieldColors(),
                        )
                        Spacer(Modifier.height(10.dp))
                        EditablePreviewField(
                            label = "件名",
                            value = subjectField,
                            singleLine = true,
                            fieldHeight = 0.dp,
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
                        fieldHeight = 160.dp,
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

                    if (isMail) {
                        GradientActionButton(
                            text = "メールを作成して送信",
                            icon = Icons.AutoMirrored.Filled.Send,
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
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = BrandBlueDeep),
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
    fieldHeight: Dp,
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
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (showReset) {
            TextButton(onClick = onReset, contentPadding = PaddingValues(horizontal = 6.dp)) {
                Icon(
                    Icons.Filled.Refresh,
                    contentDescription = null,
                    tint = BrandOrangeDeep,
                    modifier = Modifier.size(14.dp),
                )
                Spacer(Modifier.width(3.dp))
                Text("自動入力に戻す", fontSize = 11.sp, color = BrandOrangeDeep)
            }
        }
    }
    Spacer(Modifier.height(2.dp))
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        singleLine = singleLine,
        shape = RoundedCornerShape(14.dp),
        modifier = Modifier
            .fillMaxWidth()
            .then(if (fieldHeight > 0.dp) Modifier.height(fieldHeight) else Modifier),
        textStyle = if (singleLine) {
            MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Bold)
        } else {
            MaterialTheme.typography.bodyLarge.copy(fontSize = 17.sp, lineHeight = 25.sp)
        },
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = BrandBlue,
            unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant,
            cursorColor = BrandBlue,
            focusedContainerColor = MaterialTheme.colorScheme.surface,
            unfocusedContainerColor = MaterialTheme.colorScheme.surface,
        ),
    )
}

/** まとめてモードで選択中の定型文（番号つき）。タップで選択解除。 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SelectedChips(templates: List<Template>, onRemove: (String) -> Unit) {
    FlowRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        templates.forEachIndexed { index, t ->
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .clip(RoundedCornerShape(20.dp))
                    .background(BrandOrange.copy(alpha = 0.18f))
                    .clickable { onRemove(t.id) }
                    .padding(start = 10.dp, end = 6.dp, top = 6.dp, bottom = 6.dp),
            ) {
                Text(
                    "${index + 1}. ${t.name}",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium,
                    color = BrandOrangeDeep,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.width(4.dp))
                Icon(
                    Icons.Filled.Close,
                    contentDescription = "選択解除",
                    tint = BrandOrangeDeep,
                    modifier = Modifier.size(14.dp),
                )
            }
        }
    }
}

@Composable
private fun EmptyFoldersHint(isMail: Boolean) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(BrandBlue.copy(alpha = 0.07f))
            .border(1.dp, BrandBlue.copy(alpha = 0.25f), RoundedCornerShape(16.dp))
            .padding(20.dp),
    ) {
        Icon(Icons.Filled.Folder, contentDescription = null, tint = BrandBlueDeep)
        Spacer(Modifier.height(8.dp))
        Text(
            if (isMail) {
                "メール用のフォルダがまだありません。\n" +
                    "右上の設定（⚙）→「メール」からフォルダと定型文を追加してください。"
            } else {
                "フォルダがまだありません。\n" +
                    "右上の設定（⚙）→「定型文」からフォルダと定型文を追加してください。"
            },
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontSize = 15.sp,
            lineHeight = 23.sp,
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
    showMailBadge: Boolean,
    onSelectTemplate: (String) -> Unit,
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
                        .background(BlueGradient),
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
                    Text(
                        folder.name,
                        fontWeight = FontWeight.Bold,
                        fontSize = 16.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        "${folder.templates.size}件の定型文",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                    )
                }
                Icon(
                    if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                    contentDescription = null,
                    tint = BrandBlueDeep,
                )
            }

            if (expanded) {
                if (folder.templates.isEmpty()) {
                    Text(
                        "このフォルダには定型文がありません。設定から追加してください。",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 13.sp,
                        lineHeight = 19.sp,
                        modifier = Modifier.padding(start = 16.dp, end = 14.dp, bottom = 12.dp),
                    )
                } else {
                    Column(modifier = Modifier.padding(start = 12.dp, end = 12.dp, bottom = 10.dp)) {
                        folder.templates.forEach { t ->
                            val isSel = t.id in selectedIds
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .heightIn(min = 46.dp)
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(
                                        if (isSel) BrandBlue.copy(alpha = 0.12f) else Color.Transparent,
                                    )
                                    .clickable { onSelectTemplate(t.id) }
                                    .padding(horizontal = 10.dp, vertical = 8.dp),
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
                                    tint = if (isSel) BrandBlueDeep else MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(20.dp),
                                )
                                Spacer(Modifier.width(10.dp))
                                Text(
                                    t.name,
                                    modifier = Modifier.weight(1f),
                                    fontWeight = if (isSel) FontWeight.Bold else FontWeight.Normal,
                                    color = if (isSel) BrandBlueDeep else MaterialTheme.colorScheme.onSurface,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                if (showMailBadge && t.email.isNotBlank()) {
                                    Spacer(Modifier.width(6.dp))
                                    Icon(
                                        Icons.Filled.AlternateEmail,
                                        contentDescription = "メール宛先あり",
                                        tint = BrandOrangeDeep,
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
// 共通パーツ（設定画面からも使うので internal）
// ---------------------------------------------------------------------------

/**
 * オレンジの縦アクセントバー。見出し類で繰り返し使う、アプリ共通のワンポイント。
 * 単色ではなく明→暗のグラデーションにして、太め・角丸強めでしっかり主張させる。
 */
@Composable
internal fun AccentBar(height: Dp) {
    Box(
        modifier = Modifier
            .size(width = 6.dp, height = height)
            .clip(RoundedCornerShape(3.dp))
            .background(
                Brush.verticalGradient(listOf(Color(0xFFFFC168), BrandOrange, BrandOrangeDeep)),
            ),
    )
}

/** 画面上部のグラデーション見出し。全タブ共通のブルーグラデーション。 */
@Composable
internal fun HeroHeader(title: String, subtitle: String) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(BlueGradient)
            .padding(horizontal = 18.dp, vertical = 16.dp),
    ) {
        Row {
            AccentBar(height = 44.dp)
            Spacer(Modifier.width(12.dp))
            Column {
                Text(title, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 19.sp)
                Spacer(Modifier.height(6.dp))
                Text(
                    subtitle,
                    color = Color.White.copy(alpha = 0.88f),
                    fontSize = 13.sp,
                    lineHeight = 19.sp,
                )
            }
        }
    }
}

@Composable
internal fun SectionLabel(text: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        AccentBar(height = 20.dp)
        Spacer(Modifier.width(8.dp))
        Text(text, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleSmall)
    }
}

/** 主要アクションのボタン。全タブ共通でオレンジのグラデーション。 */
@Composable
internal fun GradientActionButton(
    text: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val disabled = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.30f)
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
        modifier = Modifier
            .fillMaxWidth()
            .height(52.dp)
            .clip(RoundedCornerShape(50))
            .background(if (enabled) OrangeGradient else Brush.horizontalGradient(listOf(disabled, disabled)))
            .then(if (enabled) Modifier.clickable { onClick() } else Modifier),
    ) {
        Icon(icon, contentDescription = null, tint = Color.White, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(8.dp))
        Text(text, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 16.sp)
    }
}

/** 背景にうっすらブランドカラーを敷いて、白一色ののっぺり感をなくす。 */
@Composable
internal fun surfaceWashBrush(): Brush = Brush.verticalGradient(
    listOf(
        BrandBlue.copy(alpha = 0.08f),
        MaterialTheme.colorScheme.background,
    ),
)

@Composable
internal fun brandTextFieldColors() = OutlinedTextFieldDefaults.colors(
    focusedBorderColor = BrandBlue,
    focusedLabelColor = BrandBlueDeep,
    cursorColor = BrandBlue,
    // 背景の薄いグラデーション（アクセントカラーの透過オーバーレイ）が
    // 透けてグレーっぽく見えてしまうため、常に不透明な白（surface）で塗る。
    focusedContainerColor = MaterialTheme.colorScheme.surface,
    unfocusedContainerColor = MaterialTheme.colorScheme.surface,
)

/** 差し込みトークンのチップ。幅が足りなければ自動で折り返す。 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun TokenChipsRow(onInsert: (String) -> Unit) {
    FlowRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        TemplateTokens.all.forEach { token ->
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(20.dp))
                    .background(BrandOrange.copy(alpha = 0.14f))
                    .border(1.dp, BrandOrange.copy(alpha = 0.45f), RoundedCornerShape(20.dp))
                    .clickable { onInsert(token) }
                    .padding(horizontal = 12.dp, vertical = 8.dp),
            ) {
                Text(token, color = BrandOrangeDeep, fontSize = 13.sp, fontWeight = FontWeight.Medium)
            }
        }
    }
}

/** カーソル位置にトークンを差し込む。 */
internal fun insertTokenAt(current: TextFieldValue, token: String): TextFieldValue {
    val text = current.text
    val start = current.selection.start.coerceIn(0, text.length)
    val end = current.selection.end.coerceIn(0, text.length)
    return TextFieldValue(
        text.substring(0, start) + token + text.substring(end),
        selection = TextRange(start + token.length),
    )
}

/**
 * 金額の入力欄。自由入力だが、設定に登録したプリセットがあれば
 * タップで即入力できるチップを下に並べる（日付・時間のドロップダウンに近い使い方）。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AmountField(
    label: String,
    value: String,
    presets: List<String>,
    onValueChange: (String) -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            label = { Text(label) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier.fillMaxWidth(),
            colors = brandTextFieldColors(),
        )
        if (presets.isNotEmpty()) {
            Spacer(Modifier.height(6.dp))
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                presets.forEach { p ->
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(12.dp))
                            .background(BrandBlue.copy(alpha = 0.10f))
                            .border(1.dp, BrandBlue.copy(alpha = 0.30f), RoundedCornerShape(12.dp))
                            .clickable { onValueChange(p) }
                            .padding(horizontal = 10.dp, vertical = 6.dp),
                    ) {
                        Text(p, fontSize = 12.sp, color = BrandBlueDeep, maxLines = 1)
                    }
                }
            }
        }
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
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.outlinedButtonColors(
                    contentColor = MaterialTheme.colorScheme.onSurface,
                    containerColor = MaterialTheme.colorScheme.surface,
                ),
                border = androidx.compose.foundation.BorderStroke(1.dp, BrandBlue.copy(alpha = 0.45f)),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    selected.ifBlank { "選択してください" },
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    color = if (selected.isBlank()) MaterialTheme.colorScheme.onSurfaceVariant
                    else MaterialTheme.colorScheme.onSurface,
                )
                Icon(Icons.Filled.ArrowDropDown, contentDescription = null, tint = BrandBlueDeep)
            }
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                // 開くたびに一番上からではなく、選択中の値の少し上から表示する
                // （そのままだと毎回リストの先頭に戻ってしまい、時間欄などで
                // 選び直すのに毎回長くスクロールする必要があった）。
                val selectedIndex = options.indexOf(selected).coerceAtLeast(0)
                val listState = rememberLazyListState(
                    initialFirstVisibleItemIndex = (selectedIndex - 2).coerceAtLeast(0),
                )
                LazyColumn(state = listState, modifier = Modifier.heightIn(max = 320.dp)) {
                    items(options) { opt ->
                        DropdownMenuItem(
                            text = {
                                Text(
                                    opt,
                                    fontWeight = if (opt == selected) FontWeight.Bold else FontWeight.Normal,
                                    color = if (opt == selected) BrandBlueDeep else Color.Unspecified,
                                )
                            },
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
}

private fun dateOptions(): List<String> {
    val fmt = DateTimeFormatter.ofPattern("M月d日(E)", Locale.JAPANESE)
    val today = LocalDate.now()
    return (0..30L).map { today.plusDays(it).format(fmt) }
}

private fun timeOptions(): List<String> {
    // 業務利用で使うのは 7:00〜23:00 程度なので、深夜帯は省いて選びやすくする
    val list = ArrayList<String>(65)
    for (h in 7..23) for (m in intArrayOf(0, 15, 30, 45)) {
        if (h == 23 && m > 0) break
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
