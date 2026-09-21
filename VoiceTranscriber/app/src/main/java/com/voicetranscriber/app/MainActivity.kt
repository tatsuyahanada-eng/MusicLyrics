package com.voicetranscriber.app

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.gestures.detectTapGestures
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.voicetranscriber.app.ui.theme.VoiceTranscriberTheme
import kotlinx.coroutines.launch
import java.io.OutputStreamWriter

// ---------------------------------------------------------------------------
// ブランドカラー：新しいアイコン（フェニックス・サンズ配色）に合わせた
//   ディープパープル × サンオレンジ。
// TemplateScreens.kt からも参照するため private にしない。
// ---------------------------------------------------------------------------
internal val BrandBlue = Color(0xFF35107A)       // ディープパープル（アイコン背景）
internal val BrandBlueDeep = Color(0xFF230A56)   // さらに濃いパープル
internal val BrandCyan = Color(0xFFFFC940)       // ライトゴールド（差し色・強調）
internal val BrandMagenta = Color(0xFFE0862A)    // サンオレンジ寄りの赤み（メール用グラデ始点）
internal val BrandRed = Color(0xFFFF9E1F)        // サンオレンジ（メール送信のアクセント）
internal val BrandRedDeep = Color(0xFFE07A00)    // ディープオレンジ

internal val BlueGradient = Brush.horizontalGradient(listOf(BrandBlue, BrandBlueDeep))
internal val MailGradient = Brush.horizontalGradient(listOf(BrandMagenta, BrandRed))

// 音声ボタン：ブランドのパープル×オレンジでまとめつつ、ボタンごとにはっきり区別する。
private val HoldButtonColor = Color(0xFF3A1680)        // 押す＝ディープパープル
private val HoldTextColor = Color(0xFFFFC940)          //   文字＝サンオレンジのゴールド（はっきり）
private val ContinuousButtonColor = Color(0xFF7A3D0A)  // 連続＝ディープオレンジブラウン
private val ContinuousTextColor = Color(0xFFFFD98F)    //   文字＝ライトゴールド（はっきり）
private val StopButtonColor = Color(0xFFFF9E1F)        // 録音中／停止＝サンオレンジ（目立つ）
private val StopTextColor = Color(0xFF2A0A5E)          //   文字＝ディープパープル

// 文字起こし吹き出しの優しいパステル配色（ライト/ダークで切替え・文字は読みやすく）
private val HoldBubbleBgLight = Color(0xFFEAE0FA)
private val HoldBubbleFgLight = Color(0xFF3A1680)
private val HoldBubbleBgDark = Color(0xFF3A2668)
private val HoldBubbleFgDark = Color(0xFFE8DFFF)
private val ContBubbleBgLight = Color(0xFFFFEAD1)
private val ContBubbleFgLight = Color(0xFF8A4A0C)
private val ContBubbleBgDark = Color(0xFF4E3113)
private val ContBubbleFgDark = Color(0xFFFFDFAE)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            VoiceTranscriberTheme {
                AppRoot()
            }
        }
    }
}

/**
 * 下部メニューの構成。
 *   定型文（コピペ・既定）／ メール送信 ／ 音声入力
 * 設定は右上の⚙から開く（全タブ共通）。
 */
private enum class AppScreen { TEMPLATES, MAIL, VOICE, SETTINGS }

