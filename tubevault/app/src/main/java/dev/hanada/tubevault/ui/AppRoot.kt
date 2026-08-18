package dev.hanada.tubevault.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.hanada.tubevault.AppContainer

private enum class Tab(val label: String, val icon: ImageVector) {
    SEARCH("検索", Icons.Default.Search),
    LIBRARY("ライブラリ", Icons.Default.Folder),
    DOWNLOADS("取得中", Icons.Default.Download),
    SETTINGS("設定", Icons.Default.Settings),
}

@Composable
fun AppRoot(container: AppContainer) {
    val searchViewModel: SearchViewModel = viewModel(factory = AppViewModelFactory)
    val libraryViewModel: LibraryViewModel = viewModel(factory = AppViewModelFactory)
    val downloadsViewModel: DownloadsViewModel = viewModel(factory = AppViewModelFactory)
    val settingsViewModel: SettingsViewModel = viewModel(factory = AppViewModelFactory)

    var tab by rememberSaveable { mutableStateOf(Tab.SEARCH) }
    val snackbarHostState = remember { SnackbarHostState() }
    val jobs by downloadsViewModel.jobs.collectAsStateWithLifecycle()
    val activeCount = jobs.count { it.isActive }

    Box(modifier = Modifier.fillMaxSize()) {
        Scaffold(
            snackbarHost = { SnackbarHost(snackbarHostState) },
            bottomBar = {
                Column {
                    MiniPlayer(container.playback)
                    NavigationBar {
                        Tab.entries.forEach { entry ->
                            val label = if (entry == Tab.DOWNLOADS && activeCount > 0) {
                                "${entry.label} ($activeCount)"
                            } else {
                                entry.label
                            }
                            NavigationBarItem(
                                selected = tab == entry,
                                onClick = { tab = entry },
                                icon = { Icon(entry.icon, contentDescription = entry.label) },
                                label = { Text(label) },
                            )
                        }
                    }
                }
            },
        ) { padding ->
            when (tab) {
                Tab.SEARCH -> SearchScreen(searchViewModel, padding)
                Tab.LIBRARY -> LibraryScreen(libraryViewModel, padding)
                Tab.DOWNLOADS -> DownloadsScreen(downloadsViewModel, padding)
                Tab.SETTINGS -> SettingsScreen(settingsViewModel, snackbarHostState, padding)
            }
        }

        // Sits above the scaffold so it can cover the navigation bar too.
        FullPlayer(container.playback, modifier = Modifier.fillMaxSize())
    }
}
