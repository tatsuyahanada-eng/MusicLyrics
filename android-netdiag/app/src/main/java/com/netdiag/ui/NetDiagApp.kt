package com.netdiag.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.EditNote
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.NetworkCheck
import androidx.compose.material.icons.outlined.QueryStats
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Wifi
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
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
    Tab("診断", Icons.Outlined.NetworkCheck),
    Tab("Wi-Fi", Icons.Outlined.Wifi),
    Tab("通信量", Icons.Outlined.QueryStats),
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
            bottomBar = {
                NavigationBar {
                    tabs.forEachIndexed { index, tab ->
                        NavigationBarItem(
                            selected = selected == index,
                            onClick = { selected = index },
                            icon = { Icon(tab.icon, contentDescription = tab.label) },
                            label = { Text(tab.label) },
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
