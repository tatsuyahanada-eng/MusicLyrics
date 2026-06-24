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

// おしゃれな配色: 上品なバイオレット × ティール／コーラルのアクセント
private val LightColors = lightColorScheme(
    primary = Color(0xFF6C5CE7),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFE7E2FF),
    onPrimaryContainer = Color(0xFF21134E),
    secondary = Color(0xFF00B5A5),
    onSecondary = Color(0xFFFFFFFF),
    tertiary = Color(0xFFFF6B8A),
    error = Color(0xFFFF5A7A),
    onError = Color(0xFFFFFFFF),
    background = Color(0xFFF5F3FC),
    onBackground = Color(0xFF1C1A2B),
    surface = Color(0xFFFCFBFF),
    onSurface = Color(0xFF1C1A2B),
    surfaceVariant = Color(0xFFEDE9F7),
    onSurfaceVariant = Color(0xFF6A6582),
    outline = Color(0xFFC7C1DC),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFFB1A3FF),
    onPrimary = Color(0xFF291A5E),
    primaryContainer = Color(0xFF433377),
    onPrimaryContainer = Color(0xFFE7E2FF),
    secondary = Color(0xFF4FD8C8),
    onSecondary = Color(0xFF003731),
    tertiary = Color(0xFFFF92A8),
    error = Color(0xFFFF7E97),
    onError = Color(0xFF3A0011),
    background = Color(0xFF131120),
    onBackground = Color(0xFFEAE7F5),
    surface = Color(0xFF1D1B2C),
    onSurface = Color(0xFFEAE7F5),
    surfaceVariant = Color(0xFF302C44),
    onSurfaceVariant = Color(0xFFB7B1CC),
    outline = Color(0xFF4B4763),
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
