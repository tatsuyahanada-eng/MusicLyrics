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
import androidx.compose.material3.Switch
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
import dev.hanada.tubevault.data.PlayerClient

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
    val cookieCount by viewModel.cookieCount.collectAsStateWithLifecycle()
    val signedIn by viewModel.signedIn.collectAsStateWithLifecycle()

    LaunchedEffect(toast) {
        toast?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.consumeToast()
        }
    }

    // The browser tab is what actually changes the sign-in state, so pick
    // that up whenever this screen becomes visible instead of only once.
    LaunchedEffect(Unit) { viewModel.refresh() }

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

        SettingSection("YouTube のログイン")
        Text(
            text = when {
                signedIn -> "サインイン済みのセッションを利用中（Cookie ${cookieCount} 件）"
                cookieCount > 0 -> "未サインインのセッションを利用中（Cookie ${cookieCount} 件）"
                else -> "まだセッションがありません"
            },
            style = MaterialTheme.typography.bodyMedium,
            color = if (signedIn) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
        )
        Text(
            text = if (signedIn) {
                "年齢制限などサインインが必須の動画にも対応できます。"
            } else {
                "年齢制限が付いた動画などは、閲覧だけでなく実際のサインインが必要です。" +
                    "「ホーム」タブの🔑アイコンからサインインしてください。"
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 4.dp),
        )
        Text(
            text = "⚠️ ログインする際は、普段使いのアカウントではなく、このアプリ専用のサブ" +
                "アカウントを新しく作って使うことを強くおすすめします。自動的な取得はYouTube" +
                "の規約に反するため、使いすぎるとそのアカウントに確認や制限がかかることがあり" +
                "ます。サブアカウントなら、何かあっても普段のGmail等には影響しません。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
            modifier = Modifier.padding(top = 8.dp),
        )
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("セッションを使う", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            Switch(
                checked = settings.useCookies,
                onCheckedChange = viewModel::setUseCookies,
            )
        }
        OutlinedButton(
            onClick = viewModel::clearCookies,
            modifier = Modifier.padding(top = 8.dp),
        ) {
            Text("ログイン情報を消す")
        }

        SettingSection("プレイヤークライアント")
        Text(
            text = "取得に失敗し続けるときに変更します。まずは「自動」のままで。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(bottom = 8.dp),
        )
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PlayerClient.entries.forEach { option ->
                FilterChip(
                    selected = settings.playerClient == option,
                    onClick = { viewModel.setPlayerClient(option) },
                    label = { Text(option.label) },
                )
            }
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
