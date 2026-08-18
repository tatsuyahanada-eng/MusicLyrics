package dev.hanada.tubevault.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Audiotrack
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.hanada.tubevault.core.MediaKind
import dev.hanada.tubevault.core.VideoQuality
import dev.hanada.tubevault.data.CategoryEntity

/** Shared by the search results and the in-app browser. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun DownloadOptionsDialog(
    title: String,
    categories: List<CategoryEntity>,
    initialKind: MediaKind,
    initialQuality: VideoQuality,
    initialCategoryId: Long,
    onDismiss: () -> Unit,
    onConfirm: (MediaKind, VideoQuality, Long) -> Unit,
) {
    var kind by remember { mutableStateOf(initialKind) }
    var quality by remember { mutableStateOf(initialQuality) }
    var categoryId by remember {
        val valid = categories.any { it.id == initialCategoryId }
        mutableStateOf(if (valid) initialCategoryId else categories.firstOrNull()?.id ?: 0L)
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title, maxLines = 2, overflow = TextOverflow.Ellipsis) },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                DialogSectionLabel("形式")
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    MediaKind.entries.forEach { option ->
                        FilterChip(
                            selected = kind == option,
                            onClick = { kind = option },
                            label = { Text(option.label) },
                            leadingIcon = {
                                Icon(
                                    imageVector = if (option == MediaKind.AUDIO) {
                                        Icons.Default.Audiotrack
                                    } else {
                                        Icons.Default.Videocam
                                    },
                                    contentDescription = null,
                                    modifier = Modifier.size(18.dp),
                                )
                            },
                        )
                    }
                }

                if (kind == MediaKind.VIDEO) {
                    DialogSectionLabel("画質")
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        VideoQuality.entries.forEach { option ->
                            FilterChip(
                                selected = quality == option,
                                onClick = { quality = option },
                                label = { Text(option.label) },
                            )
                        }
                    }
                }

                DialogSectionLabel("保存先フォルダ")
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    categories.forEach { category ->
                        FilterChip(
                            selected = categoryId == category.id,
                            onClick = { categoryId = category.id },
                            label = { Text(category.name) },
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(kind, quality, categoryId) },
                enabled = categories.isNotEmpty(),
            ) {
                Text("ダウンロード")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("キャンセル") }
        },
    )
}

@Composable
internal fun DialogSectionLabel(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 12.dp, bottom = 6.dp),
    )
}
