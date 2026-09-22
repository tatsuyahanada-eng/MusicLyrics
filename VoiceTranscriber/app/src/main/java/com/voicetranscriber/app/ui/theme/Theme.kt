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

// アプリ全体をブルー＋オレンジの2色基調で統一する。
// 定型文／メール／音声入力のどのタブでも同じ配色を使う。
private val LightColors = lightColorScheme(
    primary = Color(0xFF1B72E8),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFD9E7FD),
    onPrimaryContainer = Color(0xFF0B3FA8),
    secondary = Color(0xFFE07600),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFFFE6C7),
    onSecondaryContainer = Color(0xFF7A4100),
    tertiary = Color(0xFFFF9E1F),
    onTertiary = Color(0xFF3F2200),
    tertiaryContainer = Color(0xFFFFE6C7),
    onTertiaryContainer = Color(0xFF7A4100),
    error = Color(0xFFC62330),
    onError = Color(0xFFFFFFFF),
    background = Color(0xFFF3F6FC),
    onBackground = Color(0xFF141C2B),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF141C2B),
    surfaceVariant = Color(0xFFE7EDF8),
    onSurfaceVariant = Color(0xFF5B6A83),
    outline = Color(0xFFC3D0E4),
    outlineVariant = Color(0xFFDCE4F0),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFF82B4FF),
    onPrimary = Color(0xFF07224F),
    primaryContainer = Color(0xFF17356E),
    onPrimaryContainer = Color(0xFFD8E6FF),
    secondary = Color(0xFFFFB74D),
    onSecondary = Color(0xFF3F2400),
    secondaryContainer = Color(0xFF5C3900),
    onSecondaryContainer = Color(0xFFFFE6C7),
    tertiary = Color(0xFFFFC97A),
    onTertiary = Color(0xFF3F2200),
    tertiaryContainer = Color(0xFF5C3900),
    onTertiaryContainer = Color(0xFFFFE6C7),
    error = Color(0xFFFF8A8F),
    onError = Color(0xFF4A0207),
    background = Color(0xFF0B1220),
    onBackground = Color(0xFFE6ECF7),
    surface = Color(0xFF141C2E),
    onSurface = Color(0xFFE6ECF7),
    surfaceVariant = Color(0xFF1F2942),
    onSurfaceVariant = Color(0xFFA2B0C8),
    outline = Color(0xFF36456A),
    outlineVariant = Color(0xFF283553),
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
            // タイトルバーが常にブルー背景（BrandBlueDeep 寄り）になったので、
            // ステータスバーもそれに合わせ、アイコンは常に白系（ライトでない）にする。
            window.statusBarColor = 0xFF0B3FA8.toInt()
            WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = false
        }
    }
    MaterialTheme(
        colorScheme = colorScheme,
        content = content,
    )
}