/** タブごとのアクセント色。定型文・音声入力は青、メールは赤（アイコンの配色に合わせる）。 */
private fun accentOf(screen: AppScreen): Color =
    if (screen == AppScreen.MAIL) BrandRed else BrandBlue

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AppRoot() {
    val snackbarHostState = remember { SnackbarHostState() }
    // 定型文をデフォルト表示にする
    var screen by remember { mutableStateOf(AppScreen.TEMPLATES) }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            CenterAlignedTopAppBar(
                navigationIcon = {
                    if (screen == AppScreen.SETTINGS) {
                        IconButton(onClick = { screen = AppScreen.TEMPLATES }) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "戻る")
                        }
                    } else {
                        Spacer(Modifier.width(4.dp))
                    }
                },
                // アイコンはどの画面でも常に表示する
                title = { BrandTitle(showSubtitle = screen == AppScreen.SETTINGS) },
                actions = {
                    if (screen != AppScreen.SETTINGS) {
                        IconButton(onClick = { screen = AppScreen.SETTINGS }) {
                            Icon(
                                Icons.Filled.Settings,
                                contentDescription = "設定",
                                tint = accentOf(screen),
                            )
                        }
                    }
                },
                colors = TopAppBarDefaults.centerAlignedTopAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                ),
            )
        },
        bottomBar = {
            if (screen != AppScreen.SETTINGS) {
                Column {
                    // ブランドカラーの細いグラデーションラインでメニューを区切る
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(3.dp)
                            .background(
                                Brush.horizontalGradient(
                                    listOf(BrandBlue, BrandBlueDeep, BrandMagenta, BrandRed),
                                ),
                            ),
                    )
                    NavigationBar(containerColor = MaterialTheme.colorScheme.surface) {
                        // 定型文を左（既定）、メール送信、音声入力の順に配置
                        NavigationBarItem(
                            selected = screen == AppScreen.TEMPLATES,
                            onClick = { screen = AppScreen.TEMPLATES },
                            icon = { Icon(Icons.Filled.ContentCopy, contentDescription = null) },
                            label = { Text("定型文") },
                            colors = accentNavColors(BrandBlue),
                        )
                        NavigationBarItem(
                            selected = screen == AppScreen.MAIL,
                            onClick = { screen = AppScreen.MAIL },
                            icon = { Icon(Icons.Filled.Email, contentDescription = null) },
                            label = { Text("メール") },
                            colors = accentNavColors(BrandRed),
                        )
                        NavigationBarItem(
                            selected = screen == AppScreen.VOICE,
                            onClick = { screen = AppScreen.VOICE },
                            icon = { Icon(Icons.Filled.Mic, contentDescription = null) },
                            label = { Text("音声入力") },
                            colors = accentNavColors(BrandBlue),
                        )
                    }
                }
            }
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { innerPadding ->
        when (screen) {
            AppScreen.TEMPLATES -> TemplatesPane(innerPadding, snackbarHostState)
            AppScreen.MAIL -> MailPane(innerPadding, snackbarHostState)
            AppScreen.VOICE -> VoicePane(innerPadding, snackbarHostState)
            AppScreen.SETTINGS -> TemplateSettingsPane(innerPadding)
        }
    }
}

@Composable
private fun accentNavColors(accent: Color) =
    androidx.compose.material3.NavigationBarItemDefaults.colors(
        selectedIconColor = Color.White,
        indicatorColor = accent,
        selectedTextColor = accent,
        unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
        unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant,
    )

/**
 * アプリのロゴ的タイトル（アイコン＋2トーンのワードマーク）。
 * どの画面でも常にこのバッジを表示する。設定画面では下にサブタイトルも添える。
 */
