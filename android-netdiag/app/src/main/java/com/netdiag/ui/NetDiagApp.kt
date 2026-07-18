package com.netdiag.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.EditNote
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Speed
import androidx.compose.material.icons.outlined.Wifi
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.netdiag.ui.screens.DiagnoseScreen
import com.netdiag.ui.screens.MemoScreen
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
        Scaffold(
            topBar = { TerminalHeader(tabs[selected].label) },
            bottomBar = {
                NavigationBar {
                    tabs.forEachIndexed { index, tab ->
                        NavigationBarItem(
                            selected = selected == index,
                            onClick = { selected = index },
                            icon = { Icon(tab.icon, contentDescription = tab.label) },
                            label = { Text(tab.label) },
                            colors = NavigationBarItemDefaults.colors(
                                selectedIconColor = MaterialTheme.colorScheme.onPrimary,
                                selectedTextColor = MaterialTheme.colorScheme.primary,
                                indicatorColor = MaterialTheme.colorScheme.primary,
                                unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant,
                            ),
                        )
                    }
                }
            },
        ) { padding ->
            Box(Modifier.fillMaxSize().padding(padding)) {
                when (selected) {
                    0 -> ScanScreen()
                    1 -> DiagnoseScreen()
                    2 -> WifiScreen()
                    3 -> TrafficScreen()
                    4 -> MemoScreen()
                    else -> SettingsScreen(
                        settings = settings,
                        onChange = { settings = it; repo.save(it) },
                    )
                }
            }
        }
    }
}

/**
 * Console-style top banner: a shell prompt in phosphor green with a blinking
 * block cursor, giving the whole app a terminal / matrix look.
 */
@Composable
private fun TerminalHeader(section: String) {
    val accent = MaterialTheme.colorScheme.primary
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
                section.lowercase(),
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
        }
        HorizontalDivider(thickness = 1.dp, color = accent.copy(alpha = 0.45f))
    }
}
