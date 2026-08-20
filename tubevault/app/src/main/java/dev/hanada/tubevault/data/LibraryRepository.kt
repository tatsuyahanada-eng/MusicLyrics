package dev.hanada.tubevault.data

import android.content.Context
import dev.hanada.tubevault.core.MediaKind
import dev.hanada.tubevault.core.Storage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
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

    suspend fun createCategory(name: String, colorArgb: Int? = null): Long {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return -1L
        val existing = categoryDao.getAll()
        val folder = Storage.uniqueFolderName(trimmed, existing.map { it.folderName }.toSet())
        val color = colorArgb ?: PALETTE[existing.size % PALETTE.size]
        val id = categoryDao.insert(
            CategoryEntity(
                name = trimmed,
                folderName = folder,
                colorArgb = color,
                sortOrder = (existing.maxOfOrNull { it.sortOrder } ?: -1) + 1,
            ),
        )
        withContext(Dispatchers.IO) { Storage.categoryDir(context, folder) }
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

        val oldDir = Storage.categoryDir(context, category.folderName)
        val newDir = File(Storage.rootDir(context), desiredFolder)
        val moved = withContext(Dispatchers.IO) { oldDir.renameTo(newDir) }

        if (!moved) {
            // Directory is busy or the filesystem refused; keep the old folder.
            categoryDao.update(category.copy(name = trimmed))
            return
        }

        categoryDao.update(category.copy(name = trimmed, folderName = desiredFolder))
        val oldPrefix = oldDir.absolutePath
        val newPrefix = newDir.absolutePath
        mediaDao.getByCategory(id).forEach { item ->
            mediaDao.update(
                item.copy(
                    filePath = item.filePath.replace(oldPrefix, newPrefix),
                    thumbPath = item.thumbPath?.replace(oldPrefix, newPrefix),
                ),
            )
        }
    }

    suspend fun setCategoryColor(id: Long, colorArgb: Int) {
        val category = categoryDao.getById(id) ?: return
        categoryDao.update(category.copy(colorArgb = colorArgb))
    }

    /**
     * Deletes a category. When [moveItemsTo] is given the downloads survive and
     * are relocated; otherwise their files are removed with the folder.
     * The last remaining category is never deleted — the library always needs
     * somewhere to put a download.
     */
    suspend fun deleteCategory(id: Long, moveItemsTo: Long?) {
        val category = categoryDao.getById(id) ?: return
        if (categoryDao.count() <= 1) return

        if (moveItemsTo != null && moveItemsTo != id) {
            mediaDao.getByCategory(id).forEach { moveItem(it.id, moveItemsTo) }
        } else {
            mediaDao.deleteByCategory(id)
        }

        categoryDao.delete(category)
        withContext(Dispatchers.IO) {
            Storage.categoryDir(context, category.folderName).deleteRecursively()
        }
    }

    /** Moves one download into another folder, on disk and in the database. */
    suspend fun moveItem(itemId: Long, targetCategoryId: Long) {
        val item = mediaDao.getById(itemId) ?: return
        if (item.categoryId == targetCategoryId) return
        val target = categoryDao.getById(targetCategoryId) ?: return

        withContext(Dispatchers.IO) {
            val sourceDir = File(item.filePath).parentFile
            val targetDir = Storage.categoryDir(context, target.folderName)
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

    /** Recreates any category folder a user deleted from outside the app. */
    suspend fun ensureFoldersExist() {
        val categories = categoryDao.getAll()
        withContext(Dispatchers.IO) {
            categories.forEach { Storage.categoryDir(context, it.folderName) }
        }
    }

    companion object {
        val PALETTE = listOf(
            0xFF7B2FF7.toInt(),
            0xFF2F80ED.toInt(),
            0xFF27AE60.toInt(),
            0xFFEB5757.toInt(),
            0xFFF2994A.toInt(),
            0xFF9B51E0.toInt(),
            0xFF00B8D9.toInt(),
        )

        private val DEFAULT_CATEGORIES = listOf(
            "音楽" to PALETTE[0],
            "学習" to PALETTE[1],
            "あとで見る" to PALETTE[2],
            "未分類" to PALETTE[3],
        )
    }
}
