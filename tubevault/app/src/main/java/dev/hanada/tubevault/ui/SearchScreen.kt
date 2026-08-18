package dev.hanada.tubevault.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Audiotrack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.hanada.tubevault.core.MediaKind
import dev.hanada.tubevault.core.SearchResult
import dev.hanada.tubevault.core.VideoQuality
import dev.hanada.tubevault.core.formatViewCount
import dev.hanada.tubevault.data.CategoryEntity

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SearchScreen(
    viewModel: SearchViewModel,
    contentPadding: PaddingValues,
    modifier: Modifier = Modifier,
) {
    val query by viewModel.query.collectAsStateWithLifecycle()
    val results by viewModel.results.collectAsStateWithLifecycle()
    val isSearching by viewModel.isSearching.collectAsStateWithLifecycle()
    val error by viewModel.error.collectAsStateWithLifecycle()
    val hasSearched by viewModel.hasSearched.collectAsStateWithLifecycle()
    val categories by viewModel.categories.collectAsStateWithLifecycle()
    val downloadedKeys by viewModel.downloadedKeys.collectAsStateWithLifecycle()
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val sharedLink by viewModel.sharedLink.collectAsStateWithLifecycle()

    val keyboard = LocalSoftwareKeyboardController.current
    var pending by remember { mutableStateOf<SearchResult?>(null) }

    // A link shared from the YouTube app searches for itself on arrival.
    LaunchedEffect(sharedLink) {
        sharedLink?.let { viewModel.consumeSharedLink(it) }
    }

    Column(modifier = modifier.fillMaxSize().padding(contentPadding)) {
        OutlinedTextField(
            value = query,
            onValueChange = viewModel::onQueryChange,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            label = { Text("YouTube を検索 / URL を貼り付け") },
            singleLine = true,
            leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
            trailingIcon = {
                if (query.isNotEmpty()) {
                    IconButton(onClick = viewModel::clear) {
                        Icon(Icons.Default.Clear, contentDescription = "クリア")
                    }
                }
            },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(
                onSearch = {
                    keyboard?.hide()
                    viewModel.search()
                },
            ),
        )

        when {
            isSearching -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }

            error != null -> EmptyState(
                icon = Icons.Default.Search,
                title = "検索できませんでした",
                subtitle = error ?: "",
            )

            results.isEmpty() && hasSearched -> EmptyState(
                icon = Icons.Default.Search,
                title = "結果が見つかりません",
                subtitle = "キーワードを変えて試してください",
            )

            results.isEmpty() -> EmptyState(
                icon = Icons.Default.Search,
                title = "動画を検索",
                subtitle = "キーワードで検索するか、YouTube の URL を直接貼り付けてください。\nYouTube アプリの共有メニューからも送れます。",
            )

            else -> LazyColumn(
                contentPadding = PaddingValues(bottom = 16.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                items(results, key = { it.videoId }) { result ->
                    SearchResultRow(
                        result = result,
                        alreadySaved = downloadedKeys.any { it.startsWith("${result.videoId}:") },
                        onClick = { pending = result },
                    )
                }
            }
        }
    }

    pending?.let { result ->
        DownloadOptionsDialog(
            result = result,
            categories = categories,
            initialKind = settings.defaultKind,
            initialQuality = settings.defaultQuality,
            initialCategoryId = settings.defaultCategoryId,
            onDismiss = { pending = null },
            onConfirm = { kind, quality, categoryId ->
                viewModel.download(result, kind, quality, categoryId)
                viewModel.rememberDefaults(kind, quality, categoryId)
                pending = null
            },
        )
    }
}

@Composable
private fun SearchResultRow(
    result: SearchResult,
    alreadySaved: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Thumbnail(
            model = result.thumbnailUrl,
            durationSec = result.durationSec,
            modifier = Modifier.width(120.dp).height(68.dp),
        )
        Column(modifier = Modifier.weight(1f).padding(start = 12.dp)) {
            Text(
                text = result.title,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            val meta = listOfNotNull(
                result.uploader,
                formatViewCount(result.viewCount).ifBlank { null },
            ).joinToString(" · ")
            if (meta.isNotEmpty()) {
                Text(
                    text = meta,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Icon(
            imageVector = if (alreadySaved) Icons.Default.CheckCircle else Icons.Default.Download,
            contentDescription = if (alreadySaved) "取得済み" else "ダウンロード",
            tint = if (alreadySaved) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
            modifier = Modifier.size(24.dp),
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DownloadOptionsDialog(
    result: SearchResult,
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
        title = { Text(result.title, maxLines = 2, overflow = TextOverflow.Ellipsis) },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                SectionLabel("形式")
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
                    SectionLabel("画質")
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

                SectionLabel("保存先フォルダ")
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
private fun SectionLabel(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 12.dp, bottom = 6.dp),
    )
}
