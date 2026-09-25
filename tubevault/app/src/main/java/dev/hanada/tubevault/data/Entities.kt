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
 *
 * [parentId] nests one folder inside another, both on screen and on disk — a
 * child's directory lives inside its parent's, so deleting a parent's folder
 * takes every descendant's files with it for free. Null means top-level.
 * [folderName] stays unique across the whole tree rather than just among
 * siblings, which is what lets a plain rename move a folder without first
 * checking for a name collision anywhere else in the tree.
 */
@Entity(
    tableName = "categories",
    indices = [Index(value = ["folderName"], unique = true), Index(value = ["parentId"])],
)
data class CategoryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val folderName: String,
    val colorArgb: Int,
    val parentId: Long? = null,
    val sortOrder: Int = 0,
    val createdAt: Long = System.currentTimeMillis(),
)

/**
 * One downloaded file. The unique index on (categoryId, videoId, kind) is
 * what lets the same video sit in more than one folder as independent copies
 * — each folder's own row and file — while still stopping the same video
 * being downloaded twice into the *same* folder as the same media type.
 */
@Entity(
    tableName = "media_items",
    indices = [
        Index(value = ["categoryId"]),
        Index(value = ["categoryId", "videoId", "kind"], unique = true),
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
    // A video title is rarely a clean "artist - title" pair on its own, so
    // these hold the user's correction once they make one; null means "use
    // the best guess from title/uploader" rather than "no lyrics".
    val lyricsArtist: String? = null,
    val lyricsTitle: String? = null,
) {
    val mediaKind: MediaKind
        get() = runCatching { MediaKind.valueOf(kind) }.getOrDefault(MediaKind.VIDEO)
}

/**
 * Category plus the aggregates the library grid needs, in one query.
 * [itemCount] and [totalBytes] cover the whole subtree — a folder that is
 * mostly subfolders should still say how much is inside it overall, not
 * report empty because nothing sits directly in it. [subfolderCount] is
 * direct children only, so a folder card can say "3 subfolders" without that
 * number growing with grandchildren too.
 */
data class CategoryWithStats(
    @Embedded val category: CategoryEntity,
    val itemCount: Int,
    val totalBytes: Long,
    val subfolderCount: Int,
)

/**
 * A lyrics lookup this device has already seen succeed, kept so it never has
 * to ask LRCLIB again for the same pairing — a bad connection, a rate limit,
 * or the site simply being down can otherwise turn a song that always worked
 * into one that suddenly doesn't. [artistKey]/[titleKey] are the lookup
 * (trimmed, lowercased) rather than display text; a lyric never changes once
 * found, so nothing here ever needs to expire.
 *
 * [synced] tells [text] apart: raw LRC (re-parsed on every read, so the
 * parser only has to live in one place) when true, plain lyrics when false.
 */
@Entity(
    tableName = "lyrics_cache",
    indices = [Index(value = ["artistKey", "titleKey"], unique = true)],
)
data class LyricsCacheEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val artistKey: String,
    val titleKey: String,
    val synced: Boolean,
    val text: String,
    val cachedAt: Long = System.currentTimeMillis(),
)

