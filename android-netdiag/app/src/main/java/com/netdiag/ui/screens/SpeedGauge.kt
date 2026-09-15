package com.netdiag.ui.screens

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

private const val START = 180f   // 9 o'clock
private const val SWEEP = 180f   // top semicircle, clockwise to 3 o'clock

/**
 * Speedometer-style arc gauge (à la a classic Wi-Fi analyzer meter): a top
 * semicircle whose zones run red → yellow → green as the value rises, with an
 * animated needle. The scale auto-picks a "nice" maximum so the needle stays
 * meaningful from a few Mbps to a gigabit line.
 */
@Composable
fun SpeedGauge(
    label: String,
    mbps: Double,
    accent: Color,
    modifier: Modifier = Modifier,
) {
    val scaleMax = niceMax(mbps)
    val fraction = (mbps / scaleMax).toFloat().coerceIn(0f, 1f)
    val animated by animateFloatAsState(
        targetValue = fraction,
        animationSpec = tween(500),
        label = "needle",
    )

    val trackColor = MaterialTheme.colorScheme.surfaceVariant
    val zoneRed = Color(0xFFD05050)
    val zoneYellow = Color(0xFFE0A422)
    val zoneGreen = Color(0xFF2E9E54)
    val labelColor = MaterialTheme.colorScheme.onSurfaceVariant
    val valueColor = zoneColor(fraction, zoneRed, zoneYellow, zoneGreen)

    val measurer = rememberTextMeasurer()
    val tickStyle = TextStyle(color = labelColor, fontSize = 9.sp, fontFamily = FontFamily.Monospace)

    Column(modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(label, style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold, color = accent)

        Canvas(
            Modifier
                .fillMaxWidth()
                .height(132.dp)
        ) {
            val w = size.width
            val h = size.height
            val pad = 26f
            val radius = min(w / 2f - pad, h - pad)
            val center = Offset(w / 2f, h - 8f)
            val topLeft = Offset(center.x - radius, center.y - radius)
            val arcSize = Size(radius * 2f, radius * 2f)
            val thickness = radius * 0.16f

            // Background track.
            drawArc(
                color = trackColor,
                startAngle = START, sweepAngle = SWEEP, useCenter = false,
                topLeft = topLeft, size = arcSize,
                style = Stroke(width = thickness, cap = StrokeCap.Round),
            )
            // Coloured zones (low→high). Slight alpha so the filled arc reads on top.
            drawArc(zoneRed.copy(alpha = 0.45f), START, SWEEP * 0.40f, false,
                topLeft, arcSize, style = Stroke(width = thickness))
            drawArc(zoneYellow.copy(alpha = 0.45f), START + SWEEP * 0.40f, SWEEP * 0.30f, false,
                topLeft, arcSize, style = Stroke(width = thickness))
            drawArc(zoneGreen.copy(alpha = 0.45f), START + SWEEP * 0.70f, SWEEP * 0.30f, false,
                topLeft, arcSize, style = Stroke(width = thickness))

            // Filled portion up to the needle.
            drawArc(
                color = valueColor,
                startAngle = START, sweepAngle = SWEEP * animated, useCenter = false,
                topLeft = topLeft, size = arcSize,
                style = Stroke(width = thickness, cap = StrokeCap.Round),
            )

            // Tick marks + labels at 0 / ¼ / ½ / ¾ / max.
            for (i in 0..4) {
                val f = i / 4f
                val ang = Math.toRadians((START + SWEEP * f).toDouble())
                val cosA = cos(ang).toFloat()
                val sinA = sin(ang).toFloat()
                val inner = radius - thickness / 2f - 4f
                val outer = radius - thickness / 2f - 14f
                drawLine(
                    labelColor,
                    Offset(center.x + cosA * inner, center.y + sinA * inner),
                    Offset(center.x + cosA * outer, center.y + sinA * outer),
                    strokeWidth = 2f,
                )
                val labelR = radius + 12f
                val lx = center.x + cosA * labelR
                val ly = center.y + sinA * labelR
                val txt = tickLabel(scaleMax * f)
                val layout = measurer.measure(txt, tickStyle)
                drawText(
                    layout,
                    topLeft = Offset(
                        (lx - layout.size.width / 2f).coerceIn(0f, w - layout.size.width),
                        (ly - layout.size.height / 2f).coerceIn(0f, h - layout.size.height),
                    ),
                )
            }

            // Needle.
            val nAng = Math.toRadians((START + SWEEP * animated).toDouble())
            val nCos = cos(nAng).toFloat()
            val nSin = sin(nAng).toFloat()
            val needleLen = radius - thickness - 6f
            drawLine(
                color = valueColor,
                start = Offset(center.x - nCos * 12f, center.y - nSin * 12f),
                end = Offset(center.x + nCos * needleLen, center.y + nSin * needleLen),
                strokeWidth = 4f,
                cap = StrokeCap.Round,
            )
            drawCircle(valueColor, radius = 7f, center = center)
        }

        Text(
            "${fmt1(mbps)} Mbps",
            fontSize = 30.sp,
            fontWeight = FontWeight.Bold,
            color = valueColor,
        )
    }
}

private fun zoneColor(f: Float, red: Color, yellow: Color, green: Color): Color = when {
    f < 0.40f -> red
    f < 0.70f -> yellow
    else -> green
}

/** Smallest "round" ceiling above the value, so the needle never pins at full. */
private fun niceMax(v: Double): Double = when {
    v <= 10 -> 10.0
    v <= 30 -> 30.0
    v <= 50 -> 50.0
    v <= 100 -> 100.0
    v <= 200 -> 200.0
    v <= 300 -> 300.0
    v <= 600 -> 600.0
    else -> 1000.0
}

private fun tickLabel(v: Double): String =
    if (v >= 100) v.toInt().toString() else "%.0f".format(v)

private fun fmt1(v: Double): String = "%.1f".format(v)
