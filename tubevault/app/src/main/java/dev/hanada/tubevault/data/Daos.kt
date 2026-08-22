package dev.hanada.tubevault.data

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface CategoryDao {

    /**
     * `subtree` is a closure over every (ancestor, descendant) pair in the
     * tree, built once and then reused per row — a folder's stats need every
     * item under it, not just the ones sitting directly inside.
     */
    @Query(
        """
        WITH RECURSIVE subtree(ancestor, id) AS (
            SELECT id, id FROM categories
            UNION ALL
            SELECT subtree.ancestor, categories.id
            FROM categories JOIN subtree ON categories.parentId = subtree.id
        )
        SELECT c.*,
               (SELECT COUNT(*) FROM media_items m
                WHERE m.categoryId IN (SELECT id FROM subtree WHERE ancestor = c.id)) AS itemCount,
               (SELECT COALESCE(SUM(m.fileSizeBytes), 0) FROM media_items m
                WHERE m.categoryId IN (SELECT id FROM subtree WHERE ancestor = c.id)) AS totalBytes,
               (SELECT COUNT(*) FROM categories child WHERE child.parentId = c.id) AS subfolderCount
        FROM categories c
        ORDER BY c.sortOrder ASC, c.createdAt ASC
        """,
    )
    fun observeWithStats(): Flow<List<CategoryWithStats>>

    @Query("SELECT * FROM categories ORDER BY sortOrder ASC, createdAt ASC")
    fun observeAll(): Flow<List<CategoryEntity>>

    @Query("SELECT * FROM categories ORDER BY sortOrder ASC, createdAt ASC")
    suspend fun getAll(): List<CategoryEntity>

    @Query("SELECT * FROM categories WHERE id = :id")
    suspend fun getById(id: Long): CategoryEntity?

    @Query("SELECT * FROM categories WHERE id = :id")
    fun observeById(id: Long): Flow<CategoryEntity?>

    @Query("SELECT COUNT(*) FROM categories")
    suspend fun count(): Int

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(category: CategoryEntity): Long

    @Update
    suspend fun update(category: CategoryEntity)

    @Delete
    suspend fun delete(category: CategoryEntity)
}

@Dao
interface MediaDao {

    @Query("SELECT * FROM media_items WHERE categoryId = :categoryId ORDER BY downloadedAt DESC")
    fun observeByCategory(categoryId: Long): Flow<List<MediaItemEntity>>

    /** Every item across a set of folders — how a folder's subtree is played as one queue. */
    @Query("SELECT * FROM media_items WHERE categoryId IN (:categoryIds) ORDER BY downloadedAt DESC")
    fun observeByCategories(categoryIds: List<Long>): Flow<List<MediaItemEntity>>

    @Query("SELECT * FROM media_items ORDER BY downloadedAt DESC LIMIT :limit")
    fun observeRecent(limit: Int): Flow<List<MediaItemEntity>>

    @Query("SELECT * FROM media_items ORDER BY downloadedAt DESC")
    fun observeAll(): Flow<List<MediaItemEntity>>

    @Query("SELECT * FROM media_items")
    suspend fun getAllOnce(): List<MediaItemEntity>

    @Query("SELECT * FROM media_items WHERE id = :id")
    suspend fun getById(id: Long): MediaItemEntity?

    @Query("SELECT * FROM media_items WHERE categoryId = :categoryId")
    suspend fun getByCategory(categoryId: Long): List<MediaItemEntity>

    @Query("SELECT videoId || ':' || kind FROM media_items")
    fun observeDownloadedKeys(): Flow<List<String>>

    @Query("SELECT COUNT(*) FROM media_items WHERE videoId = :videoId AND kind = :kind")
    suspend fun countFor(videoId: String, kind: String): Int

    @Query("SELECT * FROM media_items WHERE videoId = :videoId AND kind = :kind LIMIT 1")
    suspend fun findByVideo(videoId: String, kind: String): MediaItemEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(item: MediaItemEntity): Long

    @Update
    suspend fun update(item: MediaItemEntity)

    @Query("UPDATE media_items SET playbackPosMs = :positionMs, lastPlayedAt = :playedAt WHERE id = :id")
    suspend fun updateProgress(id: Long, positionMs: Long, playedAt: Long)

    @Query("UPDATE media_items SET lyricsArtist = :artist, lyricsTitle = :title WHERE id = :id")
    suspend fun updateLyricsInfo(id: Long, artist: String?, title: String?)

    /** Leaves the resume position alone — this only retires the "New" badge. */
    @Query("UPDATE media_items SET lastPlayedAt = :playedAt WHERE id = :id")
    suspend fun markPlayed(id: Long, playedAt: Long)

    @Query("DELETE FROM media_items WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("DELETE FROM media_items WHERE categoryId = :categoryId")
    suspend fun deleteByCategory(categoryId: Long)
}
