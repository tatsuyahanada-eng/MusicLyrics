package dev.hanada.tubevault.data

import android.content.Context
import dev.hanada.tubevault.core.MediaKind
import dev.hanada.tubevault.core.Storage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.withContext
import java.io.File

/**
 * The single place where the database and the on-disk folder tree are kept in
 * agreement. Every operation that touches one touches the other.
 */
class LibraryRepository(
    private val context: Context,
    private val categoryDao: CategoryDao,
    private val mediaDao: MediaDao,
) {

    fun observeCategories(): Flow<List<CategoryWithStats>> = categoryDao.observeWithStats()

    fun observeCategoryList(): Flow<List<CategoryEntity>> = categoryDao.observeAll()

    fun observeItems(categoryId: Long): Flow<List<MediaItemEntity>> =
        mediaDao.observeByCategory(categoryId)

    /** Every item across a set of folders — a folder's whole subtree as one queue. */
    fun observeItemsIn(categoryIds: List<Long>): Flow<List<MediaItemEntity>> =
        if (categoryIds.isEmpty()) flowOf(emptyList()) else mediaDao.observeByCategories(categoryIds)

    fun observeRecent(limit: Int = 30): Flow<List<MediaItemEntity>> = mediaDao.observeRecent(limit)

    fun observeAllItems(): Flow<List<MediaItemEntity>> = mediaDao.observeAll()

    /** `"<videoId>:<kind>"` keys, so search rows can show an "取得済み" badge. */
    fun observeDownloadedKeys(): Flow<List<String>> = mediaDao.observeDownloadedKeys()

    suspend fun getCategories(): List<CategoryEntity> = categoryDao.getAll()

    suspend fun getCategory(id: Long): CategoryEntity? = categoryDao.getById(id)

    suspend fun getItem(id: Long): MediaItemEntity? = mediaDao.getById(id)

    suspend fun isDownloaded(videoId: String, kind: MediaKind): Boolean =
        mediaDao.countFor(videoId, kind.name) > 0

    /** Seeds a starter folder set the first time the app runs. */
    suspend fun seedDefaultsIfEmpty() {
        if (categoryDao.count() > 0) return
        DEFAULT_CATEGORIES.forEachIndexed { index, (name, color) ->
            categoryDao.insert(
                CategoryEntity(
                    name = name,
                    folderName = Storage.sanitizeFolderName(name),
                    colorArgb = color,
                    sortOrder = index,
                ),
            )
        }
    }

    /**
     * Creates a folder, nested inside [parentId] when given. [folderName]
     * still has to be unique across the whole tree, not just among siblings —
     * that is what lets a later rename move the folder without a fresh
     * collision check against every other branch.
     */
    suspend fun createCategory(name: String, colorArgb: Int? = null, parentId: Long? = null): Long {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return -1L
        val existing = categoryDao.getAll()
        val parent = parentId?.let { pid -> existing.firstOrNull { it.id == pid } }
        val siblings = existing.filter { it.parentId == parent?.id }
        val folder = Storage.uniqueFolderName(trimmed, existing.map { it.folderName }.toSet())
        val color = colorArgb ?: PALETTE[siblings.size % PALETTE.size]
        val id = categoryDao.insert(
            CategoryEntity(
                name = trimmed,
                folderName = folder,
                colorArgb = color,
                parentId = parent?.id,
                sortOrder = (siblings.maxOfOrNull { it.sortOrder } ?: -1) + 1,
            ),
        )
        withContext(Dispatchers.IO) {
            val base = if (parent == null) Storage.rootDir(context) else categoryDirFor(parent)
            Storage.categoryDir(base, folder)
        }
        return id
    }

    /** Renames the display name and, when possible, the directory behind it. */
    suspend fun renameCategory(id: Long, newName: String) {
        val trimmed = newName.trim()
        if (trimmed.isEmpty()) return
        val category = categoryDao.getById(id) ?: return
        val taken = categoryDao.getAll().filter { it.id != id }.map { it.folderName }.toSet()
        val desiredFolder = Storage.uniqueFolderName(trimmed, taken)

        if (desiredFolder == category.folderName) {
            categoryDao.update(category.copy(name = trimmed))
            return
        }

        // A rename only ever changes the leaf name — parentId is untouched —
        // so the new directory is a sibling of the old one, not resolved from
        // scratch, and no ancestor's directory needs to move.
        val oldDir = withContext(Dispatchers.IO) { categoryDirFor(category) }
        val newDir = File(oldDir.parentFile ?: Storage.rootDir(context), desiredFolder)
        val moved = withContext(Dispatchers.IO) { oldDir.renameTo(newDir) }

        if (!moved) {
            // Directory is busy or the filesystem refused; keep the old folder.
            categoryDao.update(category.copy(name = trimmed))
            return
        }

        categoryDao.update(category.copy(name = trimmed, folderName = desiredFolder))
        val oldPrefix = oldDir.absolutePath
        val newPrefix = newDir.absolutePath
        // The rename moved the whole directory in one go, so every descendant
        // folder's files moved with it — their rows need the same path
        // rewrite, not just the renamed folder's own items.
        descendantCategoryIds(id).forEach { subId ->
            mediaDao.getByCategory(subId).forEach { item ->
                mediaDao.update(
                    item.copy(
                        filePath = item.filePath.replace(oldPrefix, newPrefix),
                        thumbPath = item.thumbPath?.replace(oldPrefix, newPrefix),
                    ),
                )
            }
        }
    }

    suspend fun setCategoryColor(id: Long, colorArgb: Int) {
        val category = categoryDao.getById(id) ?: return
        categoryDao.update(category.copy(colorArgb = colorArgb))
    }

    /**
     * Deletes a folder and every folder nested inside it. When [moveItemsTo]
     * is given, every download in the whole subtree survives and is relocated
     * there; otherwise the subtree's files go with it — deleting the top
     * directory recursively takes every descendant's files along in one move,
     * so there is nothing to clean up per folder.
     *
     * [moveItemsTo] pointing inside the subtree being deleted (the folder
     * itself, or one of its own descendants) is treated the same as no
     * target: there is nowhere left to put the items once the whole branch is
     * gone. The library is never left with zero categories — deleting a
     * branch that would take every remaining folder with it is refused.
     */
    suspend fun deleteCategory(id: Long, moveItemsTo: Long?) {
        val category = categoryDao.getById(id) ?: return
        val subtreeIds = descendantCategoryIds(id)
        if (categoryDao.count() <= subtreeIds.size) return

        val target = moveItemsTo?.takeIf { it !in subtreeIds }
        subtreeIds.forEach { subId ->
            if (target != null) {
                mediaDao.getByCategory(subId).forEach { moveItem(it.id, target) }
            } else {
                mediaDao.deleteByCategory(subId)
            }
        }

        val dir = withContext(Dispatchers.IO) { categoryDirFor(category) }
        subtreeIds.forEach { subId -> categoryDao.getById(subId)?.let { categoryDao.delete(it) } }
        withContext(Dispatchers.IO) { dir.deleteRecursively() }
    }

    /** Moves one download into another folder, on disk and in the database. */
    suspend fun moveItem(itemId: Long, targetCategoryId: Long) {
        val item = mediaDao.getById(itemId) ?: return
        if (item.categoryId == targetCategoryId) return
        val target = categoryDao.getById(targetCategoryId) ?: return

        withContext(Dispatchers.IO) {
            val sourceDir = File(item.filePath).parentFile
            val targetDir = categoryDirFor(target)
            val movedMedia = if (sourceDir != null) {
                Storage.moveFilesFor(sourceDir, targetDir, item.videoId)
            } else {
                null
            }
            val thumb = Storage.findThumbnailFile(targetDir, item.videoId)
            mediaDao.update(
                item.copy(
                    categoryId = targetCategoryId,
                    filePath = movedMedia?.absolutePath ?: item.filePath,
                    thumbPath = thumb?.absolutePath ?: item.thumbPath,
                ),
            )
        }
    }

    suspend fun deleteItem(itemId: Long) {
        val item = mediaDao.getById(itemId) ?: return
        mediaDao.deleteById(itemId)
        withContext(Dispatchers.IO) {
            File(item.filePath).parentFile?.let { Storage.deleteFilesFor(it, item.videoId) }
        }
    }

    /**
     * Marks an item as having been played at all, which is what the "New"
     * badge keys off. Called the moment a track starts rather than waiting for
     * [recordPlayback], which only fires once a position is worth saving —
     * a badge that lingers for the first few seconds of playback looks broken.
     */
    suspend fun markPlayed(itemId: Long) {
        mediaDao.markPlayed(itemId, System.currentTimeMillis())
    }

    suspend fun recordPlayback(itemId: Long, positionMs: Long) {
        mediaDao.updateProgress(itemId, positionMs, System.currentTimeMillis())
    }

    /**
     * Stores a finished download. Re-downloading something that already exists
     * replaces the row in place and removes the stale file if it landed in a
     * different folder this time.
     */
    suspend fun addDownloaded(item: MediaItemEntity): Long {
        val existing = mediaDao.findByVideo(item.videoId, item.kind)
        if (existing != null && existing.filePath != item.filePath) {
            withContext(Dispatchers.IO) {
                File(existing.filePath).parentFile?.let { Storage.deleteFilesFor(it, existing.videoId) }
            }
        }
        return mediaDao.insert(item.copy(id = existing?.id ?: 0L))
    }

    /** Drops rows whose file disappeared (manual deletion, storage cleaner, ...). */
    suspend fun pruneMissingFiles(): Int {
        val stale = withContext(Dispatchers.IO) {
            mediaDao.getAllOnce().filterNot { File(it.filePath).exists() }
        }
        stale.forEach { mediaDao.deleteById(it.id) }
        return stale.size
    }

    /**
     * Repaints folders still carrying a colour from the old rainbow palette.
     * Matching by exact value means a colour the user picked deliberately is
     * left alone — only the ones this app assigned itself are migrated.
     */
    suspend fun harmonizeLegacyColors() {
        val remap = LEGACY_PALETTES.flatMap { it.zip(PALETTE) }.toMap()
        categoryDao.getAll().forEach { category ->
            remap[category.colorArgb]?.let {
                categoryDao.update(category.copy(colorArgb = it))
            }
        }
    }

    /** Recreates any category folder a user deleted from outside the app. */
    suspend fun ensureFoldersExist() {
        val categories = categoryDao.getAll()
        withContext(Dispatchers.IO) {
            // Each call resolves its own full ancestor chain, so the order
            // here does not matter — a child processed before its parent
            // still creates every intermediate directory along the way.
            categories.forEach { categoryDirFor(it) }
        }
    }

    /** The directory backing an existing folder, resolving nesting via its id. */
    suspend fun categoryDir(categoryId: Long): File? = categoryDao.getById(categoryId)?.let { categoryDirFor(it) }

    /**
     * [categoryId] and every folder nested inside it, at any depth,
     * self-inclusive. Built by walking the whole category list in memory
     * rather than a recursive query per call — the tree is small enough
     * (personal folders, not a filesystem) that this is simpler than wiring
     * up a suspend-recursive CTE for every call site that needs it.
     */
    suspend fun descendantCategoryIds(categoryId: Long): List<Long> {
        val childrenOf = categoryDao.getAll().groupBy { it.parentId }
        val result = mutableListOf<Long>()
        fun collect(id: Long) {
            result += id
            childrenOf[id]?.forEach { collect(it.id) }
        }
        collect(categoryId)
        return result
    }

    /**
     * Resolves [category]'s directory by walking up its parent chain,
     * nesting each level inside the last. This is what makes deleting or
     * renaming a parent's directory take its whole subtree of files with it
     * as a single filesystem move, rather than something this class has to
     * replicate folder by folder.
     */
    private suspend fun categoryDirFor(category: CategoryEntity): File {
        val base = category.parentId
            ?.let { categoryDao.getById(it) }
            ?.let { categoryDirFor(it) }
            ?: Storage.rootDir(context)
        return Storage.categoryDir(base, category.folderName)
    }

    companion object {
        /**
         * Bright enough to hold up against a near-black background, muted
         * enough not to fight the yellow accent. A swatch is the only colour
         * a folder gets, so it has to read at 20dp without shouting.
         */
        val PALETTE = listOf(
            0xFFE8B93B.toInt(),
            0xFFC9CDD1.toInt(),
            0xFF9AAE63.toInt(),
            0xFF6E93A8.toInt(),
            0xFFC08457.toInt(),
            0xFF9F8FBF.toInt(),
            0xFF5FB89C.toInt(),
        )

        /**
         * Every palette [PALETTE] has replaced, newest first. Folders are
         * migrated by position, so a folder keeps the slot it was given even
         * as the colours in that slot change.
         */
        private val LEGACY_PALETTES = listOf(
            // The muted grey/teal set, from when the app was light grey.
            listOf(
                0xFF2F6F68.toInt(),
                0xFF3E6B8A.toInt(),
                0xFF5D6B7D.toInt(),
                0xFF6E6A82.toInt(),
                0xFF7A6A5D.toInt(),
                0xFF4F7355.toInt(),
                0xFF8A6A6A.toInt(),
            ),
            // The original saturated rainbow.
            listOf(
                0xFF7B2FF7.toInt(),
                0xFF2F80ED.toInt(),
                0xFF27AE60.toInt(),
                0xFFEB5757.toInt(),
                0xFFF2994A.toInt(),
                0xFF9B51E0.toInt(),
                0xFF00B8D9.toInt(),
            ),
        )

        private val DEFAULT_CATEGORIES = listOf(
            "音楽" to PALETTE[0],
            "学習" to PALETTE[1],
            "あとで見る" to PALETTE[2],
            "未分類" to PALETTE[3],
        )
    }
}
