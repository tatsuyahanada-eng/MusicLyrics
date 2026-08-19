package dev.hanada.tubevault.data

import android.content.Context
import dev.hanada.tubevault.core.MediaKind
import dev.hanada.tubevault.core.VideoQuality
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Which YouTube client(s) yt-dlp should impersonate.
 *
 * YouTube requires a "PO Token" (proof of origin, minted by BotGuard/DroidGuard)
 * from most clients, and without one extraction fails with "Please sign in" —
 * which is misleading, because cookies and PO Tokens are unrelated and logging
 * in does not satisfy it. yt-dlp declares the requirement per client, and a few
 * clients still carry no requirement at all, so [NO_POT] lists those and is the
 * default. The single-client entries stay for when YouTube moves the goalposts
 * again; a comma-separated value makes yt-dlp try them in order.
 */
enum class PlayerClient(val label: String, val argument: String?) {
    NO_POT("トークン不要を優先", "web_embedded,tv_simply,mweb"),
    AUTO("yt-dlp の既定", null),
    WEB_EMBEDDED("web_embedded", "web_embedded"),
    TV_SIMPLY("tv_simply", "tv_simply"),
    MWEB("mweb", "mweb"),
    TV("tv", "tv"),
    IOS("ios", "ios"),
    ANDROID_VR("android_vr", "android_vr"),
    WEB_SAFARI("web_safari", "web_safari"),
}

data class Settings(
    val defaultCategoryId: Long = 0L,
    val defaultKind: MediaKind = MediaKind.VIDEO,
    val defaultQuality: VideoQuality = VideoQuality.P720,
    val searchLimit: Int = 25,
    val wifiOnly: Boolean = false,
    val playerClient: PlayerClient = PlayerClient.NO_POT,
    val useCookies: Boolean = true,
    /** When yt-dlp itself was last refreshed, so startup can keep it current. */
    val ytDlpUpdatedAt: Long = 0L,
)

/** Small enough that SharedPreferences beats pulling in DataStore. */
class SettingsStore(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences("tubevault_settings", Context.MODE_PRIVATE)

    private val _state = MutableStateFlow(read())
    val state: StateFlow<Settings> = _state.asStateFlow()

    val current: Settings get() = _state.value

    fun update(transform: (Settings) -> Settings) {
        val next = transform(_state.value)
        prefs.edit()
            .putLong(KEY_CATEGORY, next.defaultCategoryId)
            .putString(KEY_KIND, next.defaultKind.name)
            .putString(KEY_QUALITY, next.defaultQuality.name)
            .putInt(KEY_LIMIT, next.searchLimit)
            .putBoolean(KEY_WIFI_ONLY, next.wifiOnly)
            .putString(KEY_PLAYER_CLIENT, next.playerClient.name)
            .putBoolean(KEY_USE_COOKIES, next.useCookies)
            .putLong(KEY_YTDLP_UPDATED_AT, next.ytDlpUpdatedAt)
            .putInt(KEY_VERSION, CURRENT_VERSION)
            .apply()
        _state.value = next
    }

    private fun read(): Settings {
        val kind = prefs.getString(KEY_KIND, null)
            ?.let { name -> runCatching { MediaKind.valueOf(name) }.getOrNull() }
            ?: MediaKind.VIDEO
        val quality = prefs.getString(KEY_QUALITY, null)
            ?.let { name -> runCatching { VideoQuality.valueOf(name) }.getOrNull() }
            ?: VideoQuality.P720

        // Installs from before the PO Token findings have "AUTO" persisted from
        // when that was the default. They are exactly the ones hitting
        // "Please sign in", so move them onto the new default once.
        val storedVersion = prefs.getInt(KEY_VERSION, 1)
        val client = if (storedVersion < CURRENT_VERSION) {
            PlayerClient.NO_POT
        } else {
            prefs.getString(KEY_PLAYER_CLIENT, null)
                ?.let { name -> runCatching { PlayerClient.valueOf(name) }.getOrNull() }
                ?: PlayerClient.NO_POT
        }

        return Settings(
            defaultCategoryId = prefs.getLong(KEY_CATEGORY, 0L),
            defaultKind = kind,
            defaultQuality = quality,
            searchLimit = prefs.getInt(KEY_LIMIT, 25),
            wifiOnly = prefs.getBoolean(KEY_WIFI_ONLY, false),
            playerClient = client,
            useCookies = prefs.getBoolean(KEY_USE_COOKIES, true),
            ytDlpUpdatedAt = prefs.getLong(KEY_YTDLP_UPDATED_AT, 0L),
        )
    }

    private companion object {
        /** Bumped when a default changes in a way existing installs need. */
        const val CURRENT_VERSION = 2

        const val KEY_VERSION = "settings_version"
        const val KEY_CATEGORY = "default_category_id"
        const val KEY_KIND = "default_kind"
        const val KEY_QUALITY = "default_quality"
        const val KEY_LIMIT = "search_limit"
        const val KEY_WIFI_ONLY = "wifi_only"
        const val KEY_PLAYER_CLIENT = "player_client"
        const val KEY_USE_COOKIES = "use_cookies"
        const val KEY_YTDLP_UPDATED_AT = "ytdlp_updated_at"
    }
}
