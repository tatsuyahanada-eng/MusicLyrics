package com.netdiag.ui.theme

import android.content.Context
import androidx.compose.ui.graphics.Color

/** Phosphor text colours, in the classic terminal style. */
enum class Accent(val label: String, val bright: Color, val dim: Color) {
    GREEN("蛍光グリーン", Color(0xFF00FF66), Color(0xFF1F9E55)),
    CYAN("蛍光シアン", Color(0xFF00E5FF), Color(0xFF1C8FA3)),
    YELLOW("蛍光イエロー", Color(0xFFFFF200), Color(0xFFA39800)),
}

/** Light background shade behind dark text. */
enum class AppBackground(val label: String, val bg: Color, val surface: Color, val track: Color) {
    WHITE("ホワイト", Color(0xFFFFFFFF), Color(0xFFF3F3F3), Color(0xFFE0E0E0)),
    GRAY("グレー", Color(0xFFD8D8D8), Color(0xFFC9C9C9), Color(0xFFB2B2B2)),
}

data class AppSettings(
    val accent: Accent = Accent.GREEN,
    val background: AppBackground = AppBackground.WHITE,
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