@Composable
private fun BrandTitle(showSubtitle: Boolean = false) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        // タイトルバーのバッジ用に、背景＋前景を合成した通常のラスター画像を使う。
        // R.mipmap.ic_launcher_round は API26+ では mipmap-anydpi-v26 の
        // <adaptive-icon> XML に解決され、painterResource() は <vector> しか
        // 読めないため起動直後にクラッシュしていた。専用の drawable に分離する。
        Image(
            painter = painterResource(R.drawable.app_badge),
            contentDescription = null,
            contentScale = ContentScale.Fit,
            modifier = Modifier.size(34.dp),
        )
        Spacer(Modifier.width(8.dp))
        Column {
            val nameColor = MaterialTheme.colorScheme.onSurface
            Text(
                buildAnnotatedString {
                    withStyle(SpanStyle(color = nameColor)) { append("Voice") }
                    withStyle(SpanStyle(color = BrandBlueDeep)) { append(" & Copy") }
                    withStyle(SpanStyle(color = BrandRed)) { append(" Paste") }
                },
                fontWeight = FontWeight.SemiBold,
                fontSize = 18.sp,
                letterSpacing = 0.3.sp,
            )
            if (showSubtitle) {
                Text(
                    "定型文の設定",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun VoicePane(
    innerPadding: PaddingValues,
    snackbarHostState: SnackbarHostState,
    viewModel: TranscriptionViewModel = viewModel(),
) {
    val context = LocalContext.current
    val state by viewModel.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()

    var hasMicPermission by remember {
        mutableStateOf(
            context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED
        )
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        hasMicPermission = granted
        if (!granted) {
            scope.launch { snackbarHostState.showSnackbar("マイクの権限が必要です") }
        }
    }

    // Save-to-file launcher (Storage Access Framework — no storage permission needed).
    val saveLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("text/plain")
    ) { uri: Uri? ->
        if (uri != null) {
            val ok = writeTextToUri(context, uri, fullText(state))
            scope.launch {
                snackbarHostState.showSnackbar(if (ok) "保存しました" else "保存に失敗しました")
            }
        }
    }

    // Surface recognizer errors as snackbars.
    LaunchedEffect(state.error) {
        state.error?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.dismissError()
        }
    }

    // ----- 自動アップデート確認 -----
    var updateInfo by remember { mutableStateOf<UpdateManager.UpdateInfo?>(null) }
    var isDownloading by remember { mutableStateOf(false) }
    val updateUrl = stringResource(R.string.update_manifest_url)
    LaunchedEffect(Unit) {
        updateInfo = UpdateManager.checkForUpdate(context, updateUrl)
    }

    updateInfo?.let { info ->
        AlertDialog(
            onDismissRequest = { if (!isDownloading) updateInfo = null },
            title = { Text("アップデートがあります") },
            text = {
                Column {
                    Text("新しいバージョン ${info.versionName} が利用可能です。")
                    if (info.notes.isNotBlank()) {
                        Spacer(Modifier.height(8.dp))
                        Text(info.notes, style = MaterialTheme.typography.bodyMedium)
                    }
                    if (isDownloading) {
                        Spacer(Modifier.height(16.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(modifier = Modifier.size(20.dp))
                            Spacer(Modifier.width(12.dp))
                            Text("ダウンロード中…")
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(
                    enabled = !isDownloading,
                    onClick = {
                        scope.launch {
                            isDownloading = true
                            val file = UpdateManager.downloadApk(context, info.apkUrl)
                            isDownloading = false
                            if (file != null) {
                                UpdateManager.installApk(context, file)
                                updateInfo = null
                            } else {
                                snackbarHostState.showSnackbar("ダウンロードに失敗しました")
                            }
                        }
                    },
                ) { Text("アップデート") }
            },
            dismissButton = {
                TextButton(
                    enabled = !isDownloading,
                    onClick = { updateInfo = null },
                ) { Text("後で") }
            },
        )
    }

    fun requireMicThen(action: () -> Unit) {
        if (hasMicPermission) {
            action()
        } else {
            permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    Content(
        innerPadding = innerPadding,
        state = state,
        recognitionAvailable = viewModel.isRecognitionAvailable,
        onHoldStart = { requireMicThen { viewModel.startHold() } },
        onHoldStop = { viewModel.stopHold() },
        onToggleContinuous = { requireMicThen { viewModel.toggleContinuous() } },
        onTranscriptChange = viewModel::updateTranscript,
        onClear = viewModel::clearTranscript,
        onCopy = {
            copyToClipboard(context, fullText(state))
            scope.launch { snackbarHostState.showSnackbar("コピーしました") }
        },
        onSave = {
            saveLauncher.launch("transcription_${System.currentTimeMillis()}.txt")
        },
    )
}

@Composable
private fun Content(
    innerPadding: PaddingValues,
    state: TranscriptionState,
    recognitionAvailable: Boolean,
    onHoldStart: () -> Unit,
    onHoldStop: () -> Unit,
    onToggleContinuous: () -> Unit,
    onTranscriptChange: (String) -> Unit,
    onClear: () -> Unit,
    onCopy: () -> Unit,
    onSave: () -> Unit,
) {
    var isEditing by remember { mutableStateOf(false) }
    var showClearConfirm by remember { mutableStateOf(false) }
    val focusRequester = remember { FocusRequester() }
    val bgBrush = Brush.verticalGradient(
        colors = listOf(
            BrandBlue.copy(alpha = 0.10f),
            MaterialTheme.colorScheme.background,
        ),
    )
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(bgBrush)
            .padding(innerPadding)
            .padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        if (!recognitionAvailable) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.errorContainer,
                ),
            ) {
                Text(
                    "この端末では音声認識を利用できません。Google アプリ / 音声認識サービスを有効にしてください。",
                    modifier = Modifier.padding(12.dp),
                    color = MaterialTheme.colorScheme.onErrorContainer,
                )
            }
            Spacer(Modifier.height(12.dp))
        }

        StatusBanner(state)
        Spacer(Modifier.height(12.dp))

        ModeButtons(
            state = state,
            enabled = recognitionAvailable,
            onHoldStart = onHoldStart,
            onHoldStop = onHoldStop,
            onToggleContinuous = onToggleContinuous,
        )

        Spacer(Modifier.height(16.dp))

        // 見出し行 + 小さな編集トグル
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(width = 4.dp, height = 18.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(BlueGradient),
            )
            Spacer(Modifier.width(8.dp))
            Text(
                "Result",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
            )
            Spacer(Modifier.weight(1f))
            TextButton(
                onClick = { isEditing = !isEditing },
                enabled = isEditing || state.transcript.isNotBlank(),
            ) {
                Icon(
                    if (isEditing) Icons.Filled.Check else Icons.Filled.Edit,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.width(4.dp))
                Text(if (isEditing) "完了" else "編集")
            }
        }
        Spacer(Modifier.height(8.dp))

        // 表示領域（残りの高さいっぱいに広げる）
        if (isEditing) {
            OutlinedTextField(
                value = state.transcript,
                onValueChange = onTranscriptChange,
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .focusRequester(focusRequester),
                textStyle = MaterialTheme.typography.bodyLarge.copy(
                    fontSize = 26.sp,
                    lineHeight = 38.sp,
                ),
                shape = RoundedCornerShape(16.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedContainerColor = MaterialTheme.colorScheme.surface,
                    unfocusedContainerColor = MaterialTheme.colorScheme.surface,
                ),
            )
            LaunchedEffect(Unit) { focusRequester.requestFocus() }
        } else {
            TranscriptDisplay(
                segments = state.segments,
                partial = state.partial,
                partialMode = state.mode,
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
            )
        }

        Spacer(Modifier.height(12.dp))
        ExportButtons(
            hasText = state.transcript.isNotBlank(),
            onCopy = onCopy,
            onSave = onSave,
            onClear = { showClearConfirm = true },
        )
    }

    if (showClearConfirm) {
        AlertDialog(
            onDismissRequest = { showClearConfirm = false },
            title = { Text("文字起こしを消去") },
            text = { Text("表示中の文字起こし内容を消去します。よろしいですか？") },
            confirmButton = {
                TextButton(
                    onClick = {
                        onClear()
                        isEditing = false
                        showClearConfirm = false
                    },
                ) { Text("消去する") }
            },
            dismissButton = {
                TextButton(onClick = { showClearConfirm = false }) { Text("キャンセル") }
            },
        )
    }
}

/**
 * キーボードを出さずに文字起こし結果を表示する読み取り専用の枠。
 * LINE のように、どのモードで起こしたかで左右に分けて色付き吹き出しで表示する。
 *  - 押している間 … 左寄せ・インディゴ地にゴールド文字
 *  - 連続で      … 右寄せ・グリーン地にミント文字
 */
@Composable
private fun TranscriptDisplay(
    segments: List<TranscriptSegment>,
    partial: String,
    partialMode: Mode,
    modifier: Modifier = Modifier,
) {
    val scroll = rememberScrollState()
    // 新しい吹き出しが追加されたら自動で最下部へスクロール
    LaunchedEffect(segments.size, partial) {
        scroll.animateScrollTo(scroll.maxValue)
    }
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.surface)
            .border(
                width = 1.dp,
                color = MaterialTheme.colorScheme.outline,
                shape = RoundedCornerShape(16.dp),
            ),
    ) {
        if (segments.isEmpty() && partial.isBlank()) {
            Text(
                "ここに文字起こしされた内容が表示されます。\nマイクボタンで話しかけてください。",
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.45f),
                fontSize = 20.sp,
                lineHeight = 30.sp,
                modifier = Modifier.padding(16.dp),
            )
        } else {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(scroll)
                    .padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                segments.forEach { seg ->
                    if (seg.text.isNotBlank()) {
                        TranscriptBubble(seg.text, seg.mode, alpha = 1f)
                    }
                }
                if (partial.isNotBlank()) {
                    TranscriptBubble(partial, partialMode, alpha = 0.55f)
                }
            }
        }
    }
}

