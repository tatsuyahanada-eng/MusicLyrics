package dev.hanada.tubevault.lyrics

/**
 * A downloaded video's title is rarely a clean "artist - title" pair — it is
 * whatever the uploader typed, often with bracketed noise ("(Official Video)",
 * "【MV】") around it. This pulls a best-effort artist/title guess out of it
 * for the first lyrics search, before the user ever corrects anything.
 */
object LyricsGuess {
    private val SEPARATORS = listOf(" - ", " – ", " — ", "-")
    private val NOISE = Regex("""[\[(（【][^\])）】]*[\])）】]""")

    fun guess(title: String, uploader: String?): Pair<String, String> {
        for (sep in SEPARATORS) {
            val idx = title.indexOf(sep)
            if (idx <= 0) continue
            val left = title.substring(0, idx).trim()
            val right = clean(title.substring(idx + sep.length))
            if (left.isNotEmpty() && right.isNotEmpty()) return left to right
        }
        val artist = uploader?.trim().orEmpty()
        return artist to clean(title)
    }

    private fun clean(raw: String): String = raw.replace(NOISE, "").trim()
}
