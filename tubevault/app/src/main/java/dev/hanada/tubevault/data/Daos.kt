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

    @Query(
        """
        SELECT c.*,
               (SELECT COUNT(*) FROM media_items m WHERE m.categoryId = c.id) AS itemCount,
               (SELECT COALESCE(SUM(m.fileSizeBytes), 0) FROM media_items m WHERE m.categoryId = c.id) AS totalBytes
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

    @Query("DELETE FROM media_items WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("DELETE FROM media_items WHERE categoryId = :categoryId")
    suspend fun deleteByCategory(categoryId: Long)
}
