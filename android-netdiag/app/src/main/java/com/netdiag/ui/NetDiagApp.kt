package com.netdiag.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.DeleteSweep
import androidx.compose.material.icons.outlined.EditNote
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Menu
import androidx.compose.material.icons.outlined.RadioButtonChecked
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Speed
import androidx.compose.material.icons.outlined.Wifi
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.netdiag.core.AppReset
import com.netdiag.ui.screens.DiagnoseScreen
import com.netdiag.ui.screens.MemoScreen
import com.netdiag.ui.screens.MonitorScreen
import com.netdiag.ui.screens.ScanScreen
import com.netdiag.ui.screens.SettingsScreen
import com.netdiag.ui.screens.TrafficScreen
import com.netdiag.ui.screens.WifiScreen
import com.netdiag.ui.theme.NetDiagTheme
import com.netdiag.ui.theme.SettingsRepository

private data class Tab(val label: String, val icon: ImageVector)

private val tabs = listOf(
    Tab("TOP", Icons.Outlined.Home),
    Tab("診断", Icons.Outlined.Search),
    Tab("Wi-Fi", Icons.Outlined.Wifi),
    Tab("速度", Icons.Outlined.Speed),
    Tab("監視", Icons.Outlined.RadioButtonChecked),
    Tab("メモ", Icons.Outlined.EditNote),
    Tab("設定", Icons.Outlined.Settings),
)

@Composable
fun NetDiagApp() {
    val context = LocalContext.current
    val repo = remember { SettingsRepository(context) }
    var settings by remember { mutableStateOf(repo.load()) }

    NetDiagTheme(settings) {
        var selected by rememberSaveable { mutableIntStateOf(0) }
        var showClearConfirm by remember { mutableStateOf(false) }

        Scaffold(
            topBar = {
                TerminalMenuHeader(
                    tabs = tabs,
                    selectedIndex = selected,
                    onSelect = { selected = it },
                    onClearAllClick = { showClearConfirm = true },
                )
            },
        ) { padding ->
            Box(Modifier.fillMaxSize().padding(padding)) {
                when (selected) {
                    0 -> ScanScreen()
                    1 -> DiagnoseScreen()
                    2 -> WifiScreen()
                    3 -> TrafficScreen()
                    4 -> MonitorScreen()
                    5 -> MemoScreen()
                    else -> SettingsScreen(
                        settings = settings,
                        onChange = { settings = it; repo.save(it) },
                    )
                }
            }
        }

        if (showClearConfirm) {
            AlertDialog(
                onDismissRequest = { showClearConfirm = false },
                title = { Text("ALL CLEAR") },
                text = {
                    Text(
                        "メモ・診断ログ・監視アラート・撮影した画像・ネットワークの基準値を" +
                            "すべて削除して、最初からやり直します。この操作は元に戻せません。"
                    )
                },
                confirmButton = {
                    TextButton(onClick = {
                        AppReset.clearAll(context)
                        showClearConfirm = false
                    }) {
                        Text("クリアする", color = MaterialTheme.colorScheme.error)
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showClearConfirm = false }) { Text("キャンセル") }
                },
            )
        }
    }
}

/**
 * Console-style top banner doubling as navigation: a shell prompt in phosphor
 * green with a blinking block cursor shows the current section, and tapping it
 * drops down a full-width command list of every tab. This replaces a bottom
 * NavigationBar, which — at 7 destinations — got cramped and wrapped labels
 * onto two lines on narrow phones.
 */
@Composable
private fun TerminalMenuHeader(
    tabs: List<Tab>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    onClearAllClick: () -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val accent = MaterialTheme.colorScheme.primary
    val current = tabs[selectedIndex]

    val transition = rememberInfiniteTransition(label = "cursor")
    val blink by transition.animateFloat(
        initialValue = 1f,
        targetValue = 0f,
        animationSpec = infiniteRepeatable(
            animation = tween(650, easing = LinearEasing),
            repeatMode = androidx.compose.animation.core.RepeatMode.Reverse,
        ),
        label = "blink",
    )

    Column(
        Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.background)
            .statusBarsPadding(),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .clickable { expanded = !expanded }
                .padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "root@netscope",
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                fontSize = 15.sp,
                color = accent,
            )
            Text(
                ":~$ ",
                fontFamily = FontFamily.Monospace,
                fontSize = 15.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                current.label.lowercase(),
                fontFamily = FontFamily.Monospace,
                fontSize = 15.sp,
                color = NameColor,
            )
            Text(
                "█",
                fontFamily = FontFamily.Monospace,
                fontSize = 15.sp,
                color = accent.copy(alpha = blink),
            )
            Spacer(Modifier.weight(1f))
            Icon(
                Icons.Outlined.DeleteSweep,
                contentDescription = "ALL CLEAR（メモ・ログ・画像を全て消去）",
                tint = DangerColor,
                modifier = Modifier
                    .size(22.dp)
                    .clickable(onClick = onClearAllClick),
            )
            Spacer(Modifier.width(14.dp))
            Icon(
                if (expanded) Icons.Outlined.Close else Icons.Outlined.Menu,
                contentDescription = if (expanded) "メニューを閉じる" else "メニューを開く",
                tint = accent,
                modifier = Modifier.size(22.dp),
            )
        }
        HorizontalDivider(thickness = 1.dp, color = accent.copy(alpha = 0.45f))

        IconTabStrip(tabs = tabs, selectedIndex = selectedIndex, onSelect = onSelect, accent = accent)
        HorizontalDivider(thickness = 1.dp, color = accent.copy(alpha = 0.25f))

        AnimatedVisibility(
            visible = expanded,
            enter = fadeIn() + expandVertically(),
            exit = fadeOut() + shrinkVertically(),
        ) {
            Column(Modifier.fillMaxWidth()) {
                Column(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface)) {
                    tabs.forEachIndexed { index, tab ->
                        val isSelected = index == selectedIndex
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clickable { onSelect(index); expanded = false }
                                .background(
                                    if (isSelected) accent.copy(alpha = 0.12f) else Color.Transparent
                                )
                                .padding(horizontal = 18.dp, vertical = 13.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(
                                tab.icon,
                                contentDescription = null,
                                tint = if (isSelected) accent else MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(20.dp),
                            )
                            Spacer(Modifier.width(14.dp))
                            Text(
                                "> ${tab.label}",
                                fontFamily = FontFamily.Monospace,
                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                fontSize = 15.sp,
                                color = if (isSelected) accent else NameColor,
                            )
                        }
                        if (index != tabs.lastIndex) {
                            HorizontalDivider(thickness = 0.5.dp, color = accent.copy(alpha = 0.15f))
                        }
                    }
                }
                HorizontalDivider(thickness = 1.dp, color = accent.copy(alpha = 0.45f))
            }
        }
    }
}

/**
 * Always-visible row of icon-only tab buttons — no labels, so all 7 fit on
 * one line without wrapping. A quick way to jump straight to a section;
 * the hamburger menu above still opens the full labelled list for anyone
 * unsure what an icon means.
 */
@Composable
private fun IconTabStrip(
    tabs: List<Tab>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    accent: Color,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        tabs.forEachIndexed { index, tab ->
            val isSelected = index == selectedIndex
            Box(
                Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .background(if (isSelected) accent.copy(alpha = 0.16f) else Color.Transparent)
                    .clickable { onSelect(index) }
                    .padding(horizontal = 10.dp, vertical = 6.dp),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    tab.icon,
                    contentDescription = tab.label,
                    tint = if (isSelected) accent else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(22.dp),
                )
            }
        }
    }
}
