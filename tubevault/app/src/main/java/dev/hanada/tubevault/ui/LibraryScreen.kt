package dev.hanada.tubevault.ui

import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
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
import androidx.compose.foundation.lazy.item
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
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DriveFileMove
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.FileUpload
import androidx.compose.material.icons.filled.SelectAll
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material.icons.filled.ViewList
import androidx.compose.material.icons.filled.ViewModule
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.hanada.tubevault.core.MediaKind
import dev.hanada.tubevault.core.formatBytes
import dev.hanada.tubevault.core.formatDuration
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
    val rootCategories by viewModel.rootCategories.collectAsStateWithLifecycle()
    val subcategories by viewModel.subcategories.collectAsStateWithLifecycle()
    val openCategory by viewModel.openCategory.collectAsStateWithLifecycle()
    val items by viewModel.items.collectAsStateWithLifecycle()
    val subtreeItems by viewModel.subtreeItems.collectAsStateWithLifecycle()
    val allItems by viewModel.allItems.collectAsStateWithLifecycle()
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val importing by viewModel.importing.collectAsStateWithLifecycle()
    val importStatus by viewModel.importStatus.collectAsStateWithLifecycle()

    BackHandler(enabled = openCategory != null) { viewModel.closeCategory() }

    val context = LocalContext.current
    LaunchedEffect(importStatus) {
        importStatus?.let {
            Toast.makeText(context, it, Toast.LENGTH_SHORT).show()
            viewModel.consumeImportStatus()
        }
    }

    Box(modifier = modifier.fillMaxSize().padding(contentPadding)) {
        val current = openCategory
        if (current == null) {
            CategoryGrid(
                categories = rootCategories,
                allCategories = categories,
                canShuffle = allItems.isNotEmpty(),
                onOpen = viewModel::open,
                onCreate = { name -> viewModel.createCategory(name) },
                onRename = viewModel::renameCategory,
                onDelete = viewModel::deleteCategory,
                onMoveCategory = viewModel::moveCategory,
                onShuffleAll = viewModel::shuffleAll,
            )
        } else {
            CategoryDetail(
                category = current,
                items = items,
                subcategories = subcategories,
                canPlayFolder = subtreeItems.isNotEmpty(),
                allCategories = categories,
                onBack = viewModel::closeCategory,
                onPlay = { index -> viewModel.play(items, index) },
                onPlayFolder = viewModel::playFolder,
                onShuffleFolder = viewModel::shuffleFolder,
                onMove = viewModel::moveItem,
                onDelete = viewModel::deleteItem,
                onMoveMany = viewModel::moveItems,
                onDeleteMany = viewModel::deleteItems,
                onOpenSub = viewModel::open,
                onCreateSub = viewModel::createSubfolder,
                onRenameSub = viewModel::renameCategory,
                onDeleteSub = viewModel::deleteCategory,
                onMoveCategory = viewModel::moveCategory,
                compact = settings.compactLibrary,
                onToggleCompact = viewModel::toggleCompactLibrary,
                importing = importing,
                onImport = viewModel::importFiles,
            )
        }
    }
}