/** 1 つの吹き出し。mode に応じて色と左右の寄せを変える。 */
@Composable
private fun TranscriptBubble(text: String, mode: Mode, alpha: Float) {
    val dark = isSystemInDarkTheme()
    val (bg, fg, alignStart) = when (mode) {
        Mode.HOLD -> Triple(
            if (dark) HoldBubbleBgDark else HoldBubbleBgLight,
            if (dark) HoldBubbleFgDark else HoldBubbleFgLight,
            true,
        )
        Mode.CONTINUOUS -> Triple(
            if (dark) ContBubbleBgDark else ContBubbleBgLight,
            if (dark) ContBubbleFgDark else ContBubbleFgLight,
            false,
        )
        else -> Triple(
            MaterialTheme.colorScheme.surfaceVariant,
            MaterialTheme.colorScheme.onSurface,
            true,
        )
    }
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (alignStart) Arrangement.Start else Arrangement.End,
    ) {
        Box(
            modifier = Modifier
                .widthIn(max = 300.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(bg.copy(alpha = alpha))
                .padding(horizontal = 14.dp, vertical = 10.dp),
        ) {
            Text(
                text,
                color = fg,
                fontSize = 22.sp,
                lineHeight = 30.sp,
            )
        }
    }
}

@Composable
private fun StatusBanner(state: TranscriptionState) {
    val (label, color) = when {
        state.isListening && state.mode == Mode.CONTINUOUS ->
            "● 連続文字起こし中…" to MaterialTheme.colorScheme.secondary
        state.isListening && state.mode == Mode.HOLD ->
            "● 録音中（ボタンを離すと停止）" to BrandBlueDeep
        state.mode == Mode.CONTINUOUS ->
            "連続モード起動中…" to MaterialTheme.colorScheme.secondary
        else -> "待機中" to MaterialTheme.colorScheme.onSurfaceVariant
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier
                .size(width = 4.dp, height = 16.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(if (state.isListening) BrandCyan else color.copy(alpha = 0.4f)),
        )
        Spacer(Modifier.width(8.dp))
        Text(label, color = color, fontWeight = FontWeight.Bold, fontSize = 16.sp)
    }
}

