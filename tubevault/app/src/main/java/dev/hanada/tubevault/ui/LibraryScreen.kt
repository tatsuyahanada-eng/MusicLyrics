package dev.hanada.tubevault.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Audiotrack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DriveFileMove
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SelectAll
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.hanada.tubevault.core.MediaKind
import dev.hanada.tubevault.core.formatBytes
import dev.hanada.tubevault.data.CategoryWithStats
import dev.hanada.tubevault.data.MediaItemEntity
import java.io.File

@Composable
fun LibraryScreen(
    viewModel: LibraryViewModel,
    contentPadding: PaddingValues,
    modifier: Modifier = Modifier,
) {
    val categories by viewModel.categories.collectAsStateWithLifecycle()
    val openCategory by viewModel.openCategory.collectAsStateWithLifecycle()
    val items by viewModel.items.collectAsStateWithLifecycle()

    BackHandler(enabled = openCategory != null) { viewModel.closeCategory() }

    Box(modifier = modifier.fillMaxSize().padding(contentPadding)) {
        val current = openCategory
        if (current == null) {
            CategoryGrid(
                categories = categories,
                onOpen = viewModel::open,
                onCreate = viewModel::createCategory,
                onRename = viewModel::renameCategory,
                onDelete = viewModel::deleteCategory,
            )
        } else {
            CategoryDetail(
                category = current,
                items = items,
                allCategories = categories,
                onBack = viewModel::closeCategory,
                onPlay = { index -> viewModel.play(items, index) },
                onShuffle = { viewModel.shuffle(items) },
                onMove = viewModel::moveItem,
                onDelete = viewModel::deleteItem,
                onMoveMany = viewModel::moveItems,
                onDeleteMany = viewModel::deleteItems,
            )
        }
    }
}

