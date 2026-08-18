package dev.hanada.tubevault.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.hanada.tubevault.core.MediaKind
import dev.hanada.tubevault.core.Storage
import dev.hanada.tubevault.core.VideoQuality
import dev.hanada.tubevault.core.formatBytes

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SettingsScreen(
    viewModel: SettingsViewModel,
    snackbarHostState: SnackbarHostState,
    contentPadding: PaddingValues,
    modifier: Modifier = Modifier,
) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val categories by viewModel.categories.collectAsStateWithLifecycle()
    val version by viewModel.ytDlpVersion.collectAsStateWithLifecycle()
    val busy by viewModel.busyMessage.collectAsStateWithLifecycle()
    val storageBytes by viewModel.storageBytes.collectAsStateWithLifecycle()
    val toast by viewModel.toast.collectAsStateWithLifecycle()

    LaunchedEffect(toast) {
        toast?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.consumeToast()
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(contentPadding)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp),
    ) {
        Text("設定", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(vertical = 12.dp))

        SettingSection("既定の形式")
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            MediaKind.entries.forEach { option ->
                FilterChip(
                    selected = settings.defaultKind == option,
                    onClick = { viewModel.setDefaultKind(option) },
                    label = { Text(option.label) },
                )
            }
        }

        SettingSection("既定の画質")
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            VideoQuality.entries.forEach { option ->
                FilterChip(
                    selected = settings.defaultQuality == option,
                    onClick = { viewModel.setDefaultQuality(option) },
                    label = { Text(option.label) },
                )
            }
        }

        SettingSection("既定の保存先")
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            categories.forEach { category ->
                FilterChip(
                    selected = settings.defaultCategoryId == category.id,
                    onClick = { viewModel.setDefaultCategory(category.id) },
                    label = { Text(category.name) },
                )
            }
        }

        SettingSection("検索結果の件数")
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(10, 25, 50).forEach { limit ->
                FilterChip(
                    selected = settings.searchLimit == limit,
                    onClick = { viewModel.setSearchLimit(limit) },
                    label = { Text("$limit 件") },
                )
            }
        }

        HorizontalDivider(modifier = Modifier.padding(vertical = 20.dp))

        SettingSection("ストレージ")
        Text(
            text = "使用量: ${formatBytes(storageBytes)}",
            style = MaterialTheme.typography.bodyMedium,
        )
        Text(
            text = "保存先: Android/data/dev.hanada.tubevault/files/${Storage.ROOT_DIR_NAME}/",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 4.dp),
        )
        Row(
            modifier = Modifier.padding(top = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedButton(onClick = viewModel::pruneMissing) { Text("不明なファイルを整理") }
            OutlinedButton(onClick = viewModel::refresh) { Text("再計算") }
        }

        HorizontalDivider(modifier = Modifier.padding(vertical = 20.dp))

        SettingSection("yt-dlp")
        Text(text = "バージョン: $version", style = MaterialTheme.typography.bodyMedium)
        Text(
            text = "YouTube の仕様変更で動画が取得できなくなったときは、まず更新を試してください。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 4.dp),
        )
        Row(
            modifier = Modifier.padding(top = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            OutlinedButton(onClick = viewModel::updateYtDlp, enabled = busy == null) {
                Text("yt-dlp を更新")
            }
            if (busy != null) {
                CircularProgressIndicator(modifier = Modifier.size(20.dp))
                Text(busy.orEmpty(), style = MaterialTheme.typography.bodySmall)
            }
        }

        HorizontalDivider(modifier = Modifier.padding(vertical = 20.dp))

        Text(
            text = "このアプリは個人利用専用です。ダウンロードは YouTube の利用規約に反する場合があります。" +
                "権利者が許諾していないコンテンツの保存・再配布は行わないでください。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(bottom = 32.dp),
        )
    }
}

@Composable
private fun SettingSection(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleSmall,
        modifier = Modifier.fillMaxWidth().padding(top = 16.dp, bottom = 8.dp),
    )
}
