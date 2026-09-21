package com.voicetranscriber.app.ui.theme

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

// アプリアイコンの配色（フェニックス・サンズ風：ディープパープル × サンオレンジ）を
// そのままテーマに落とし込む。
private val LightColors = lightColorScheme(
    primary = Color(0xFF4A1F94),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFE7DCFB),
    onPrimaryContainer = Color(0xFF230A56),
    secondary = Color(0xFFE07A00),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFFFE3BD),
    onSecondaryContainer = Color(0xFF592E00),
    tertiary = Color(0xFFFF9E1F),
    onTertiary = Color(0xFF3A1D00),
    tertiaryContainer = Color(0xFFFFE3BD),
    onTertiaryContainer = Color(0xFF592E00),
    error = Color(0xFFC62330),
    onError = Color(0xFFFFFFFF),
    background = Color(0xFFF6F2FC),
    onBackground = Color(0xFF201238),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF201238),
    surfaceVariant = Color(0xFFEBE2F8),
    onSurfaceVariant = Color(0xFF6B5C87),
    outline = Color(0xFFCBBCE6),
    outlineVariant = Color(0xFFE2D8F2),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFFC7A9FF),
    onPrimary = Color(0xFF2E1063),
    primaryContainer = Color(0xFF3A1680),
    onPrimaryContainer = Color(0xFFEADFFF),
    secondary = Color(0xFFFFB74D),
    onSecondary = Color(0xFF3F2400),
    secondaryContainer = Color(0xFF5C3900),
    onSecondaryContainer = Color(0xFFFFE3BD),
    tertiary = Color(0xFFFFC97A),
    onTertiary = Color(0xFF3A1D00),
    tertiaryContainer = Color(0xFF5C3900),
    onTertiaryContainer = Color(0xFFFFE3BD),
    error = Color(0xFFFF8A8F),
    onError = Color(0xFF4A0207),
    background = Color(0xFF120826),
    onBackground = Color(0xFFEAE3F7),
    surface = Color(0xFF1C1036),
    onSurface = Color(0xFFEAE3F7),
    surfaceVariant = Color(0xFF2C1E4B),
    onSurfaceVariant = Color(0xFFC2B3DC),
    outline = Color(0xFF4E3D74),
    outlineVariant = Color(0xFF362858),
)

@Composable
fun VoiceTranscriberTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val colorScheme = if (darkTheme) DarkColors else LightColors
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            window.statusBarColor = colorScheme.background.toArgb()
            WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = !darkTheme
        }
    }
    MaterialTheme(
        colorScheme = colorScheme,
        content = content,
    )
}
