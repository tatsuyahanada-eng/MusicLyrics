package dev.hanada.tubevault.data

import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import dev.hanada.tubevault.core.MediaKind

/**
 * A user-defined folder. [folderName] is the on-disk directory and [name] is
 * what the UI shows; they start out the same and are kept in sync on rename,
 * but the database is the authority if a rename ever fails.
 */
@Entity(
    tableName = "categories",
    indices = [Index(value = ["folderName"], unique = true)],
)
data class CategoryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val folderName: String,
    val colorArgb: Int,
    val sortOrder: Int = 0,
    val createdAt: Long = System.currentTimeMillis(),
)

/**
 * One downloaded file. The unique index on (videoId, kind) is what makes
 * "already downloaded" checks cheap and stops the same video being fetched
 * twice as the same media type.
 */
@Entity(
    tableName = "media_items",
    indices = [
        Index(value = ["categoryId"]),
        Index(value = ["videoId", "kind"], unique = true),
    ],
)
data class MediaItemEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val videoId: String,
    val title: String,
    val uploader: String?,
    val durationSec: Long,
    val kind: String,
    val categoryId: Long,
    val filePath: String,
    val thumbPath: String?,
    val fileSizeBytes: Long,
    val sourceUrl: String,
    val downloadedAt: Long,
    val lastPlayedAt: Long? = null,
    val playbackPosMs: Long = 0L,
) {
    val mediaKind: MediaKind
        get() = runCatching { MediaKind.valueOf(kind) }.getOrDefault(MediaKind.VIDEO)
}

/** Category plus the aggregates the library grid needs, in one query. */
data class CategoryWithStats(
    @Embedded val category: CategoryEntity,
    val itemCount: Int,
    val totalBytes: Long,
)
