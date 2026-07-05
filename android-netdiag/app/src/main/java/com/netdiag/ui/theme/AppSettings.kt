package com.netdiag.ui.theme

import android.content.Context
import androidx.compose.ui.graphics.Color

/** Text accent colours (used for body text, highlights and title bars). */
enum class Accent(val label: String, val bright: Color, val dim: Color) {
    GREEN("グリーン", Color(0xFF00FF66), Color(0xFF1F9E55)),
    CYAN("シアン", Color(0xFF00E5FF), Color(0xFF1C8FA3)),
    YELLOW("イエロー", Color(0xFFFFF200), Color(0xFFA39800)),
    PINK("ピンク", Color(0xFFFF3DAE), Color(0xFFB02E77)),
}

/** Dark background shade behind the phosphor text (kept clearly distinct). */
enum class AppBackground(val label: String, val bg: Color, val surface: Color, val track: Color) {
    BLACK("ブラック", Color(0xFF000000), Color(0xFF101010), Color(0xFF242424)),
    GRAY("グレー", Color(0xFF3A3A3A), Color(0xFF484848), Color(0xFF5E5E5E)),
}

data class AppSettings(
    val accent: Accent = Accent.GREEN,
    val background: AppBackground = AppBackground.BLACK,
)

/** Persists the look-and-feel choice in SharedPreferences (no extra deps). */
class SettingsRepository(context: Context) {
    private val prefs = context.applicationContext
        .getSharedPreferences("netdiag_settings", Context.MODE_PRIVATE)

    fun load(): AppSettings {
        val accent = runCatching { Accent.valueOf(prefs.getString(KEY_ACCENT, null) ?: "") }
            .getOrDefault(Accent.GREEN)
        val bg = runCatching { AppBackground.valueOf(prefs.getString(KEY_BG, null) ?: "") }
            .getOrDefault(AppBackground.BLACK)
        return AppSettings(accent, bg)
    }

    fun save(settings: AppSettings) {
        prefs.edit()
            .putString(KEY_ACCENT, settings.accent.name)
            .putString(KEY_BG, settings.background.name)
            .apply()
    }

    private companion object {
        const val KEY_ACCENT = "accent"
        const val KEY_BG = "background"
    }
}
