package com.netdiag.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.font.FontFamily

// Monospace everywhere to reinforce the character-terminal aesthetic.
private val mono = FontFamily.Monospace

val AppTypography: Typography = Typography().run {
    copy(
        displayLarge = displayLarge.copy(fontFamily = mono),
        displayMedium = displayMedium.copy(fontFamily = mono),
        displaySmall = displaySmall.copy(fontFamily = mono),
        headlineLarge = headlineLarge.copy(fontFamily = mono),
        headlineMedium = headlineMedium.copy(fontFamily = mono),
        headlineSmall = headlineSmall.copy(fontFamily = mono),
        titleLarge = titleLarge.copy(fontFamily = mono),
        titleMedium = titleMedium.copy(fontFamily = mono),
        titleSmall = titleSmall.copy(fontFamily = mono),
        bodyLarge = bodyLarge.copy(fontFamily = mono),
        bodyMedium = bodyMedium.copy(fontFamily = mono),
        bodySmall = bodySmall.copy(fontFamily = mono),
        labelLarge = labelLarge.copy(fontFamily = mono),
        labelMedium = labelMedium.copy(fontFamily = mono),
        labelSmall = labelSmall.copy(fontFamily = mono),
    )
}
