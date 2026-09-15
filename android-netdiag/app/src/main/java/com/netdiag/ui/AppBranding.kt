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
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
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
 * The app glyph — a neon-framed badge holding a wireframe globe (network)
 * crossed by a magnifying glass (scope), matching the launcher icon (see
 * ic_launcher_foreground.xml). Drawn with Canvas so it stays crisp at any
 * size and follows the theme's accent. Bright strokes are drawn twice — a
 * wide low-alpha pass then a crisp pass — to fake a neon glow.
 */
@Composable
fun NetScopeLogo(modifier: Modifier = Modifier, size: Dp = 40.dp) {
    val neon = Color(0xFF39FF6A)
    val frameCrisp = Color(0xFF5CFF9A)
    val globeOuter = Color(0xFF8CFFC2)
    val globeLine = Color(0xFF22C55E)
    val node = Color(0xFFEAFFF3)
    val glassHighlight = Color(0xFFC8FFDD)
    val lensGlass = Color(0xFF050805)

    Canvas(modifier.size(size)) {
        val s = min(this.size.width, this.size.height) / 108f
        fun p(x: Float, y: Float) = Offset(x * s, y * s)

        // Framed badge border (glow, then crisp).
        val frameTopLeft = p(6f, 6f)
        val frameSize = Size(96f * s, 96f * s)
        val corner = CornerRadius(14f * s, 14f * s)
        drawRoundRect(neon, frameTopLeft, frameSize, corner,
            style = Stroke(width = 9f * s), alpha = 0.35f)
        drawRoundRect(frameCrisp, frameTopLeft, frameSize, corner,
            style = Stroke(width = 4.5f * s))

        // Globe: outer circle (glow + crisp), meridians, equator, diagonal link.
        drawCircle(neon, radius = 22f * s, center = p(42f, 44f),
            style = Stroke(width = 7f * s), alpha = 0.30f)
        drawCircle(globeOuter, radius = 22f * s, center = p(42f, 44f),
            style = Stroke(width = 3f * s, cap = StrokeCap.Round))
        drawLine(globeLine, p(42f, 22f), p(42f, 66f), strokeWidth = 2.4f * s, cap = StrokeCap.Round)
        drawLine(globeLine, p(20f, 44f), p(64f, 44f), strokeWidth = 2.4f * s, cap = StrokeCap.Round)
        drawLine(globeLine, p(27f, 30f), p(54f, 58f), strokeWidth = 2f * s, cap = StrokeCap.Round)
        drawOval(
            color = globeLine,
            topLeft = p(31f, 22f),
            size = Size(22f * s, 44f * s),
            style = Stroke(width = 2.4f * s),
        )

        // Network nodes (glow, then crisp).
        listOf(42f to 22f, 27f to 30f, 31f to 56f).forEach { (x, y) ->
            drawCircle(neon, radius = 4f * s, center = p(x, y), alpha = 0.35f)
            drawCircle(node, radius = 2.4f * s, center = p(x, y))
        }

        // Magnifying glass: glow, dark lens, bright rim, glass highlight, handle.
        drawCircle(neon, radius = 14f * s, center = p(75f, 73f), alpha = 0.35f)
        drawCircle(lensGlass, radius = 13f * s, center = p(75f, 73f))
        drawCircle(neon, radius = 13f * s, center = p(75f, 73f), style = Stroke(width = 5f * s))
        drawArc(
            color = glassHighlight,
            startAngle = 200f, sweepAngle = 60f, useCenter = false,
            topLeft = p(65f, 63f), size = Size(20f * s, 20f * s),
            style = Stroke(width = 2f * s, cap = StrokeCap.Round),
        )
        drawLine(neon, p(84.5f, 82.5f), p(100f, 98f), strokeWidth = 10f * s,
            cap = StrokeCap.Round, alpha = 0.35f)
        drawLine(neon, p(84.5f, 82.5f), p(100f, 98f), strokeWidth = 6f * s, cap = StrokeCap.Round)
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
