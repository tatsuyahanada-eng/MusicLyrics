package com.netdiag.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/**
 * Dark "terminal" theme: a black / gray background with phosphor text. Dynamic
 * (Material You) colours are disabled so the retro look stays consistent.
 */
@Composable
fun NetDiagTheme(
    settings: AppSettings,
    content: @Composable () -> Unit,
) {
    val accent = settings.accent
    val bg = settings.background
    val colorScheme = darkColorScheme(
        primary = accent.bright,
        onPrimary = Color.Black,
        secondary = accent.bright,
        onSecondary = Color.Black,
        tertiary = accent.dim,
        background = bg.bg,
        onBackground = accent.bright,
        surface = bg.surface,
        onSurface = accent.bright,
        surfaceVariant = bg.track,
        onSurfaceVariant = accent.dim,
        outline = accent.dim,
        error = Color(0xFFFF5555),
        onError = Color.Black,
    )
    MaterialTheme(
        colorScheme = colorScheme,
        typography = AppTypography,
        content = content,
    )
}
