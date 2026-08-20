package dev.hanada.tubevault.core

/** Recognising a video the browser happens to be sitting on. */
object YouTubeUrls {

    const val MOBILE_HOME = "https://m.youtube.com/"

    private val VIDEO_ID = Regex("[A-Za-z0-9_-]{11}")

    private val PATTERNS = listOf(
        Regex("""[?&]v=([A-Za-z0-9_-]{11})"""),
        Regex("""youtu\.be/([A-Za-z0-9_-]{11})"""),
        Regex("""/shorts/([A-Za-z0-9_-]{11})"""),
        Regex("""/embed/([A-Za-z0-9_-]{11})"""),
        Regex("""/live/([A-Za-z0-9_-]{11})"""),
    )

    /** The video id [url] points at, or null when it is a feed, channel, ... */
    fun videoId(url: String?): String? {
        if (url.isNullOrBlank()) return null
        for (pattern in PATTERNS) {
            val id = pattern.find(url)?.groupValues?.getOrNull(1)
            if (id != null && VIDEO_ID.matches(id)) return id
        }
        return null
    }

    fun isYouTube(url: String?): Boolean {
        val host = runCatching { android.net.Uri.parse(url).host }.getOrNull() ?: return false
        return host.endsWith("youtube.com") || host.endsWith("youtu.be") || host.endsWith("youtube-nocookie.com")
    }

    fun watchUrl(videoId: String): String = "https://www.youtube.com/watch?v=$videoId"
}