@Composable
private fun CategoryGrid(
    categories: List<CategoryWithStats>,
    allCategories: List<CategoryWithStats>,
    canShuffle: Boolean,
    onOpen: (Long) -> Unit,
    onCreate: (String) -> Unit,
    onRename: (Long, String) -> Unit,
    onDelete: (Long, Long?) -> Unit,
    onMoveCategory: (Long, Long?) -> Unit,
    onShuffleAll: () -> Unit,
) {
    var showCreate by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf<CategoryWithStats?>(null) }
    var deleting by remember { mutableStateOf<CategoryWithStats?>(null) }
    var moving by remember { mutableStateOf<CategoryWithStats?>(null) }

    Column(modifier = Modifier.fillMaxSize()) {
        ScreenHeader(
            title = "フォルダ",
            actions = {
                // Folder boundaries are for organising, not for limiting what
                // plays — this pulls from every folder at once, so there is a
                // one-tap "just play something" without picking one first.
                if (canShuffle) {
                    FilledTonalIconButton(onClick = onShuffleAll) {
                        Icon(Icons.Default.Shuffle, contentDescription = "すべてシャッフル再生")
                    }
                }
                FilledTonalIconButton(
                    onClick = { showCreate = true },
                    modifier = Modifier.padding(start = 8.dp),
                ) {
                    Icon(Icons.Default.Add, contentDescription = "フォルダを追加")
                }
            },
        )

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
                        onMove = { moving = entry },
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
        // A folder about to be deleted can't also be the place its own items
        // land — excludes the whole subtree, not just the folder itself, so a
        // parent full of subfolders can never be offered as its own target.
        val excluded = categorySubtreeIds(allCategories, entry.category.id).toSet()
        DeleteCategoryDialog(
            entry = entry,
            others = allCategories.filter { it.category.id !in excluded },
            onDismiss = { deleting = null },
            onConfirm = { moveTo ->
                onDelete(entry.category.id, moveTo)
                deleting = null
            },
        )
    }

    moving?.let { entry ->
        // A folder can't move into itself or anywhere inside its own
        // subtree — either does nothing or turns the tree into a cycle.
        val excluded = categorySubtreeIds(allCategories, entry.category.id).toSet()
        MoveCategoryDialog(
            title = "「${entry.category.name}」の移動先",
            candidates = allCategories.filter { it.category.id !in excluded },
            currentParentId = entry.category.parentId,
            onDismiss = { moving = null },
            onConfirm = { newParentId ->
                onMoveCategory(entry.category.id, newParentId)
                moving = null
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
    onMove: () -> Unit,
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
                text = listOfNotNull(
                    "${entry.subfolderCount} 個のフォルダ".takeIf { entry.subfolderCount > 0 },
                    "${entry.itemCount} 件",
                    formatBytes(entry.totalBytes),
                ).joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun CategoryDetail(
    category: CategoryWithStats,
    items: List<MediaItemEntity>,
    subcategories: List<CategoryWithStats>,
    canPlayFolder: Boolean,
    allCategories: List<CategoryWithStats>,
    onBack: () -> Unit,
    onPlay: (Int) -> Unit,
    onPlayFolder: () -> Unit,
    onShuffleFolder: () -> Unit,
    onMove: (Long, Long) -> Unit,
    onDelete: (Long) -> Unit,
    onMoveMany: (Set<Long>, Long) -> Unit,
    onDeleteMany: (Set<Long>) -> Unit,
    onOpenSub: (Long) -> Unit,
    onCreateSub: (String) -> Unit,
    onRenameSub: (Long, String) -> Unit,
    onDeleteSub: (Long, Long?) -> Unit,
    onMoveCategory: (Long, Long?) -> Unit,
    compact: Boolean,
    onToggleCompact: () -> Unit,
    importing: Boolean,
    onImport: (List<Uri>) -> Unit,
) {
    // ACTION_OPEN_DOCUMENT rather than a media-store query: it needs no storage
    // permission, and it lets the user reach files a media scanner never
    // indexed — downloads folders, anything sideloaded over USB.
    val picker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments(),
    ) { uris -> onImport(uris) }

    var moving by remember { mutableStateOf<MediaItemEntity?>(null) }
    var deleting by remember { mutableStateOf<MediaItemEntity?>(null) }
    var showCreateSub by remember { mutableStateOf(false) }
    var renamingSub by remember { mutableStateOf<CategoryWithStats?>(null) }
    var deletingSub by remember { mutableStateOf<CategoryWithStats?>(null) }
    var movingSub by remember { mutableStateOf<CategoryWithStats?>(null) }

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
            ScreenHeader(
                title = category.category.name,
                subtitle = listOfNotNull(
                    "${category.subfolderCount} 個のフォルダ".takeIf { category.subfolderCount > 0 },
                    "${category.itemCount} 件",
                    formatBytes(category.totalBytes),
                ).joinToString(" · "),
                leading = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "戻る")
                    }
                },
                actions = {
                    // Both draw from the folder's whole subtree, subfolders
                    // included — grouping tracks into subfolders organises
                    // them, it does not wall them off from the folder's own
                    // playback.
                    if (canPlayFolder) {
                        FilledTonalIconButton(onClick = onShuffleFolder) {
                            Icon(Icons.Default.Shuffle, contentDescription = "シャッフル再生")
                        }
                        FilledIconButton(
                            onClick = onPlayFolder,
                            modifier = Modifier.padding(start = 6.dp),
                        ) {
                            Icon(Icons.Default.PlayArrow, contentDescription = "先頭から再生")
                        }
                    }
                    FolderMenu(
                        compact = compact,
                        importEnabled = !importing,
                        onToggleCompact = onToggleCompact,
                        onImport = { picker.launch(IMPORT_MIME_TYPES) },
                        onCreateSubfolder = { showCreateSub = true },
                    )
                },
            )
        }

        if (importing) {
            // Copying a long video off the device is not instant, and the
            // folder shows nothing until a file lands, so say something.
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        }

        if (items.isEmpty() && subcategories.isEmpty()) {
            EmptyState(
                icon = Icons.Default.Folder,
                title = "まだ何もありません",
                subtitle = "検索タブからダウンロードする、右上のメニューから端末の動画・音楽を取り込む、" +
                    "またはサブフォルダを作って整理してください",
            )
        } else {
            LazyColumn(
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                verticalArrangement = Arrangement.spacedBy(if (compact) 3.dp else 6.dp),
            ) {
                // Subfolders first: they're how this folder is organised, so
                // they read as the folder's structure before its own content.
                // itemsIndexed rather than the `items` DSL function — a local
                // val named `items` is already in scope here, and shadows it.
                itemsIndexed(subcategories, key = { _, entry -> "sub-${entry.category.id}" }) { _, entry ->
                    SubfolderRow(
                        entry = entry,
                        onClick = { onOpenSub(entry.category.id) },
                        onRename = { renamingSub = entry },
                        onDelete = { deletingSub = entry },
                        onMove = { movingSub = entry },
                    )
                }
                itemsIndexed(items, key = { _, item -> item.id }) { index, item ->
                    MediaRow(
                        item = item,
                        compact = compact,
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

    if (showCreateSub) {
        TextPromptDialog(
            title = "サブフォルダを作成",
            label = "フォルダ名",
            initial = "",
            confirmLabel = "作成",
            onDismiss = { showCreateSub = false },
            onConfirm = {
                onCreateSub(it)
                showCreateSub = false
            },
        )
    }

    renamingSub?.let { entry ->
        TextPromptDialog(
            title = "フォルダ名を変更",
            label = "フォルダ名",
            initial = entry.category.name,
            confirmLabel = "変更",
            onDismiss = { renamingSub = null },
            onConfirm = {
                onRenameSub(entry.category.id, it)
                renamingSub = null
            },
        )
    }

    deletingSub?.let { entry ->
        // Same subtree exclusion as the top-level grid's delete dialog: a
        // folder about to go can't be offered as the place its own items land.
        val excluded = categorySubtreeIds(allCategories, entry.category.id).toSet()
        DeleteCategoryDialog(
            entry = entry,
            others = allCategories.filter { it.category.id !in excluded },
            onDismiss = { deletingSub = null },
            onConfirm = { moveTo ->
                onDeleteSub(entry.category.id, moveTo)
                deletingSub = null
            },
        )
    }

    movingSub?.let { entry ->
        val excluded = categorySubtreeIds(allCategories, entry.category.id).toSet()
        MoveCategoryDialog(
            title = "「${entry.category.name}」の移動先",
            candidates = allCategories.filter { it.category.id !in excluded },
            currentParentId = entry.category.parentId,
            onDismiss = { movingSub = null },
            onConfirm = { newParentId ->
                onMoveCategory(entry.category.id, newParentId)
                movingSub = null
            },
        )
    }
}

/**
 * A folder nested inside another, shown the way a media row is — the same
 * list, the same tap-to-open, so a folder full of subfolders does not need
 * a second, differently-shaped screen to browse.
 */
@Composable
private fun SubfolderRow(
    entry: CategoryWithStats,
    onClick: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
    onMove: () -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }

    SurfaceCard(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onClick)
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CategorySwatch(colorArgb = entry.category.colorArgb, modifier = Modifier.size(40.dp)) {
                Icon(
                    imageVector = Icons.Default.Folder,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(20.dp),
                )
            }
            Column(modifier = Modifier.weight(1f).padding(start = 12.dp)) {
                Text(
                    text = entry.category.name,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = listOfNotNull(
                        "${entry.subfolderCount} 個のフォルダ".takeIf { entry.subfolderCount > 0 },
                        "${entry.itemCount} 件",
                        formatBytes(entry.totalBytes),
                    ).joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Box {
                IconButton(onClick = { menuOpen = true }) {
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
                }
            }
        }
    }
}

/**
 * The folder's less-used actions. They are behind one button because the
 * header already carries shuffle and play, and four icon buttons plus a back
 * arrow leaves a folder name nowhere to go on a phone.
 */
@Composable
private fun FolderMenu(
    compact: Boolean,
    importEnabled: Boolean,
    onToggleCompact: () -> Unit,
    onImport: () -> Unit,
    onCreateSubfolder: () -> Unit,
) {
    var open by remember { mutableStateOf(false) }

    Box {
        IconButton(onClick = { open = true }) {
            Icon(Icons.Default.MoreVert, contentDescription = "フォルダのメニュー")
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(
                text = { Text("サブフォルダを作成") },
                leadingIcon = { Icon(Icons.Default.CreateNewFolder, contentDescription = null) },
                onClick = {
                    open = false
                    onCreateSubfolder()
                },
            )
            DropdownMenuItem(
                text = { Text(if (compact) "サムネイル表示" else "タイトルのみ表示") },
                leadingIcon = {
                    Icon(
                        imageVector = if (compact) Icons.Default.ViewModule else Icons.Default.ViewList,
                        contentDescription = null,
                    )
                },
                onClick = {
                    open = false
                    onToggleCompact()
                },
            )
            DropdownMenuItem(
                text = { Text("端末から取り込む") },
                leadingIcon = { Icon(Icons.Default.FileUpload, contentDescription = null) },
                enabled = importEnabled,
                onClick = {
                    open = false
                    onImport()
                },
            )
        }
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
    compact: Boolean,
    selecting: Boolean,
    selected: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onMove: () -> Unit,
    onDelete: () -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }

    // primaryContainer alone read too close to the row's normal background
    // to tell selected rows apart at a glance — a tinted fill plus a solid
    // accent-coloured border makes a selected row unmistakable.
    Surface(
        shape = MaterialTheme.shapes.medium,
        color = if (selected) {
            MaterialTheme.colorScheme.primary.copy(alpha = 0.20f)
        } else {
            MaterialTheme.colorScheme.surfaceContainerHigh
        },
        border = if (selected) {
            BorderStroke(1.5.dp, MaterialTheme.colorScheme.primary)
        } else {
            null
        },
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .combinedClickable(onClick = onClick, onLongClick = onLongClick)
                .padding(horizontal = if (compact) 12.dp else 10.dp, vertical = if (compact) 4.dp else 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (selecting) {
                Checkbox(
                    checked = selected,
                    onCheckedChange = { onClick() },
                    modifier = Modifier.padding(end = 4.dp),
                )
            }
            if (compact) {
                // Titles-only: the kind glyph replaces the artwork, since with
                // no picture there is otherwise nothing telling a track apart
                // from a video at a glance.
                Icon(
                    imageVector = if (item.mediaKind == MediaKind.AUDIO) {
                        Icons.Default.Audiotrack
                    } else {
                        Icons.Default.Videocam
                    },
                    contentDescription = item.mediaKind.label,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(18.dp),
                )
            } else {
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
            }
            Column(modifier = Modifier.weight(1f).padding(start = 12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (compact && item.lastPlayedAt == null) {
                        NewBadge(modifier = Modifier.padding(end = 6.dp))
                    }
                    Text(
                        text = item.title,
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = if (compact) 1 else 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                if (!compact) {
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
            }
            if (compact && item.durationSec > 0) {
                Text(
                    text = formatDuration(item.durationSec),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 8.dp),
                )
            }
            // The per-row menu would just get in the way of tap-to-toggle.
            if (!selecting) {
                Box {
                    IconButton(
                        onClick = { menuOpen = true },
                        modifier = Modifier.size(if (compact) 32.dp else 48.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Default.MoreVert,
                            contentDescription = "メニュー",
                            modifier = Modifier.size(if (compact) 18.dp else 24.dp),
                        )
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

/**
 * Where a folder moves to. Unlike [MoveTargetDialog] (an item's destination
 * folder, which is always some existing folder) a folder's destination can
 * also be "nowhere" — the top level — so this offers that as an explicit
 * first row rather than only ever listing other folders.
 */
@Composable
private fun MoveCategoryDialog(
    title: String,
    candidates: List<CategoryWithStats>,
    currentParentId: Long?,
    onDismiss: () -> Unit,
    onConfirm: (Long?) -> Unit,
) {
    val destinations = candidates.filter { it.category.id != currentParentId }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            LazyColumn(modifier = Modifier.heightIn(max = 320.dp)) {
                if (currentParentId != null) {
                    item {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onConfirm(null) }
                                .padding(vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(
                                imageVector = Icons.Default.Folder,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(20.dp),
                            )
                            Text(
                                text = "ルート（トップ階層）",
                                modifier = Modifier.padding(start = 12.dp),
                            )
                        }
                    }
                }
                itemsIndexed(destinations, key = { _, entry -> entry.category.id }) { _, entry ->
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
                if (destinations.isEmpty() && currentParentId == null) {
                    item { Text("他にフォルダがありません", modifier = Modifier.padding(vertical = 12.dp)) }
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
    val hasSubfolders = entry.subfolderCount > 0
    val fallback = others.firstOrNull()?.category?.id

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("「${entry.category.name}」を削除") },
        text = {
            Text(
                when {
                    hasItems && hasSubfolders ->
                        "中の${entry.subfolderCount} 個のフォルダに、合計 ${entry.itemCount} 件の動画が入っています。中身をどうしますか？"
                    hasItems -> "${entry.itemCount} 件の動画が入っています。中身をどうしますか？"
                    hasSubfolders -> "中に ${entry.subfolderCount} 個の空のフォルダがあります。まとめて削除します。"
                    else -> "このフォルダを削除します。"
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

/** What the picker will offer: playable media only, nothing else. */
private val IMPORT_MIME_TYPES = arrayOf("video/*", "audio/*")
