package dev.hanada.tubevault.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

private val BrandPurple = Color(0xFF7B2FF7)
private val BrandPurpleLight = Color(0xFFCBB2FF)

private val LightColors = lightColorScheme(
    primary = BrandPurple,
    secondary = Color(0xFF5B4B8A),
    tertiary = Color(0xFF00838F),
)

private val DarkColors = darkColorScheme(
    primary = BrandPurpleLight,
    secondary = Color(0xFFC7BFE8),
    tertiary = Color(0xFF6FD8E6),
)

@Composable
fun TubeVaultTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)

        darkTheme -> DarkColors
        else -> LightColors
    }

    MaterialTheme(colorScheme = colorScheme, content = content)
}
