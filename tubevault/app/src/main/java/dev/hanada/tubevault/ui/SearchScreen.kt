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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.hanada.tubevault.core.SearchResult
import dev.hanada.tubevault.core.formatViewCount

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SearchScreen(
    viewModel: SearchViewModel,
    contentPadding: PaddingValues,
    onOpenBrowser: () -> Unit,
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
    val clipboard = LocalClipboardManager.current
    var pending by remember { mutableStateOf<SearchResult?>(null) }

    // A link shared from the YouTube app searches for itself on arrival.
    LaunchedEffect(sharedLink) {
        sharedLink?.let { viewModel.consumeSharedLink(it) }
    }

    fun runSearch() {
        keyboard?.hide()
        viewModel.search()
    }

    Column(modifier = modifier.fillMaxSize().padding(contentPadding)) {
        ScreenHeader(
            title = "検索",
            actions = {
                FilledTonalIconButton(onClick = onOpenBrowser) {
                    Icon(Icons.Default.Home, contentDescription = "YouTube を開く")
                }
            },
        )

        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedTextField(
                value = query,
                onValueChange = viewModel::onQueryChange,
                modifier = Modifier.weight(1f),
                label = { Text("キーワード / URL") },
                singleLine = true,
                shape = MaterialTheme.shapes.large,
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                trailingIcon = {
                    // Paste is the whole point of the URL flow — the URL was
                    // just copied in the browser tab, and asking the user to
                    // long-press the field for the system menu was the step
                    // where that flow was quietly falling apart.
                    if (query.isEmpty()) {
                        IconButton(
                            onClick = {
                                val text = clipboard.getText()?.text?.trim().orEmpty()
                                if (text.isNotEmpty()) {
                                    viewModel.onQueryChange(text)
                                    runSearch()
                                }
                            },
                        ) {
                            Icon(Icons.Default.ContentPaste, contentDescription = "貼り付けて検索")
                        }
                    } else {
                        IconButton(onClick = viewModel::clear) {
                            Icon(Icons.Default.Clear, contentDescription = "クリア")
                        }
                    }
                },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { runSearch() }),
            )
            // The keyboard's search key used to be the only way to run a
            // search, which is invisible to anyone who pasted instead of typed.
            FilledIconButton(
                onClick = { runSearch() },
                enabled = query.isNotBlank() && !isSearching,
                modifier = Modifier.padding(start = 8.dp).size(52.dp),
            ) {
                Icon(Icons.Default.Search, contentDescription = "検索")
            }
        }

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
                subtitle = "キーワードで検索するか、コピーした YouTube の URL を" +
                    "貼り付けアイコンから読み込んでください。\n" +
                    "YouTube アプリの共有メニューからも送れます。",
            )

            else -> LazyColumn(
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
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
            title = result.title,
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
    SurfaceCard(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onClick)
                .padding(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Thumbnail(
                model = result.thumbnailUrl,
                durationSec = result.durationSec,
                modifier = Modifier.width(116.dp).height(66.dp),
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
                        modifier = Modifier.padding(top = 2.dp),
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
                modifier = Modifier.padding(horizontal = 8.dp).size(24.dp),
            )
        }
    }
}
