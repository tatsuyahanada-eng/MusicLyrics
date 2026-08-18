package dev.hanada.tubevault.data

import android.content.Context
import dev.hanada.tubevault.core.MediaKind
import dev.hanada.tubevault.core.VideoQuality
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Which YouTube client yt-dlp should impersonate. YouTube keeps changing which
 * ones answer anonymous requests, so this stays user-switchable instead of
 * being hard-coded; [AUTO] leaves yt-dlp's own default list alone.
 */
enum class PlayerClient(val label: String, val argument: String?) {
    AUTO("自動", null),
    WEB_EMBEDDED("web_embedded", "web_embedded"),
    TV("tv", "tv"),
    TV_SIMPLY("tv_simply", "tv_simply"),
    MWEB("mweb", "mweb"),
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
    val playerClient: PlayerClient = PlayerClient.AUTO,
    val useCookies: Boolean = true,
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
        val client = prefs.getString(KEY_PLAYER_CLIENT, null)
            ?.let { name -> runCatching { PlayerClient.valueOf(name) }.getOrNull() }
            ?: PlayerClient.AUTO
        return Settings(
            defaultCategoryId = prefs.getLong(KEY_CATEGORY, 0L),
            defaultKind = kind,
            defaultQuality = quality,
            searchLimit = prefs.getInt(KEY_LIMIT, 25),
            wifiOnly = prefs.getBoolean(KEY_WIFI_ONLY, false),
            playerClient = client,
            useCookies = prefs.getBoolean(KEY_USE_COOKIES, true),
        )
    }

    private companion object {
        const val KEY_CATEGORY = "default_category_id"
        const val KEY_KIND = "default_kind"
        const val KEY_QUALITY = "default_quality"
        const val KEY_LIMIT = "search_limit"
        const val KEY_WIFI_ONLY = "wifi_only"
        const val KEY_PLAYER_CLIENT = "player_client"
        const val KEY_USE_COOKIES = "use_cookies"
    }
}
