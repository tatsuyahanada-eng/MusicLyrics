package com.netdiag.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Lan
import androidx.compose.material.icons.outlined.NetworkCheck
import androidx.compose.material.icons.outlined.QueryStats
import androidx.compose.material.icons.outlined.Wifi
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import com.netdiag.ui.screens.DiagnoseScreen
import com.netdiag.ui.screens.ScanScreen
import com.netdiag.ui.screens.TrafficScreen
import com.netdiag.ui.screens.WifiScreen
import com.netdiag.ui.theme.NetDiagTheme

private data class Tab(val label: String, val icon: ImageVector)

private val tabs = listOf(
    Tab("可視化", Icons.Outlined.Lan),
    Tab("診断", Icons.Outlined.NetworkCheck),
    Tab("Wi-Fi", Icons.Outlined.Wifi),
    Tab("通信量", Icons.Outlined.QueryStats),
)

@Composable
fun NetDiagApp() {
    NetDiagTheme {
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
                    else -> TrafficScreen()
                }
            }
        }
    }
}
