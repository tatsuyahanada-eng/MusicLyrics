package com.netdiag.ui.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.netdiag.core.wifi.WifiAp
import com.netdiag.ui.NameColor
import com.netdiag.ui.SectionCard
import kotlin.math.abs

// Distinguishable bright hues for overlapping access-point curves.
private val spectrumPalette = listOf(
    Color(0xFF00FF66), Color(0xFF00E5FF), Color(0xFFFF2EA6), Color(0xFFFFD400),
    Color(0xFFFF7A00), Color(0xFF9D7BFF), Color(0xFF53FF53), Color(0xFFFF5C7A),
)

/**
 * The classic "Wi-Fi analyzer" view, per band. Tap a curve **or** its name in
 * the legend to highlight that access point and show its details — this makes
 * it easy to tell which dome belongs to which SSID.
 */
@Composable
fun WifiSpectrum(aps: List<WifiAp>) {
    val byBand = aps.filter { it.channel > 0 }.groupBy { it.band }
    listOf("2.4GHz", "5GHz", "6GHz").forEach { band ->
        val bandAps = byBand[band] ?: return@forEach
        if (bandAps.isNotEmpty()) BandSpectrum(band, bandAps)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun BandSpectrum(band: String, aps: List<WifiAp>) {
    val sorted = remember(aps) { aps.sortedByDescending { it.rssi } }
    var selected by remember(aps) { mutableStateOf<Int?>(null) }

    val minCh = remember(sorted) { (sorted.minOf { it.channel } - 2).coerceAtLeast(0) }
    val maxCh = remember(sorted) { sorted.maxOf { it.channel } + 2 }
    val span = (maxCh - minCh).coerceAtLeast(1).toFloat()

    SectionCard("電波波形 — $band") {
        // Selected-AP caption.
        val sel = selected
        if (sel != null && sel in sorted.indices) {
            val ap = sorted[sel]
            Text(
                "選択: ${ap.ssid}  ch${ap.channel} / ${ap.rssi}dBm / ${ap.security}",
                color = spectrumPalette[sel % spectrumPalette.size],
                fontWeight = FontWeight.Bold,
                style = MaterialTheme.typography.bodyMedium,
            )
        } else {
            Text(
                "波形か下の名前をタップすると対応が分かります",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall,
            )
        }
        Spacer(Modifier.height(6.dp))

        SpectrumCanvas(
            sorted = sorted,
            selected = selected,
            minCh = minCh,
            span = span,
            onSelect = { selected = it },
        )

        Spacer(Modifier.height(10.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            sorted.forEachIndexed { index, ap ->
                val isSel = selected == index
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .background(
                            if (isSel) spectrumPalette[index % spectrumPalette.size].copy(alpha = 0.20f)
                            else Color.Transparent
                        )
                        .clickable { selected = if (isSel) null else index }
                        .padding(horizontal = 4.dp, vertical = 2.dp),
                ) {
                    Box(
                        Modifier
                            .size(10.dp)
                            .clip(CircleShape)
                            .background(spectrumPalette[index % spectrumPalette.size])
                    )
                    Spacer(Modifier.width(4.dp))
                    Text(
                        "${ap.ssid} ch${ap.channel}",
                        color = if (isSel) NameColor else MaterialTheme.colorScheme.onSurface,
                        fontWeight = if (isSel) FontWeight.Bold else FontWeight.Normal,
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
            }
        }
    }
}

@Composable
private fun SpectrumCanvas(
    sorted: List<WifiAp>,
    selected: Int?,
    minCh: Int,
    span: Float,
    onSelect: (Int?) -> Unit,
) {
    val gridColor = MaterialTheme.colorScheme.surfaceVariant

    Canvas(
        Modifier
            .fillMaxWidth()
            .height(180.dp)
            .pointerInput(sorted, span) {
                detectTapGestures { pos ->
                    // Select the access point whose peak is nearest the tap X.
                    val w = size.width.toFloat()
                    var best = -1
                    var bestDist = Float.MAX_VALUE
                    sorted.forEachIndexed { i, ap ->
                        val x = (ap.channel - minCh) / span * w
                        val d = abs(x - pos.x)
                        if (d < bestDist) { bestDist = d; best = i }
                    }
                    onSelect(if (best >= 0) best else null)
                }
            }
    ) {
        val w = size.width
        val h = size.height
        val baseline = h - 18f
        val top = 8f
        val usableH = baseline - top

        drawLine(gridColor, Offset(0f, baseline), Offset(w, baseline), strokeWidth = 1.5f)
        val tickStep = if (span > 30) 10 else if (span > 14) 4 else 1
        var ch = minCh
        while (ch <= minCh + span.toInt()) {
            val x = (ch - minCh) / span * w
            drawLine(gridColor, Offset(x, top), Offset(x, baseline), strokeWidth = 0.5f)
            ch += tickStep
        }

        sorted.forEachIndexed { index, ap ->
            val isSel = selected == index
            val dimmed = selected != null && !isSel
            val base = spectrumPalette[index % spectrumPalette.size]
            val color = if (dimmed) base.copy(alpha = 0.35f) else base
            val xCenter = (ap.channel - minCh) / span * w
            val peakFrac = (ap.signalQuality / 100f).coerceIn(0.04f, 1f)
            val peakY = baseline - peakFrac * usableH

            val halfChannels = (ap.channelWidthMhz / 10f).coerceAtLeast(1.5f)
            val halfWidthPx = (halfChannels / span * w).coerceIn(18f, w / 2)

            val path = Path().apply {
                moveTo(xCenter - halfWidthPx, baseline)
                quadraticBezierTo(xCenter, 2 * peakY - baseline, xCenter + halfWidthPx, baseline)
            }
            drawPath(path, color.copy(alpha = if (isSel) 0.30f else 0.14f))
            drawPath(path, color, style = Stroke(width = if (isSel) 6f else 3f))

            if (isSel) {
                drawLine(color, Offset(xCenter, top), Offset(xCenter, baseline), strokeWidth = 1.5f)
            }
        }
    }
}
