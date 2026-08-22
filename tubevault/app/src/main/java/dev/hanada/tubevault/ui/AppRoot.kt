package dev.hanada.tubevault.ui

import android.view.WindowManager
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.hanada.tubevault.AppContainer
import dev.hanada.tubevault.R

private enum class Tab(val label: String, val icon: ImageVector) {
    SEARCH("検索", Icons.Default.Search),
    BROWSE("ホーム", Icons.Default.Home),
    LIBRARY("ライブラリ", Icons.Default.Folder),
    DOWNLOADS("取得中", Icons.Default.Download),
    SETTINGS("設定", Icons.Default.Settings),
}

@Composable
fun AppRoot(container: AppContainer) {
    val searchViewModel: SearchViewModel = viewModel(factory = AppViewModelFactory)
    val browseViewModel: BrowseViewModel = viewModel(factory = AppViewModelFactory)
    val libraryViewModel: LibraryViewModel = viewModel(factory = AppViewModelFactory)
    val downloadsViewModel: DownloadsViewModel = viewModel(factory = AppViewModelFactory)
    val settingsViewModel: SettingsViewModel = viewModel(factory = AppViewModelFactory)

    var tab by rememberSaveable { mutableStateOf(Tab.SEARCH) }
    val snackbarHostState = remember { SnackbarHostState() }
    val jobs by downloadsViewModel.jobs.collectAsStateWithLifecycle()
    val activeCount = jobs.count { it.isActive }

    // Keyed to playback rather than to the full player being open: audio kept
    // going from the mini bar while browsing other tabs is exactly the case
    // where the screen would otherwise time out mid-track.
    val isPlaying by container.playback.isPlaying.collectAsStateWithLifecycle()
    KeepScreenOnWhilePlaying(isPlaying)

    Box(modifier = Modifier.fillMaxSize()) {
        Scaffold(
            topBar = { AppHeader() },
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
                Tab.SEARCH -> SearchScreen(
                    viewModel = searchViewModel,
                    contentPadding = padding,
                    onOpenBrowser = { tab = Tab.BROWSE },
                )

                Tab.BROWSE -> BrowseScreen(browseViewModel, padding)
                Tab.LIBRARY -> LibraryScreen(libraryViewModel, padding)
                Tab.DOWNLOADS -> DownloadsScreen(downloadsViewModel, padding)
                Tab.SETTINGS -> SettingsScreen(settingsViewModel, snackbarHostState, padding)
            }
        }

        // Sits above the scaffold so it can cover the navigation bar too.
        FullPlayer(container.playback, container.lyrics, modifier = Modifier.fillMaxSize())
    }
}

/** Branding row pinned above the tabs: the launcher glyph and app name. */
@Composable
private fun AppHeader() {
    Surface(color = MaterialTheme.colorScheme.surface) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .windowInsetsPadding(WindowInsets.statusBars)
                .padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Image(
                painter = painterResource(R.drawable.ic_launcher_foreground),
                contentDescription = null,
                modifier = Modifier.size(28.dp),
            )
            Text(
                text = stringResource(R.string.app_name),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
    }
}

/**
 * The flag lives on the window, not a wake lock — it only holds the screen
 * on while this activity is the foreground window, which is exactly the
 * scope playback should affect. A wake lock would keep fighting the screen
 * even after the user switches away.
 */
@Composable
private fun KeepScreenOnWhilePlaying(playing: Boolean) {
    val window = LocalView.current.context.findActivity()?.window ?: return
    DisposableEffect(window, playing) {
        if (playing) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
        onDispose { window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
    }
}
