package com.netdiag.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.netdiag.ui.SectionCard
import com.netdiag.ui.theme.Accent
import com.netdiag.ui.theme.AppBackground
import com.netdiag.ui.theme.AppSettings

@Composable
fun SettingsScreen(settings: AppSettings, onChange: (AppSettings) -> Unit) {
    LazyColumn(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 12.dp),
    ) {
        item {
            SectionCard("文字色（蛍光）") {
                Accent.entries.forEach { accent ->
                    OptionRow(
                        selected = settings.accent == accent,
                        onClick = { onChange(settings.copy(accent = accent)) },
                        swatch = accent.bright,
                        label = accent.label,
                    )
                }
            }
        }
        item {
            SectionCard("背景色") {
                AppBackground.entries.forEach { bg ->
                    OptionRow(
                        selected = settings.background == bg,
                        onClick = { onChange(settings.copy(background = bg)) },
                        swatch = bg.bg,
                        label = bg.label,
                        bordered = true,
                    )
                }
            }
        }
        item {
            SectionCard("プレビュー") {
                Text("user@netdiag:~\$ scan --subnet", style = MaterialTheme.typography.bodyMedium)
                Text("> 192.168.1.1  gateway", style = MaterialTheme.typography.bodyMedium)
                Text("> 192.168.1.23 PC-TANAKA", style = MaterialTheme.typography.bodyMedium)
                Text("[OK] 12 hosts up", style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun OptionRow(
    selected: Boolean,
    onClick: () -> Unit,
    swatch: androidx.compose.ui.graphics.Color,
    label: String,
    bordered: Boolean = false,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = onClick)
        Spacer(Modifier.width(8.dp))
        Box(
            Modifier
                .size(20.dp)
                .clip(CircleShape)
                .background(swatch)
                .then(
                    if (bordered) Modifier.border(1.dp, MaterialTheme.colorScheme.outline, CircleShape)
                    else Modifier
                )
        )
        Spacer(Modifier.width(12.dp))
        Text(label, style = MaterialTheme.typography.bodyLarge)
    }
}
