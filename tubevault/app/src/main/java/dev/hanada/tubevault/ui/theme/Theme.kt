package dev.hanada.tubevault.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/**
 * Grey is the app's signature, so the palette is fixed rather than derived
 * from the device wallpaper — Android 12+ dynamic colour would repaint the
 * background with whatever the user's wallpaper suggests and lose exactly the
 * characteristic this theme is built around.
 *
 * Everything else is a neutral grey ramp with one teal accent, which stays
 * legible against grey in both light and dark without competing with
 * thumbnails.
 */
private val TealAccent = Color(0xFF00796B)
private val TealAccentDark = Color(0xFF4DD9C5)

private val LightColors = lightColorScheme(
    primary = TealAccent,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFB8EDE4),
    onPrimaryContainer = Color(0xFF00201B),

    secondary = Color(0xFF4A6360),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFCCE8E3),
    onSecondaryContainer = Color(0xFF06201C),

    tertiary = Color(0xFF44617D),
    onTertiary = Color.White,

    // The greys: a deliberately flat, slightly cool ramp.
    background = Color(0xFFE3E5E7),
    onBackground = Color(0xFF1A1C1E),
    surface = Color(0xFFEDEFF1),
    onSurface = Color(0xFF1A1C1E),
    surfaceVariant = Color(0xFFD3D7DA),
    onSurfaceVariant = Color(0xFF4A4E51),

    outline = Color(0xFF7A7F82),
    outlineVariant = Color(0xFFC2C6C9),

    error = Color(0xFFB3261E),
    onError = Color.White,
)

private val DarkColors = darkColorScheme(
    primary = TealAccentDark,
    onPrimary = Color(0xFF00382F),
    primaryContainer = Color(0xFF005046),
    onPrimaryContainer = Color(0xFFB8EDE4),

    secondary = Color(0xFFB0CCC7),
    onSecondary = Color(0xFF1C3531),
    secondaryContainer = Color(0xFF334B48),
    onSecondaryContainer = Color(0xFFCCE8E3),

    tertiary = Color(0xFFACCAE9),
    onTertiary = Color(0xFF13334E),

    background = Color(0xFF1B1D1F),
    onBackground = Color(0xFFE2E3E5),
    surface = Color(0xFF232629),
    onSurface = Color(0xFFE2E3E5),
    surfaceVariant = Color(0xFF33373A),
    onSurfaceVariant = Color(0xFFC2C6C9),

    outline = Color(0xFF8C9194),
    outlineVariant = Color(0xFF44484B),

    error = Color(0xFFF2B8B5),
    onError = Color(0xFF601410),
)

/** Softer, rounder than the Material defaults, so controls read as pills. */
private val AppShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(22.dp),
    extraLarge = RoundedCornerShape(30.dp),
)

@Composable
fun TubeVaultTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        shapes = AppShapes,
        content = content,
    )
}
