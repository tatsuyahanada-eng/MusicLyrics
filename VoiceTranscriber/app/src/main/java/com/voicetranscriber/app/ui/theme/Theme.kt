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

// アプリアイコンの配色をそのままテーマに落とし込む。
// ブルー（#1E7BF0）→ ディープブルー（#1139CF）→ マゼンタ → レッド（#E0242E）、
// 差し色にアイコンの電波のシアン（#4FD2F7）。
private val LightColors = lightColorScheme(
    primary = Color(0xFF1656DC),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFDCE8FF),
    onPrimaryContainer = Color(0xFF0B2A6B),
    secondary = Color(0xFF0E96C4),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFD5F1FC),
    onSecondaryContainer = Color(0xFF07364A),
    tertiary = Color(0xFFD32330),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFFFDBDC),
    onTertiaryContainer = Color(0xFF63080F),
    error = Color(0xFFC62330),
    onError = Color(0xFFFFFFFF),
    background = Color(0xFFF1F5FD),
    onBackground = Color(0xFF141C30),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF141C30),
    surfaceVariant = Color(0xFFE6EDFB),
    onSurfaceVariant = Color(0xFF5A6B8C),
    outline = Color(0xFFBFD0EC),
    outlineVariant = Color(0xFFD9E3F5),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFF79ABFF),
    onPrimary = Color(0xFF07214F),
    primaryContainer = Color(0xFF1B2F63),
    onPrimaryContainer = Color(0xFFD8E5FF),
    secondary = Color(0xFF63D6F5),
    onSecondary = Color(0xFF05303F),
    secondaryContainer = Color(0xFF104254),
    onSecondaryContainer = Color(0xFFCAF0FE),
    tertiary = Color(0xFFFF7C82),
    onTertiary = Color(0xFF52060C),
    tertiaryContainer = Color(0xFF75151C),
    onTertiaryContainer = Color(0xFFFFDBDC),
    error = Color(0xFFFF8A8F),
    onError = Color(0xFF4A0207),
    background = Color(0xFF0A0F1F),
    onBackground = Color(0xFFE6ECFA),
    surface = Color(0xFF141B2F),
    onSurface = Color(0xFFE6ECFA),
    surfaceVariant = Color(0xFF1E2842),
    onSurfaceVariant = Color(0xFF9FB0D0),
    outline = Color(0xFF35446B),
    outlineVariant = Color(0xFF283354),
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
