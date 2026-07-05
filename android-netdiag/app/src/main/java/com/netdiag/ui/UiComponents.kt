package com.netdiag.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

/** High-contrast colour for device/host names so they stand out from the
 *  phosphor body text on the dark background. */
val NameColor: Color = Color(0xFFFFFFFF)

@Composable
fun SectionCard(
    title: String,
    modifier: Modifier = Modifier,
    trailing: @Composable (() -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    Card(modifier = modifier.fillMaxWidth()) {
        Column {
            // Retro window-style title bar: filled accent bar with dark text.
            Row(
                Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.primary)
                    .padding(horizontal = 12.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "▌ $title",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onPrimary,
                )
                trailing?.invoke()
            }
            Column(Modifier.padding(16.dp)) {
                content()
            }
        }
    }
}

@Composable
fun LabeledValue(label: String, value: String, monospace: Boolean = false) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            value,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
            fontFamily = if (monospace) FontFamily.Monospace else FontFamily.Default,
        )
    }
}

/** Horizontal progress/strength bar with a 0..1 fraction. */
@Composable
fun MeterBar(fraction: Float, color: Color, modifier: Modifier = Modifier) {
    Box(
        modifier
            .height(8.dp)
            .clip(RoundedCornerShape(4.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Box(
            Modifier
                .fillMaxWidth(fraction.coerceIn(0f, 1f))
                .height(8.dp)
                .clip(RoundedCornerShape(4.dp))
                .background(color)
        )
    }
}

/** Small coloured pill used for security / status labels. */
@Composable
fun Tag(text: String, color: Color) {
    Box(
        Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(color.copy(alpha = 0.16f))
            .padding(horizontal = 8.dp, vertical = 2.dp)
    ) {
        Text(text, color = color, style = MaterialTheme.typography.labelMedium)
    }
}

@Composable
fun MonoSmall(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        fontFamily = FontFamily.Monospace,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

private fun splitOctets(ip: String): List<String> {
    val parts = ip.split(".")
    return (0..3).map { i -> parts.getOrNull(i)?.filter { it.isDigit() } ?: "" }
}

/**
 * IPv4 entry as four numeric boxes (no "." typing). Each box takes up to three
 * digits, so a single digit can be entered zero-padded (e.g. 001), auto-advances
 * to the next box at three digits, and clamps each octet to 0–255. Emits the
 * normalised "a.b.c.d" string (leading zeros stripped).
 */
@Composable
fun OctetIpField(label: String, value: String, onChange: (String) -> Unit) {
    val fields = remember {
        mutableStateListOf(*splitOctets(value).map { TextFieldValue(it) }.toTypedArray())
    }
    var lastValue by remember { mutableStateOf(value) }
    val focus = remember { List(4) { FocusRequester() } }

    // Synchronous external sync: when the incoming value changes (preset /
    // RESET) and it wasn't produced by our own typing, overwrite the boxes.
    // Converges in one extra recomposition because lastValue is updated too.
    if (value != lastValue) {
        lastValue = value
        val parts = splitOctets(value)
        for (i in 0..3) fields[i] = TextFieldValue(parts[i], TextRange(parts[i].length))
    }

    Column {
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(4.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            for (i in 0..3) {
                OutlinedTextField(
                    value = fields[i],
                    onValueChange = { tfv ->
                        var digits = tfv.text.filter { it.isDigit() }.take(3)
                        if ((digits.toIntOrNull() ?: 0) > 255) digits = "255"
                        fields[i] = TextFieldValue(digits, TextRange(digits.length))
                        val combined = fields.joinToString(".") {
                            (it.text.ifEmpty { "0" }).toIntOrNull()?.toString() ?: "0"
                        }
                        lastValue = combined // mark as self-originated
                        onChange(combined)
                        if (digits.length == 3 && i < 3) focus[i + 1].requestFocus()
                    },
                    singleLine = true,
                    textStyle = MaterialTheme.typography.bodyLarge.copy(textAlign = TextAlign.Center),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier
                        .width(64.dp)
                        .focusRequester(focus[i])
                        // Select all on focus so the next digit replaces the box.
                        .onFocusChanged { st ->
                            if (st.isFocused) {
                                val t = fields[i].text
                                fields[i] = fields[i].copy(selection = TextRange(0, t.length))
                            }
                        },
                )
                if (i < 3) {
                    Text(
                        ".",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(horizontal = 4.dp),
                    )
                }
            }
        }
    }
}