@Composable
private fun ModeButtons(
    state: TranscriptionState,
    enabled: Boolean,
    onHoldStart: () -> Unit,
    onHoldStop: () -> Unit,
    onToggleContinuous: () -> Unit,
) {
    val continuousActive = state.mode == Mode.CONTINUOUS
    val holdInteractive = enabled && state.mode != Mode.CONTINUOUS
    val holdColor = if (state.mode == Mode.HOLD) StopButtonColor else HoldButtonColor
    val holdTextColor = if (state.mode == Mode.HOLD) StopTextColor else HoldTextColor
    val continuousTextColor = if (continuousActive) StopTextColor else ContinuousTextColor

    // 左右に並べる：左＝押している間だけ / 右＝連続
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // 左：Hold-to-talk。Button ではなく Box にして、子の clickable が
        // 押下イベントを横取りしないようにする（onPress → tryAwaitRelease）。
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .weight(1f)
                .height(104.dp)
                .clip(RoundedCornerShape(20.dp))
                .background(if (holdInteractive) holdColor else holdColor.copy(alpha = 0.4f))
                .pointerInput(holdInteractive) {
                    if (!holdInteractive) return@pointerInput
                    detectTapGestures(
                        onPress = {
                            onHoldStart()
                            tryAwaitRelease()
                            onHoldStop()
                        },
                    )
                },
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(
                    Icons.Filled.Mic,
                    contentDescription = null,
                    tint = holdTextColor,
                    modifier = Modifier.size(26.dp),
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    if (state.mode == Mode.HOLD) "録音中…\n離すと停止" else "押している間\n文字起こし",
                    color = holdTextColor,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center,
                    lineHeight = 18.sp,
                )
            }
        }

        // 右：連続文字起こし（タップでトグル）
        Button(
            onClick = onToggleContinuous,
            enabled = enabled && state.mode != Mode.HOLD,
            shape = RoundedCornerShape(20.dp),
            contentPadding = PaddingValues(8.dp),
            modifier = Modifier
                .weight(1f)
                .height(104.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = if (continuousActive) StopButtonColor else ContinuousButtonColor,
                contentColor = continuousTextColor,
            ),
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(Icons.Filled.Mic, contentDescription = null, modifier = Modifier.size(26.dp))
                Spacer(Modifier.height(6.dp))
                Text(
                    if (continuousActive) "連続停止" else "連続で\n文字起こし",
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center,
                    lineHeight = 18.sp,
                )
            }
        }
    }
}

