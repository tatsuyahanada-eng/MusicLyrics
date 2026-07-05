package com.netdiag.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/**
 * Light theme: a white / gray background with dark, readable text. The chosen
 * accent (green / cyan / yellow) is used for title bars, buttons and
 * highlights via a darker shade so it stays legible on a light surface.
 */
@Composable
fun NetDiagTheme(
    settings: AppSettings,
    content: @Composable () -> Unit,
) {
    val accent = settings.accent
    val bg = settings.background
    val onLight = Color(0xFF1B1B1B)
    val colorScheme = lightColorScheme(
        primary = accent.dim,
        onPrimary = Color.White,
        secondary = accent.dim,
        onSecondary = Color.White,
        tertiary = accent.dim,
        background = bg.bg,
        onBackground = onLight,
        surface = bg.surface,
        onSurface = onLight,
        surfaceVariant = bg.track,
        onSurfaceVariant = Color(0xFF5A5A5A),
        outline = Color(0xFF9A9A9A),
        error = Color(0xFFC0392B),
        onError = Color.White,
    )
    MaterialTheme(
        colorScheme = colorScheme,
        typography = AppTypography,
        content = content,
    )
}
