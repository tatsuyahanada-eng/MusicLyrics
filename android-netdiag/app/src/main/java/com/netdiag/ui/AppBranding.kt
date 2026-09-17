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
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.min

/**
 * The app glyph — a wireframe globe (network) swept by a radar scan,
 * matching the launcher icon (see ic_launcher_foreground.xml). No
 * magnifying glass: the radar sweep alone reads as "scanning" and keeps
 * every element inside the icon's round safe zone. Drawn with Canvas so it
 * stays crisp at any size and follows the theme's accent.
 */
@Composable
fun NetScopeLogo(modifier: Modifier = Modifier, size: Dp = 40.dp) {
    val bright = Color(0xFF39FF6A)
    val dim = Color(0xFF22C55E)
    val node = Color(0xFFEAFFF3)

    Canvas(modifier.size(size)) {
        val s = min(this.size.width, this.size.height) / 108f
        fun p(x: Float, y: Float) = Offset(x * s, y * s)

        // Globe outline.
        drawCircle(bright, radius = 26f * s, center = p(54f, 54f),
            style = Stroke(width = 5f * s, cap = StrokeCap.Round))

        // Meridian cross + narrow depth arcs either side of centre.
        drawLine(dim, p(54f, 28f), p(54f, 80f), strokeWidth = 2.6f * s, cap = StrokeCap.Round)
        drawLine(dim, p(28f, 54f), p(80f, 54f), strokeWidth = 2.6f * s, cap = StrokeCap.Round)
        val depthBoundsTopLeft = p(41f, 28f)
        val depthBoundsSize = Size(26f * s, 52f * s)
        // Right half (top -> bottom via the right edge) and left half (bottom -> top
        // via the left edge); together they trace a full narrow ellipse.
        drawArc(dim, startAngle = -90f, sweepAngle = 180f, useCenter = false,
            topLeft = depthBoundsTopLeft, size = depthBoundsSize,
            style = Stroke(width = 2.2f * s, cap = StrokeCap.Round))
        drawArc(dim, startAngle = 90f, sweepAngle = 180f, useCenter = false,
            topLeft = depthBoundsTopLeft, size = depthBoundsSize,
            style = Stroke(width = 2.2f * s, cap = StrokeCap.Round))

        // Radar scan: fading quarter wedge + bright sweep line + origin dot.
        val boundsTopLeft = p(28f, 28f)
        val boundsBottomRight = p(80f, 80f)
        val wedge = Path().apply {
            moveTo(p(54f, 54f).x, p(54f, 54f).y)
            lineTo(p(54f, 28f).x, p(54f, 28f).y)
            arcTo(
                rect = androidx.compose.ui.geometry.Rect(
                    boundsTopLeft.x, boundsTopLeft.y, boundsBottomRight.x, boundsBottomRight.y,
                ),
                startAngleDegrees = -90f, sweepAngleDegrees = 90f, forceMoveTo = false,
            )
            close()
        }
        drawPath(wedge, bright, alpha = 0.20f)
        drawLine(bright, p(54f, 54f), p(80f, 54f), strokeWidth = 4f * s, cap = StrokeCap.Round)
        drawCircle(node, radius = 3f * s, center = p(54f, 54f))

        // Detected-device ping nodes.
        listOf(35f to 40f, 40f to 72f).forEach { (x, y) ->
            drawCircle(bright, radius = 3.6f * s, center = p(x, y), alpha = 0.35f)
            drawCircle(node, radius = 2.2f * s, center = p(x, y))
        }
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
