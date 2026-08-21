package dev.hanada.tubevault.importer

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log
import android.webkit.MimeTypeMap
import dev.hanada.tubevault.core.MediaKind
import dev.hanada.tubevault.data.LibraryRepository
import dev.hanada.tubevault.data.MediaItemEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

/**
 * Brings audio and video already on the device into the library.
 *
 * The file is copied rather than referenced. Everything downstream — moving
 * between folders, deleting, resuming playback, the folder's byte count —
 * assumes a real file under the app's own directory that nothing else can
 * move or revoke, and a content URI from the picker guarantees none of that:
 * the read grant lasts only as long as this activity, and the user is free to
 * delete the original the moment the picker closes.
 */
class LocalImporter(
    private val context: Context,
    private val library: LibraryRepository,
) {

    data class Outcome(val imported: Int, val failed: Int)

    suspend fun import(uris: List<Uri>, categoryId: Long): Outcome = withContext(Dispatchers.IO) {
        if (uris.isEmpty()) return@withContext Outcome(0, 0)
        val targetDir = library.categoryDir(categoryId) ?: return@withContext Outcome(0, uris.size)

        var imported = 0
        var failed = 0
        for (uri in uris) {
            val ok = runCatching { importOne(uri, targetDir, categoryId) }
                .onFailure { Log.w(TAG, "could not import $uri", it) }
                .getOrDefault(false)
            if (ok) imported++ else failed++
        }
        Outcome(imported, failed)
    }

    private suspend fun importOne(uri: Uri, targetDir: File, categoryId: Long): Boolean {
        val displayName = queryDisplayName(uri)
        val mime = context.contentResolver.getType(uri).orEmpty()
        val kind = if (mime.startsWith("audio/")) MediaKind.AUDIO else MediaKind.VIDEO

        // Local items need an id in the same shape as a video id because the
        // whole storage layer names files after one. A random one also means
        // importing the same file twice makes two entries rather than one
        // silently overwriting the other.
        val id = LOCAL_PREFIX + UUID.randomUUID().toString().replace("-", "").take(12)
        val extension = extensionFor(displayName, mime, kind)
        val destination = File(targetDir, "$id.$extension")

        val copied = context.contentResolver.openInputStream(uri)?.use { input ->
            destination.outputStream().use { output -> input.copyTo(output) }
            true
        } ?: false
        if (!copied || destination.length() == 0L) {
            destination.delete()
            return false
        }

        val probe = probe(destination)
        val thumb = probe.artwork?.let { writeThumbnail(targetDir, id, it) }

        library.addDownloaded(
            MediaItemEntity(
                videoId = id,
                title = probe.title ?: displayName?.substringBeforeLast('.') ?: "取り込んだファイル",
                uploader = probe.artist,
                durationSec = probe.durationMs / 1000,
                kind = kind.name,
                categoryId = categoryId,
                filePath = destination.absolutePath,
                thumbPath = thumb?.absolutePath,
                fileSizeBytes = destination.length(),
                sourceUrl = uri.toString(),
                downloadedAt = System.currentTimeMillis(),
            ),
        )
        return true
    }

    private fun queryDisplayName(uri: Uri): String? = runCatching {
        context.contentResolver
            .query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0) else null
            }
    }.getOrNull()

    /**
     * The picker's display name is the best source — it is what the user sees
     * in their file manager — but it is not guaranteed to carry an extension,
     * and playback needs one to pick a demuxer.
     */
    private fun extensionFor(displayName: String?, mime: String, kind: MediaKind): String {
        val fromName = displayName?.substringAfterLast('.', "")?.lowercase()
        if (!fromName.isNullOrBlank() && fromName.length <= 5) return fromName
        MimeTypeMap.getSingleton().getExtensionFromMimeType(mime)?.let { return it }
        return if (kind == MediaKind.AUDIO) "m4a" else "mp4"
    }

    private data class Probe(
        val title: String?,
        val artist: String?,
        val durationMs: Long,
        val artwork: ByteArray?,
    )

    /**
     * Tags first, then a frame. An audio file usually carries cover art and a
     * real title; a video usually carries neither, so a frame a little way in
     * stands in — far enough past the start to miss the black lead-in most
     * encodes open with.
     */
    private fun probe(file: File): Probe {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(file.absolutePath)
            val durationMs = retriever
                .extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toLongOrNull()
                ?.coerceAtLeast(0L)
                ?: 0L
            Probe(
                title = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE)
                    ?.trim()
                    ?.ifBlank { null },
                artist = (
                    retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST)
                        ?: retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUMARTIST)
                    )?.trim()?.ifBlank { null },
                durationMs = durationMs,
                artwork = retriever.embeddedPicture ?: frameJpeg(retriever, durationMs),
            )
        } catch (e: Exception) {
            Log.w(TAG, "could not read metadata from ${file.name}", e)
            Probe(null, null, 0L, null)
        } finally {
            runCatching { retriever.release() }
        }
    }

    private fun frameJpeg(retriever: MediaMetadataRetriever, durationMs: Long): ByteArray? {
        val atUs = if (durationMs > 0) (durationMs * 1000L) / 5 else 0L
        val frame = runCatching {
            retriever.getFrameAtTime(atUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
        }.getOrNull() ?: return null
        return java.io.ByteArrayOutputStream().use { out ->
            frame.compress(Bitmap.CompressFormat.JPEG, 85, out)
            frame.recycle()
            out.toByteArray()
        }
    }

    private fun writeThumbnail(dir: File, id: String, bytes: ByteArray): File? = runCatching {
        File(dir, "$id.jpg").apply { writeBytes(bytes) }
    }.getOrNull()

    private companion object {
        const val TAG = "LocalImporter"

        /** Marks an item that came from the device rather than from YouTube. */
        const val LOCAL_PREFIX = "loc"
    }
}
