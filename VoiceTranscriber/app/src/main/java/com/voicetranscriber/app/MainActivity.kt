package com.voicetranscriber.app

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Save
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.voicetranscriber.app.ui.theme.VoiceTranscriberTheme
import kotlinx.coroutines.launch
import java.io.OutputStreamWriter

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            VoiceTranscriberTheme {
                TranscriberScreen()
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TranscriberScreen(viewModel: TranscriptionViewModel = viewModel()) {
    val context = LocalContext.current
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
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

    fun requireMicThen(action: () -> Unit) {
        if (hasMicPermission) {
            action()
        } else {
            permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("音声文字起こし") },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    titleContentColor = Color.White,
                ),
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { innerPadding ->
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
            onEmail = { sendEmail(context, fullText(state)) },
        )
    }
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
    onEmail: () -> Unit,
) {
    val scroll = rememberScrollState()
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(innerPadding)
            .verticalScroll(scroll)
            .padding(16.dp),
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
        Spacer(Modifier.height(16.dp))

        ModeButtons(
            state = state,
            enabled = recognitionAvailable,
            onHoldStart = onHoldStart,
            onHoldStop = onHoldStop,
            onToggleContinuous = onToggleContinuous,
        )

        Spacer(Modifier.height(20.dp))

        Text(
            "文字起こし結果",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
        )
        Spacer(Modifier.height(8.dp))

        OutlinedTextField(
            value = state.transcript,
            onValueChange = onTranscriptChange,
            modifier = Modifier
                .fillMaxWidth()
                .height(220.dp),
            placeholder = { Text("ここに文字起こしされたテキストが表示されます（編集も可能）") },
        )

        if (state.partial.isNotBlank()) {
            Spacer(Modifier.height(8.dp))
            Text(
                "認識中: ${state.partial}",
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.Medium,
            )
        }

        Spacer(Modifier.height(20.dp))
        ExportButtons(
            hasText = state.transcript.isNotBlank(),
            onCopy = onCopy,
            onSave = onSave,
            onEmail = onEmail,
            onClear = onClear,
        )
    }
}

@Composable
private fun StatusBanner(state: TranscriptionState) {
    val (label, color) = when {
        state.isListening && state.mode == Mode.CONTINUOUS ->
            "● 連続文字起こし中…" to MaterialTheme.colorScheme.secondary
        state.isListening && state.mode == Mode.HOLD ->
            "● 録音中（ボタンを離すと停止）" to MaterialTheme.colorScheme.primary
        state.mode == Mode.CONTINUOUS ->
            "連続モード起動中…" to MaterialTheme.colorScheme.secondary
        else -> "待機中" to MaterialTheme.colorScheme.onSurfaceVariant
    }
    Text(label, color = color, fontWeight = FontWeight.Bold, fontSize = 16.sp)
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
    // Hold-to-talk: a plain Box (not a Button) so its clickable does not consume
    // the press before our detectTapGestures sees it. onPress fires immediately,
    // tryAwaitRelease suspends until the finger lifts.
    val holdInteractive = enabled && state.mode != Mode.CONTINUOUS
    val holdColor = if (state.mode == Mode.HOLD)
        MaterialTheme.colorScheme.error
    else MaterialTheme.colorScheme.primary
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .fillMaxWidth()
            .height(96.dp)
            .clip(RoundedCornerShape(16.dp))
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
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                Icons.Filled.Mic,
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier.size(28.dp),
            )
            Spacer(Modifier.width(8.dp))
            Text(
                if (state.mode == Mode.HOLD) "録音中… 離すと停止" else "押している間だけ文字起こし",
                color = Color.White,
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold,
            )
        }
    }

    Spacer(Modifier.height(12.dp))

    Button(
        onClick = onToggleContinuous,
        enabled = enabled && state.mode != Mode.HOLD,
        modifier = Modifier
            .fillMaxWidth()
            .height(96.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = if (continuousActive)
                MaterialTheme.colorScheme.error
            else MaterialTheme.colorScheme.secondary,
        ),
    ) {
        Icon(Icons.Filled.Mic, contentDescription = null, modifier = Modifier.size(28.dp))
        Spacer(Modifier.width(8.dp))
        Text(
            if (continuousActive) "連続文字起こしを停止" else "連続文字起こしを開始",
            fontSize = 16.sp,
            fontWeight = FontWeight.Bold,
        )
    }
}

@Composable
private fun ExportButtons(
    hasText: Boolean,
    onCopy: () -> Unit,
    onSave: () -> Unit,
    onEmail: () -> Unit,
    onClear: () -> Unit,
) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(onClick = onCopy, enabled = hasText, modifier = Modifier.weight(1f)) {
            Icon(Icons.Filled.ContentCopy, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(4.dp))
            Text("コピー")
        }
        OutlinedButton(onClick = onSave, enabled = hasText, modifier = Modifier.weight(1f)) {
            Icon(Icons.Filled.Save, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(4.dp))
            Text("保存")
        }
    }
    Spacer(Modifier.height(8.dp))
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(onClick = onEmail, enabled = hasText, modifier = Modifier.weight(1f)) {
            Icon(Icons.Filled.Email, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(4.dp))
            Text("メール")
        }
        OutlinedButton(onClick = onClear, enabled = hasText, modifier = Modifier.weight(1f)) {
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

private fun sendEmail(context: Context, text: String) {
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_SUBJECT, "音声文字起こし")
        putExtra(Intent.EXTRA_TEXT, text)
    }
    context.startActivity(Intent.createChooser(intent, "メールで送信"))
}
