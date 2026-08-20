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
 * The greys are a ramp rather than a single value. Material's container tiers
 * are what let a card, a control bar and a dialog read as different depths
 * without any of them resorting to a colour of its own, which is what keeps
 * the whole app looking like one surface with things sitting on it.
 *
 * One teal accent carries every interactive element, matching the launcher
 * icon, and is the only saturated colour in the app apart from folder swatches.
 */
private val Teal = Color(0xFF00796B)
private val TealDark = Color(0xFF4DD9C5)

private val LightColors = lightColorScheme(
    primary = Teal,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFB4EBE1),
    onPrimaryContainer = Color(0xFF00201B),

    secondary = Color(0xFF4A6360),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFCCE8E3),
    onSecondaryContainer = Color(0xFF06201C),

    tertiary = Color(0xFF44617D),
    onTertiary = Color.White,

    background = Color(0xFFE4E6E8),
    onBackground = Color(0xFF16181A),
    surface = Color(0xFFE4E6E8),
    onSurface = Color(0xFF16181A),
    surfaceVariant = Color(0xFFD2D6D9),
    onSurfaceVariant = Color(0xFF4B4F52),

    surfaceContainerLowest = Color(0xFFFBFCFC),
    surfaceContainerLow = Color(0xFFEEF0F2),
    surfaceContainer = Color(0xFFE8EAEC),
    surfaceContainerHigh = Color(0xFFDFE2E4),
    surfaceContainerHighest = Color(0xFFD7DADD),

    outline = Color(0xFF797E81),
    outlineVariant = Color(0xFFC0C4C7),

    error = Color(0xFFB3261E),
    onError = Color.White,
    errorContainer = Color(0xFFF9DEDC),
    onErrorContainer = Color(0xFF410E0B),
)

private val DarkColors = darkColorScheme(
    primary = TealDark,
    onPrimary = Color(0xFF00382F),
    primaryContainer = Color(0xFF005046),
    onPrimaryContainer = Color(0xFFB4EBE1),

    secondary = Color(0xFFB0CCC7),
    onSecondary = Color(0xFF1C3531),
    secondaryContainer = Color(0xFF334B48),
    onSecondaryContainer = Color(0xFFCCE8E3),

    tertiary = Color(0xFFACCAE9),
    onTertiary = Color(0xFF13334E),

    background = Color(0xFF17191B),
    onBackground = Color(0xFFE3E4E6),
    surface = Color(0xFF17191B),
    onSurface = Color(0xFFE3E4E6),
    surfaceVariant = Color(0xFF33373A),
    onSurfaceVariant = Color(0xFFC0C4C7),

    surfaceContainerLowest = Color(0xFF101214),
    surfaceContainerLow = Color(0xFF1D2022),
    surfaceContainer = Color(0xFF212427),
    surfaceContainerHigh = Color(0xFF2B2E31),
    surfaceContainerHighest = Color(0xFF35383B),

    outline = Color(0xFF8C9194),
    outlineVariant = Color(0xFF44484B),

    error = Color(0xFFF2B8B5),
    onError = Color(0xFF601410),
    errorContainer = Color(0xFF8C1D18),
    onErrorContainer = Color(0xFFF9DEDC),
)

/** Softer, rounder than the Material defaults, so controls read as pills. */
private val AppShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(18.dp),
    large = RoundedCornerShape(24.dp),
    extraLarge = RoundedCornerShape(32.dp),
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
