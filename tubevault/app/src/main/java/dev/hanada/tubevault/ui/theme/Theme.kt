package dev.hanada.tubevault.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/**
 * One fixed palette, not a light/dark pair. The near-black grey *is* the app's
 * look, so following the system theme would mean half the time it isn't the
 * app the user chose — and dynamic colour from the wallpaper would repaint the
 * background entirely.
 *
 * The greys run as a ramp rather than a single value: Material's container
 * tiers are what let a card, a control bar and a dialog read as different
 * depths of the same black without any of them resorting to a colour. White
 * carries the text, and one warm yellow carries everything interactive —
 * bright enough to be the only thing on screen competing with a thumbnail.
 */
private val Yellow = Color(0xFFFFC93C)
private val Paper = Color(0xFFF2F3F4)

private val AppColors = darkColorScheme(
    primary = Yellow,
    onPrimary = Color(0xFF231A00),
    primaryContainer = Color(0xFF4A3A08),
    onPrimaryContainer = Color(0xFFFFE29A),

    // Plain white, for the controls that should read as neutral next to the
    // yellow ones rather than competing with them.
    secondary = Color(0xFFD8DBDD),
    onSecondary = Color(0xFF2A2C2E),
    secondaryContainer = Color(0xFF303335),
    onSecondaryContainer = Color(0xFFE6E8EA),

    tertiary = Color(0xFFFFE08A),
    onTertiary = Color(0xFF3A2E00),

    background = Color(0xFF0D0E0F),
    onBackground = Paper,
    surface = Color(0xFF0D0E0F),
    onSurface = Paper,
    surfaceVariant = Color(0xFF2C2F31),
    onSurfaceVariant = Color(0xFFA9AEB1),

    surfaceContainerLowest = Color(0xFF08090A),
    surfaceContainerLow = Color(0xFF131415),
    surfaceContainer = Color(0xFF17191A),
    surfaceContainerHigh = Color(0xFF1E2021),
    surfaceContainerHighest = Color(0xFF26282A),

    outline = Color(0xFF6C7174),
    outlineVariant = Color(0xFF3A3D3F),

    error = Color(0xFFFF8A80),
    onError = Color(0xFF3A0A08),
    errorContainer = Color(0xFF5C1A16),
    onErrorContainer = Color(0xFFFFDAD6),

    scrim = Color(0xFF000000),
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
fun TubeVaultTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = AppColors,
        shapes = AppShapes,
        content = content,
    )
}
