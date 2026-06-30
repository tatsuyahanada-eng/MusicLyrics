package com.netdiag.ui.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import com.netdiag.core.wifi.WifiAp

// Distinguishable bright hues for overlapping access-point curves.
private val spectrumPalette = listOf(
    Color(0xFF00FF66), Color(0xFF00E5FF), Color(0xFFFF2EA6), Color(0xFFFFD400),
    Color(0xFFFF7A00), Color(0xFF9D7BFF), Color(0xFF53FF53), Color(0xFFFF5C7A),
)

/**
 * The classic "Wi-Fi analyzer" view: each access point is drawn as a dome
 * centred on its channel, as tall as its signal strength and as wide as its
 * channel width. Overlapping domes reveal channel congestion at a glance.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun WifiSpectrum(aps: List<WifiAp>) {
    val byBand = aps.filter { it.channel > 0 }.groupBy { it.band }
    // Render the common bands in a stable order.
    listOf("2.4GHz", "5GHz", "6GHz").forEach { band ->
        val bandAps = byBand[band] ?: return@forEach
        if (bandAps.isEmpty()) return@forEach
        com.netdiag.ui.SectionCard("電波波形 — $band") {
            BandSpectrumCanvas(bandAps)
            Spacer(Modifier.height(10.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                bandAps.sortedByDescending { it.rssi }.forEachIndexed { index, ap ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            Modifier
                                .size(10.dp)
                                .clip(CircleShape)
                                .background(spectrumPalette[index % spectrumPalette.size])
                        )
                        Spacer(Modifier.width(4.dp))
                        Text(
                            "${ap.ssid} ch${ap.channel} ${ap.rssi}dBm",
                            style = MaterialTheme.typography.labelSmall,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun BandSpectrumCanvas(aps: List<WifiAp>) {
    val sorted = aps.sortedByDescending { it.rssi }
    val onSurface = MaterialTheme.colorScheme.onSurfaceVariant
    val gridColor = MaterialTheme.colorScheme.surfaceVariant

    // Channel domain with a little padding on each side.
    val channels = sorted.map { it.channel }
    val minCh = (channels.min() - 2).coerceAtLeast(0)
    val maxCh = (channels.max() + 2)
    val span = (maxCh - minCh).coerceAtLeast(1).toFloat()

    Canvas(
        Modifier
            .fillMaxWidth()
            .height(180.dp)
    ) {
        val w = size.width
        val h = size.height
        val baseline = h - 18f
        val top = 8f
        val usableH = baseline - top

        // baseline + a few channel grid ticks
        drawLine(gridColor, Offset(0f, baseline), Offset(w, baseline), strokeWidth = 1.5f)
        val tickStep = if (span > 30) 10 else if (span > 14) 4 else 1
        var ch = minCh
        while (ch <= maxCh) {
            val x = (ch - minCh) / span * w
            drawLine(gridColor, Offset(x, top), Offset(x, baseline), strokeWidth = 0.5f)
            ch += tickStep
        }

        sorted.forEachIndexed { index, ap ->
            val color = spectrumPalette[index % spectrumPalette.size]
            val xCenter = (ap.channel - minCh) / span * w
            val peakFrac = (ap.signalQuality / 100f).coerceIn(0.04f, 1f)
            val peakY = baseline - peakFrac * usableH

            // Channel width -> dome half-width (≈ MHz/5 channels each side).
            val halfChannels = (ap.channelWidthMhz / 10f).coerceAtLeast(1.5f)
            val halfWidthPx = (halfChannels / span * w).coerceIn(18f, w / 2)

            val path = Path().apply {
                moveTo(xCenter - halfWidthPx, baseline)
                // control point chosen so the curve's apex sits at peakY
                quadraticBezierTo(xCenter, 2 * peakY - baseline, xCenter + halfWidthPx, baseline)
            }
            drawPath(path, color.copy(alpha = 0.16f))
            drawPath(path, color, style = Stroke(width = 3f))
        }
    }
}