@Composable
private fun CategoryGrid(
    categories: List<CategoryWithStats>,
    onOpen: (Long) -> Unit,
    onCreate: (String) -> Unit,
    onRename: (Long, String) -> Unit,
    onDelete: (Long, Long?) -> Unit,
) {
    var showCreate by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf<CategoryWithStats?>(null) }
    var deleting by remember { mutableStateOf<CategoryWithStats?>(null) }

    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("フォルダ", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
            IconButton(onClick = { showCreate = true }) {
                Icon(Icons.Default.Add, contentDescription = "フォルダを追加")
            }
        }

        if (categories.isEmpty()) {
            EmptyState(
                icon = Icons.Default.Folder,
                title = "フォルダがありません",
                subtitle = "「+」から作成してください",
            )
        } else {
            LazyVerticalGrid(
                columns = GridCells.Fixed(2),
                contentPadding = PaddingValues(16.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                items(categories, key = { it.category.id }) { entry ->
                    CategoryCard(
                        entry = entry,
                        onClick = { onOpen(entry.category.id) },
                        onRename = { renaming = entry },
                        onDelete = { deleting = entry },
                    )
                }
            }
        }
    }

    if (showCreate) {
        TextPromptDialog(
            title = "フォルダを作成",
            label = "フォルダ名",
            initial = "",
            confirmLabel = "作成",
            onDismiss = { showCreate = false },
            onConfirm = {
                onCreate(it)
                showCreate = false
            },
        )
    }

    renaming?.let { entry ->
        TextPromptDialog(
            title = "フォルダ名を変更",
            label = "フォルダ名",
            initial = entry.category.name,
            confirmLabel = "変更",
            onDismiss = { renaming = null },
            onConfirm = {
                onRename(entry.category.id, it)
                renaming = null
            },
        )
    }

    deleting?.let { entry ->
        DeleteCategoryDialog(
            entry = entry,
            others = categories.filter { it.category.id != entry.category.id },
            onDismiss = { deleting = null },
            onConfirm = { moveTo ->
                onDelete(entry.category.id, moveTo)
                deleting = null
            },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CategoryCard(
    entry: CategoryWithStats,
    onClick: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }

    Card(
        onClick = onClick,
        colors = CardDefaults.cardColors(
            containerColor = Color(entry.category.colorArgb).copy(alpha = 0.15f),
        ),
        modifier = Modifier.aspectRatio(1.35f),
    ) {
        Column(modifier = Modifier.fillMaxSize().padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CategorySwatch(
                    colorArgb = entry.category.colorArgb,
                    modifier = Modifier.size(28.dp),
                ) {
                    Icon(
                        imageVector = Icons.Default.Folder,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(16.dp),
                    )
                }
                Box(modifier = Modifier.weight(1f))
                Box {
                    IconButton(onClick = { menuOpen = true }, modifier = Modifier.size(28.dp)) {
                        Icon(Icons.Default.MoreVert, contentDescription = "メニュー")
                    }
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        DropdownMenuItem(
                            text = { Text("名前を変更") },
                            leadingIcon = { Icon(Icons.Default.Edit, contentDescription = null) },
                            onClick = {
                                menuOpen = false
                                onRename()
                            },
                        )
                        DropdownMenuItem(
                            text = { Text("削除") },
                            leadingIcon = { Icon(Icons.Default.Delete, contentDescription = null) },
                            onClick = {
                                menuOpen = false
                                onDelete()
                            },
                        )
                    }
                }
            }

            Box(modifier = Modifier.weight(1f))

            Text(
                text = entry.category.name,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = "${entry.itemCount} 件 · ${formatBytes(entry.totalBytes)}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun CategoryDetail(
    category: CategoryWithStats,
    items: List<MediaItemEntity>,
    allCategories: List<CategoryWithStats>,
    onBack: () -> Unit,
    onPlay: (Int) -> Unit,
    onShuffle: () -> Unit,
    onMove: (Long, Long) -> Unit,
    onDelete: (Long) -> Unit,
    onMoveMany: (Set<Long>, Long) -> Unit,
    onDeleteMany: (Set<Long>) -> Unit,
) {
    var moving by remember { mutableStateOf<MediaItemEntity?>(null) }
    var deleting by remember { mutableStateOf<MediaItemEntity?>(null) }

    // Empty means "not selecting". Long-pressing a row starts a selection,
    // and clearing it drops straight back to normal browsing.
    var selection by remember(category.category.id) { mutableStateOf(emptySet<Long>()) }
    var movingSelection by remember { mutableStateOf(false) }
    var deletingSelection by remember { mutableStateOf(false) }

    val selecting = selection.isNotEmpty()

    // Selections must not outlive the rows they point at.
    val presentIds = items.map { it.id }.toSet()
    if (selecting && !presentIds.containsAll(selection)) {
        selection = selection intersect presentIds
    }

    BackHandler(enabled = selecting) { selection = emptySet() }

    Column(modifier = Modifier.fillMaxSize()) {
        if (selecting) {
            SelectionBar(
                count = selection.size,
                allSelected = selection.size == items.size,
                onClear = { selection = emptySet() },
                onSelectAll = { selection = presentIds },
                onMove = { movingSelection = true },
                onDelete = { deletingSelection = true },
            )
        } else {
            Row(
                modifier = Modifier.fillMaxWidth().padding(end = 12.dp, top = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "戻る")
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = category.category.name,
                        style = MaterialTheme.typography.titleLarge,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = "${category.itemCount} 件 · ${formatBytes(category.totalBytes)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (items.isNotEmpty()) {
                    FilledTonalIconButton(onClick = onShuffle) {
                        Icon(Icons.Default.Shuffle, contentDescription = "シャッフル再生")
                    }
                    Button(
                        onClick = { onPlay(0) },
                        modifier = Modifier.padding(start = 8.dp),
                    ) {
                        Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(18.dp))
                        Text("再生", modifier = Modifier.padding(start = 4.dp))
                    }
                }
            }
        }

        if (items.isEmpty()) {
            EmptyState(
                icon = Icons.Default.Folder,
                title = "まだ何もありません",
                subtitle = "検索タブから動画をダウンロードすると、ここに保存されます",
            )
        } else {
            LazyColumn(contentPadding = PaddingValues(bottom = 16.dp)) {
                itemsIndexed(items, key = { _, item -> item.id }) { index, item ->
                    MediaRow(
                        item = item,
                        selecting = selecting,
                        selected = item.id in selection,
                        onClick = {
                            if (selecting) {
                                selection = if (item.id in selection) {
                                    selection - item.id
                                } else {
                                    selection + item.id
                                }
                            } else {
                                onPlay(index)
                            }
                        },
                        onLongClick = { selection = selection + item.id },
                        onMove = { moving = item },
                        onDelete = { deleting = item },
                    )
                }
            }
        }
    }

    moving?.let { item ->
        MoveTargetDialog(
            title = "移動先を選択",
            categories = allCategories.filter { it.category.id != item.categoryId },
            onDismiss = { moving = null },
            onConfirm = { target ->
                onMove(item.id, target)
                moving = null
            },
        )
    }

    if (movingSelection) {
        MoveTargetDialog(
            title = "${selection.size} 件の移動先",
            categories = allCategories.filter { it.category.id != category.category.id },
            onDismiss = { movingSelection = false },
            onConfirm = { target ->
                onMoveMany(selection, target)
                movingSelection = false
                selection = emptySet()
            },
        )
    }

    if (deletingSelection) {
        AlertDialog(
            onDismissRequest = { deletingSelection = false },
            title = { Text("${selection.size} 件を削除しますか？") },
            text = { Text("選択した項目をファイルごと削除します。") },
            confirmButton = {
                TextButton(onClick = {
                    onDeleteMany(selection)
                    deletingSelection = false
                    selection = emptySet()
                }) { Text("削除") }
            },
            dismissButton = {
                TextButton(onClick = { deletingSelection = false }) { Text("キャンセル") }
            },
        )
    }

    deleting?.let { item ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text("削除しますか？") },
            text = { Text("「${item.title}」をファイルごと削除します。") },
            confirmButton = {
                TextButton(onClick = {
                    onDelete(item.id)
                    deleting = null
                }) { Text("削除") }
            },
            dismissButton = {
                TextButton(onClick = { deleting = null }) { Text("キャンセル") }
            },
        )
    }
}

