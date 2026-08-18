package dev.hanada.tubevault.core

/** What a library entry actually is on disk. */
enum class MediaKind(val label: String) {
    VIDEO("動画"),
    AUDIO("音声"),
}

/** Ceiling passed to yt-dlp's format selector. */
enum class VideoQuality(val label: String, val maxHeight: Int) {
    P360("360p", 360),
    P480("480p", 480),
    P720("720p", 720),
    P1080("1080p", 1080),
    BEST("最高画質", 4320),
}

/** One row of a `ytsearch:` result, before anything is downloaded. */
data class SearchResult(
    val videoId: String,
    val title: String,
    val uploader: String?,
    val durationSec: Long,
    val viewCount: Long,
) {
    val watchUrl: String get() = "https://www.youtube.com/watch?v=$videoId"
    val thumbnailUrl: String get() = "https://i.ytimg.com/vi/$videoId/hqdefault.jpg"
}

fun formatDuration(seconds: Long): String {
    if (seconds <= 0) return "--:--"
    val h = seconds / 3600
    val m = (seconds % 3600) / 60
    val s = seconds % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}

fun formatBytes(bytes: Long): String {
    if (bytes <= 0) return "0 B"
    val units = listOf("B", "KB", "MB", "GB", "TB")
    var value = bytes.toDouble()
    var unit = 0
    while (value >= 1024 && unit < units.lastIndex) {
        value /= 1024
        unit++
    }
    return if (unit == 0) "${value.toLong()} ${units[unit]}" else "%.1f %s".format(value, units[unit])
}

fun formatViewCount(count: Long): String = when {
    count <= 0 -> ""
    count >= 100_000_000 -> "%.1f億回視聴".format(count / 100_000_000.0)
    count >= 10_000 -> "%.1f万回視聴".format(count / 10_000.0)
    else -> "${count}回視聴"
}
