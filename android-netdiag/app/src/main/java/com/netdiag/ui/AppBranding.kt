package com.netdiag.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.min

/**
 * The app glyph — a wireframe globe (network) crossed by a magnifying glass
 * (scope), matching the launcher icon (see ic_launcher_foreground.xml). Drawn
 * with Canvas so it stays crisp at any size and follows the theme's accent.
 */
@Composable
fun NetScopeLogo(modifier: Modifier = Modifier, size: Dp = 40.dp) {
    val globeOuter = Color(0xFF7CFFB2)
    val globeLine = Color(0xFF22C55E)
    val node = Color(0xFFEAFFF3)
    val lensRim = Color(0xFF39FF6A)
    val lensGlass = MaterialTheme.colorScheme.background

    Canvas(modifier.size(size)) {
        val s = min(this.size.width, this.size.height) / 108f
        fun p(x: Float, y: Float) = Offset(x * s, y * s)

        // Globe: outer circle + vertical meridian + two bulging arcs + equator.
        drawCircle(globeOuter, radius = 18f * s, center = p(44f, 46f),
            style = Stroke(width = 3f * s, cap = StrokeCap.Round))
        drawLine(globeLine, p(44f, 28f), p(44f, 64f), strokeWidth = 2.2f * s, cap = StrokeCap.Round)
        drawLine(globeLine, p(26f, 46f), p(62f, 46f), strokeWidth = 2.2f * s, cap = StrokeCap.Round)
        // Narrow meridian ellipses, approximated as thin ovals via scale.
        drawOval(
            color = globeLine,
            topLeft = p(35f, 28f),
            size = androidx.compose.ui.geometry.Size(18f * s, 36f * s),
            style = Stroke(width = 2.2f * s),
        )

        // Network nodes.
        drawCircle(node, radius = 2.2f * s, center = p(44f, 28f))
        drawCircle(node, radius = 2.2f * s, center = p(28f, 38f))
        drawCircle(node, radius = 2.2f * s, center = p(35f, 58f))

        // Magnifying glass: dark lens with a bright rim, plus a handle.
        drawCircle(lensGlass, radius = 10f * s, center = p(68f, 68f))
        drawCircle(lensRim, radius = 10f * s, center = p(68f, 68f), style = Stroke(width = 4f * s))
        drawLine(lensRim, p(75.5f, 75.5f), p(85f, 85f), strokeWidth = 5f * s, cap = StrokeCap.Round)
    }
}

/**
 * App identity block for the TOP screen: logo + "NetScope" wordmark. Kept
 * separate from the terminal top bar so the brand mark shows once, prominently,
 * on the home tab rather than in every section header.
 */
@Composable
fun NetScopeHeader(modifier: Modifier = Modifier) {
    Row(
        modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        NetScopeLogo(size = 44.dp)
        Spacer(Modifier.width(12.dp))
        Column {
            Text(
                "NetScope",
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                fontSize = 22.sp,
                color = MaterialTheme.colorScheme.primary,
            )
            Text(
                "ネットワーク調査ツール",
                fontFamily = FontFamily.Monospace,
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