/** Replaces the header while rows are selected. */
@Composable
private fun SelectionBar(
    count: Int,
    allSelected: Boolean,
    onClear: () -> Unit,
    onSelectAll: () -> Unit,
    onMove: () -> Unit,
    onDelete: () -> Unit,
) {
    Surface(color = MaterialTheme.colorScheme.primaryContainer) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(end = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onClear) {
                Icon(Icons.Default.Close, contentDescription = "選択を解除")
            }
            Text(
                text = "$count 件を選択",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = onSelectAll, enabled = !allSelected) {
                Icon(Icons.Default.SelectAll, contentDescription = "すべて選択")
            }
            IconButton(onClick = onMove) {
                Icon(Icons.Default.DriveFileMove, contentDescription = "まとめて移動")
            }
            IconButton(onClick = onDelete) {
                Icon(Icons.Default.Delete, contentDescription = "まとめて削除")
            }
        }
    }
}

@Composable
@OptIn(ExperimentalFoundationApi::class)
private fun MediaRow(
    item: MediaItemEntity,
    selecting: Boolean,
    selected: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onMove: () -> Unit,
    onDelete: () -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                if (selected) MaterialTheme.colorScheme.primaryContainer else Color.Transparent,
            )
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (selecting) {
            Checkbox(
                checked = selected,
                onCheckedChange = { onClick() },
                modifier = Modifier.padding(end = 4.dp),
            )
        }
        Thumbnail(
            model = item.thumbPath?.let { File(it) },
            durationSec = item.durationSec,
            fallbackIcon = if (item.mediaKind == MediaKind.AUDIO) {
                Icons.Default.Audiotrack
            } else {
                Icons.Default.Videocam
            },
            modifier = Modifier.width(104.dp).height(60.dp),
        )
        Column(modifier = Modifier.weight(1f).padding(start = 12.dp)) {
            Text(
                text = item.title,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (item.lastPlayedAt == null) {
                    NewBadge(modifier = Modifier.padding(end = 6.dp))
                }
                Text(
                    text = listOfNotNull(
                        item.mediaKind.label,
                        formatBytes(item.fileSizeBytes),
                        item.uploader,
                    ).joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        // The per-row menu would just get in the way of tap-to-toggle.
        if (!selecting) {
            Box {
                IconButton(onClick = { menuOpen = true }) {
                    Icon(Icons.Default.MoreVert, contentDescription = "メニュー")
                }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    DropdownMenuItem(
                        text = { Text("フォルダを移動") },
                        leadingIcon = { Icon(Icons.Default.DriveFileMove, contentDescription = null) },
                        onClick = {
                            menuOpen = false
                            onMove()
                        },
                    )
                    DropdownMenuItem(
                        text = { Text("削除") },
                        leadingIcon = { Icon(Icons.Default.Delete, contentDescription = null) },
                        onClick = {
                            menuOpen = false
                            onDelete()
                        },
                    )
                    DropdownMenuItem(
                        text = { Text("選択") },
                        leadingIcon = { Icon(Icons.Default.SelectAll, contentDescription = null) },
                        onClick = {
                            menuOpen = false
                            onLongClick()
                        },
                    )
                }
            }
        }
    }
}

