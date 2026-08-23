package dev.hanada.tubevault.lyrics

/**
 * Reduces a video title down to the artist and song name a lyrics database
 * indexes by, and nothing else.
 *
 * An uploader's title carries far more than the song: release decoration
 * ("(Official Video)", "【MV】"), subtitles ("〜Album Version〜", "- Live at X"),
 * credits ("feat. …"), and channel furniture ("- Topic", "VEVO"). None of it
 * appears in a lyrics database's fields, and all of it makes a search miss, so
 * every piece is stripped rather than passed along.
 */
object LyricsGuess {

    /** Release decoration: "(Official Video)", "【MV】", "［4K］" … */
    private val BRACKETED = Regex("""[\[(（【〈《［〔][^\])）】〉》］〕]*[\])）】〉》］〕]""")

    /** A bare video-type marker, left behind once its brackets are gone. */
    private val VIDEO_MARKER =
        Regex("""(?i)\s*[-–—]?\s*\b(?:mv|pv|m/v|(?:official\s+)?(?:music|lyric)\s*video)\s*$""")

    /** "Artist「Song」" — Japanese music videos put the song itself in quotes. */
    private val QUOTED_SONG = Regex("""[「『]([^」』]+)[」』]""")

    /**
     * Everything from a subtitle marker onwards: " - Live Version", " / Remix",
     * "〜Album Mix〜". A dash only counts with space around it, so hyphenated
     * names ("Rock-n-Roll") survive.
     */
    private val SUBTITLE = Regex("""(\s+[-–—/|｜]\s+|\s*[~〜]).*$""")

    /** Performer credits — part of the recording, not part of the song's name. */
    private val CREDITS = Regex("""(?i)\s*[-–—]?\s*\b(?:feat\.?|ft\.?|featuring)\s+.*$""")

    /** Channel furniture, only ever found in an uploader name. */
    private val CHANNEL_SUFFIX =
        Regex("""(?i)\s*[-–—]?\s*(topic|vevo|official|records?|music|channel|公式|オフィシャル|チャンネル)\s*$""")

    /**
     * Which half of a split is the artist depends on the separator: a dash
     * reads "artist - song", but a slash or bar is overwhelmingly
     * "曲名 / アーティスト" — song first. Getting this backwards swaps the two
     * fields and the search finds nothing, so each separator carries its own
     * convention rather than sharing one.
     */
    private class Separator(val text: String, val artistIsSecond: Boolean)

    /**
     * All require surrounding space (or are unambiguous full-width forms). A
     * bare "-" is deliberately absent: with nothing to disambiguate it, it
     * splits "Rock-n-Roll Star" into an artist and a song that are both wrong,
     * where falling through to the uploader gets at least the artist right.
     */
    private val SEPARATORS = listOf(
        Separator(" - ", artistIsSecond = false),
        Separator(" – ", artistIsSecond = false),
        Separator(" — ", artistIsSecond = false),
        Separator(" / ", artistIsSecond = true),
        Separator("／", artistIsSecond = true),
        Separator(" | ", artistIsSecond = true),
        Separator("｜", artistIsSecond = true),
    )

    private val WHITESPACE = Regex("""\s+""")

    private const val QUOTES = "\"'「」『』 　"

    fun guess(title: String, uploader: String?): Pair<String, String> {
        val channel = cleanUploader(uploader)

        QUOTED_SONG.find(title)?.let { match ->
            val song = cleanSong(match.groupValues[1])
            if (song.isNotEmpty()) {
                val artist = cleanArtist(title.substring(0, match.range.first))
                return artist.ifEmpty { channel } to song
            }
        }

        for (sep in SEPARATORS) {
            val index = title.indexOf(sep.text)
            if (index <= 0) continue
            val first = title.substring(0, index)
            val second = title.substring(index + sep.text.length)

            // The channel is the surest signal there is about which half names
            // the artist — it usually *is* the artist. Only when neither half
            // resembles it does the separator's convention have to decide.
            val artistIsSecond = when {
                channel.isEmpty() -> sep.artistIsSecond
                namesTheChannel(second, channel) -> true
                namesTheChannel(first, channel) -> false
                else -> sep.artistIsSecond
            }

            val artist = cleanArtist(if (artistIsSecond) second else first)
            val song = cleanSong(if (artistIsSecond) first else second)
            if (artist.isNotEmpty() && song.isNotEmpty()) return artist to song
        }

        return channel to cleanSong(title)
    }

    /**
     * Whether one half of a split looks like the uploader's name. Compared
     * loosely — a channel is "YOASOBI" where the title says "Ayase / YOASOBI",
     * or vice versa — with a floor on length so two short strings do not match
     * on a coincidental substring.
     */
    private fun namesTheChannel(side: String, channel: String): Boolean {
        val a = cleanArtist(side).squashed()
        val b = channel.squashed()
        if (a.isEmpty() || b.isEmpty()) return false
        return a == b || (a.length >= 3 && b.contains(a)) || (b.length >= 3 && a.contains(b))
    }

    private fun String.squashed(): String = lowercase().replace(WHITESPACE, "")

    private fun cleanSong(raw: String): String = raw
        .replace(BRACKETED, " ")
        .replace(CREDITS, "")
        .replace(SUBTITLE, "")
        .tidy()

    /** Left alone apart from decoration: channel furniture never appears here. */
    private fun cleanArtist(raw: String): String = raw
        .replace(BRACKETED, " ")
        .replace(CREDITS, "")
        .replace(VIDEO_MARKER, "")
        .tidy()

    private fun cleanUploader(uploader: String?): String {
        var name = uploader.orEmpty().replace(BRACKETED, " ").tidy()
        // "ArtistVEVO Official Channel" sheds one suffix per pass, so this
        // repeats until the name stops shrinking.
        repeat(3) {
            val trimmed = name.replace(CHANNEL_SUFFIX, "").tidy()
            if (trimmed == name || trimmed.isEmpty()) return name
            name = trimmed
        }
        return name
    }

    private fun String.tidy(): String = replace(WHITESPACE, " ").trim().trim { it in QUOTES }
}