@Composable
private fun ExportButtons(
    hasText: Boolean,
    onCopy: () -> Unit,
    onSave: () -> Unit,
    onClear: () -> Unit,
) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(
            onClick = onCopy,
            enabled = hasText,
            shape = RoundedCornerShape(14.dp),
            modifier = Modifier.weight(1f),
            colors = ButtonDefaults.outlinedButtonColors(contentColor = BrandBlueDeep),
        ) {
            Icon(Icons.Filled.ContentCopy, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(4.dp))
            Text("コピー")
        }
        OutlinedButton(
            onClick = onSave,
            enabled = hasText,
            shape = RoundedCornerShape(14.dp),
            modifier = Modifier.weight(1f),
            colors = ButtonDefaults.outlinedButtonColors(contentColor = BrandBlueDeep),
        ) {
            Icon(Icons.Filled.Save, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(4.dp))
            Text("保存")
        }
        OutlinedButton(
            onClick = onClear,
            enabled = hasText,
            shape = RoundedCornerShape(14.dp),
            modifier = Modifier.weight(1f),
            colors = ButtonDefaults.outlinedButtonColors(
                contentColor = MaterialTheme.colorScheme.error,
            ),
        ) {
            Icon(Icons.Filled.Delete, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(4.dp))
            Text("クリア")
        }
    }
}

// ----- Plain helpers --------------------------------------------------------

private fun fullText(state: TranscriptionState): String = state.transcript

private fun copyToClipboard(context: Context, text: String) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    clipboard.setPrimaryClip(ClipData.newPlainText("文字起こし", text))
}

private fun writeTextToUri(context: Context, uri: Uri, text: String): Boolean = try {
    context.contentResolver.openOutputStream(uri)?.use { out ->
        OutputStreamWriter(out, Charsets.UTF_8).use { it.write(text) }
    }
    true
} catch (e: Exception) {
    false
}
