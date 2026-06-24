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

// シックな配色: 落ち着いたグラファイト × ミュートしたセージ／真鍮のニュートラルトーン
private val LightColors = lightColorScheme(
    primary = Color(0xFF3D3B47),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFE6E1D8),
    onPrimaryContainer = Color(0xFF2A2832),
    secondary = Color(0xFF4F6F6A),
    onSecondary = Color(0xFFFFFFFF),
    tertiary = Color(0xFF9C7C53),
    error = Color(0xFF9E5B54),
    onError = Color(0xFFFFFFFF),
    background = Color(0xFFF4F1EC),
    onBackground = Color(0xFF2A2832),
    surface = Color(0xFFFBFAF7),
    onSurface = Color(0xFF2A2832),
    surfaceVariant = Color(0xFFE8E4DD),
    onSurfaceVariant = Color(0xFF6B6770),
    outline = Color(0xFFC9C3BA),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFFCFCBD6),
    onPrimary = Color(0xFF2A2832),
    primaryContainer = Color(0xFF3A3742),
    onPrimaryContainer = Color(0xFFE8E4EF),
    secondary = Color(0xFF8FB3AD),
    onSecondary = Color(0xFF16302C),
    tertiary = Color(0xFFC9A877),
    error = Color(0xFFE0938C),
    onError = Color(0xFF3A1512),
    background = Color(0xFF16151A),
    onBackground = Color(0xFFE8E5EC),
    surface = Color(0xFF201E25),
    onSurface = Color(0xFFE8E5EC),
    surfaceVariant = Color(0xFF2E2B34),
    onSurfaceVariant = Color(0xFFB0ABB8),
    outline = Color(0xFF4A4651),
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
