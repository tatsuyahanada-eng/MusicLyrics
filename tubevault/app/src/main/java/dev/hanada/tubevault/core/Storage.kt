package dev.hanada.tubevault.core

import android.content.Context
import java.io.File

/**
 * Everything lives under the app-specific external directory, so no storage
 * permission is ever needed and uninstalling the app cleans up after itself:
 *
 *   Android/data/dev.hanada.tubevault/files/TubeVault/<category folder>/<videoId>.<ext>
 *
 * Files are named by video id — titles can contain anything, and the display
 * title is kept in the database instead.
 */
object Storage {

    const val ROOT_DIR_NAME = "TubeVault"

    private const val ILLEGAL_CHARS = "\\/:*?\"<>|"
    private val THUMB_EXTENSIONS = setOf("jpg", "jpeg", "png", "webp")

    fun rootDir(context: Context): File {
        val base = context.getExternalFilesDir(null) ?: context.filesDir
        return File(base, ROOT_DIR_NAME).also { it.mkdirs() }
    }

    fun categoryDir(context: Context, folderName: String): File =
        File(rootDir(context), folderName).also { it.mkdirs() }

    /** Keeps Japanese text intact; only replaces what a filesystem cannot store. */
    fun sanitizeFolderName(raw: String): String {
        val cleaned = raw.trim()
            .map { ch -> if (ch.code < 0x20 || ch in ILLEGAL_CHARS) '_' else ch }
            .joinToString("")
            .trim('.', ' ')
            .take(60)
        return cleaned.ifBlank { "category" }
    }

    /** Appends `-2`, `-3`, ... until the name is free among [taken]. */
    fun uniqueFolderName(desired: String, taken: Set<String>): String {
        val base = sanitizeFolderName(desired)
        if (base !in taken) return base
        var n = 2
        while ("$base-$n" in taken) n++
        return "$base-$n"
    }

    /** The media file yt-dlp just wrote for [videoId], ignoring thumbnails and leftovers. */
    fun findMediaFile(dir: File, videoId: String): File? =
        dir.listFiles()
            ?.filter { it.isFile && it.nameWithoutExtension == videoId }
            ?.firstOrNull { it.extension.lowercase() !in THUMB_EXTENSIONS && it.extension != "part" }

    fun findThumbnailFile(dir: File, videoId: String): File? =
        dir.listFiles()?.firstOrNull {
            it.isFile &&
                it.nameWithoutExtension == videoId &&
                it.extension.lowercase() in THUMB_EXTENSIONS
        }

    /** Removes the media file plus any sidecar (thumbnail, subtitles) sharing its id. */
    fun deleteFilesFor(dir: File, videoId: String) {
        dir.listFiles()
            ?.filter { it.isFile && it.name.startsWith("$videoId.") }
            ?.forEach { it.delete() }
    }

    /** Moves every file named after [videoId] into [target]. Returns the new media path. */
    fun moveFilesFor(source: File, target: File, videoId: String): File? {
        target.mkdirs()
        var movedMedia: File? = null
        val files = source.listFiles()?.filter { it.isFile && it.name.startsWith("$videoId.") }.orEmpty()
        for (file in files) {
            val destination = File(target, file.name)
            val ok = file.renameTo(destination) || copyThenDelete(file, destination)
            if (ok && destination.extension.lowercase() !in THUMB_EXTENSIONS) {
                movedMedia = destination
            }
        }
        return movedMedia
    }

    /** `renameTo` fails across mount points; fall back to a copy. */
    private fun copyThenDelete(from: File, to: File): Boolean = runCatching {
        from.copyTo(to, overwrite = true)
        from.delete()
        true
    }.getOrDefault(false)

    fun directorySize(dir: File): Long =
        dir.walkTopDown().filter { it.isFile }.sumOf { it.length() }
}