/** Marks a download that has never been played. Retired on first playback. */
@Composable
private fun NewBadge(modifier: Modifier = Modifier) {
    Text(
        text = "New",
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onPrimary,
        modifier = modifier
            .clip(RoundedCornerShape(4.dp))
            .background(MaterialTheme.colorScheme.primary)
            .padding(horizontal = 5.dp, vertical = 1.dp),
    )
}

@Composable
private fun MoveTargetDialog(
    title: String,
    categories: List<CategoryWithStats>,
    onDismiss: () -> Unit,
    onConfirm: (Long) -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            if (categories.isEmpty()) {
                Text("他にフォルダがありません")
            } else {
                LazyColumn(modifier = Modifier.heightIn(max = 320.dp)) {
                    items(categories, key = { it.category.id }) { entry ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onConfirm(entry.category.id) }
                                .padding(vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            CategorySwatch(
                                colorArgb = entry.category.colorArgb,
                                modifier = Modifier.size(20.dp),
                            )
                            Text(
                                text = entry.category.name,
                                modifier = Modifier.padding(start = 12.dp),
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("キャンセル") } },
    )
}

@Composable
private fun DeleteCategoryDialog(
    entry: CategoryWithStats,
    others: List<CategoryWithStats>,
    onDismiss: () -> Unit,
    onConfirm: (Long?) -> Unit,
) {
    val hasItems = entry.itemCount > 0
    val fallback = others.firstOrNull()?.category?.id

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("「${entry.category.name}」を削除") },
        text = {
            Text(
                if (hasItems) {
                    "${entry.itemCount} 件の動画が入っています。中身をどうしますか？"
                } else {
                    "このフォルダを削除します。"
                },
            )
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(null) }) {
                Text(if (hasItems) "中身ごと削除" else "削除")
            }
        },
        dismissButton = {
            Row {
                if (hasItems && fallback != null) {
                    TextButton(onClick = { onConfirm(fallback) }) { Text("移動して削除") }
                }
                TextButton(onClick = onDismiss) { Text("キャンセル") }
            }
        },
    )
}

@Composable
private fun TextPromptDialog(
    title: String,
    label: String,
    initial: String,
    confirmLabel: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var value by remember { mutableStateOf(initial) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = value,
                onValueChange = { value = it },
                label = { Text(label) },
                singleLine = true,
            )
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(value.trim()) },
                enabled = value.isNotBlank(),
            ) { Text(confirmLabel) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("キャンセル") } },
    )
}
