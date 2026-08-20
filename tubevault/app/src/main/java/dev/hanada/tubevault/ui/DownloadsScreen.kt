package dev.hanada.tubevault.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.hanada.tubevault.core.formatDuration
import dev.hanada.tubevault.download.DownloadJob
import dev.hanada.tubevault.download.JobState

@Composable
fun DownloadsScreen(
    viewModel: DownloadsViewModel,
    contentPadding: PaddingValues,
    modifier: Modifier = Modifier,
) {
    val jobs by viewModel.jobs.collectAsStateWithLifecycle()

    Column(modifier = modifier.fillMaxSize().padding(contentPadding)) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("ダウンロード", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
            if (jobs.any { !it.isActive }) {
                TextButton(onClick = viewModel::clearFinished) { Text("履歴を消す") }
            }
        }

        if (jobs.isEmpty()) {
            EmptyState(
                icon = Icons.Default.Download,
                title = "ダウンロードはありません",
                subtitle = "検索タブから動画を選ぶと、ここに進捗が表示されます",
            )
        } else {
            LazyColumn(
                contentPadding = PaddingValues(bottom = 16.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                items(jobs, key = { it.id }) { job ->
                    DownloadRow(
                        job = job,
                        onCancel = { viewModel.cancel(job.id) },
                        onRetry = { viewModel.retry(job.id) },
                        onRemove = { viewModel.remove(job.id) },
                    )
                }
            }
        }
    }
}

@Composable
private fun DownloadRow(
    job: DownloadJob,
    onCancel: () -> Unit,
    onRetry: () -> Unit,
    onRemove: () -> Unit,
) {
    var showFullError by remember { mutableStateOf(false) }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .let {
                if (job.state == JobState.FAILED && !job.error.isNullOrBlank()) {
                    it.clickable { showFullError = true }
                } else {
                    it
                }
            }
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Thumbnail(
            model = job.thumbnailUrl,
            durationSec = job.durationSec,
            modifier = Modifier.width(96.dp).height(54.dp),
        )

        Column(modifier = Modifier.weight(1f).padding(start = 12.dp)) {
            Text(
                text = job.title,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )

            when (job.state) {
                JobState.RUNNING -> {
                    LinearProgressIndicator(
                        progress = { job.progress },
                        modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                    )
                    Text(
                        text = buildString {
                            append("${(job.progress * 100).toInt()}%")
                            if (job.etaSeconds > 0) append(" · 残り ${formatDuration(job.etaSeconds)}")
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }

                JobState.QUEUED -> StatusText("待機中", MaterialTheme.colorScheme.onSurfaceVariant)
                JobState.COMPLETED -> StatusText("完了 · ${job.kind.label}", MaterialTheme.colorScheme.primary)
                JobState.CANCELED -> StatusText("キャンセル済み", MaterialTheme.colorScheme.onSurfaceVariant)
                JobState.FAILED -> Column {
                    StatusText(job.error ?: "失敗しました", MaterialTheme.colorScheme.error)
                    if (!job.error.isNullOrBlank()) {
                        Text(
                            text = "タップして全文を表示",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }

        Box(modifier = Modifier.padding(start = 4.dp)) {
            when (job.state) {
                JobState.QUEUED, JobState.RUNNING -> IconButton(onClick = onCancel) {
                    Icon(Icons.Default.Close, contentDescription = "キャンセル")
                }

                JobState.FAILED, JobState.CANCELED -> IconButton(onClick = onRetry) {
                    Icon(Icons.Default.Refresh, contentDescription = "再試行")
                }

                JobState.COMPLETED -> IconButton(onClick = onRemove) {
                    Icon(Icons.Default.Close, contentDescription = "一覧から消す")
                }
            }
        }
    }

    if (showFullError) {
        FullErrorDialog(
            title = job.title,
            error = job.error.orEmpty(),
            onDismiss = { showFullError = false },
        )
    }
}

@Composable
private fun FullErrorDialog(title: String, error: String, onDismiss: () -> Unit) {
    val clipboard = LocalClipboardManager.current

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title, maxLines = 2, overflow = TextOverflow.Ellipsis) },
        text = {
            SelectionContainer {
                Text(
                    text = error,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier
                        .verticalScroll(rememberScrollState())
                        .heightIn(max = 320.dp),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { clipboard.setText(AnnotatedString(error)) }) {
                Icon(Icons.Default.ContentCopy, contentDescription = null, modifier = Modifier.size(18.dp))
                Text("コピー", modifier = Modifier.padding(start = 4.dp))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("閉じる") }
        },
    )
}

@Composable
private fun StatusText(text: String, color: androidx.compose.ui.graphics.Color) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = color,
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.padding(top = 4.dp),
    )
}
