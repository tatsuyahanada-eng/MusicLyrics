package dev.hanada.tubevault.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.systemBars
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Forward30
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Replay10
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.ui.PlayerView
import dev.hanada.tubevault.core.MediaKind
import dev.hanada.tubevault.core.formatDuration
import dev.hanada.tubevault.playback.PlaybackController
import java.io.File

/** Persistent bar that sits above the bottom navigation while something plays. */
@Composable
fun MiniPlayer(
    controller: PlaybackController,
    modifier: Modifier = Modifier,
) {
    val item by controller.currentItem.collectAsStateWithLifecycle()
    val isPlaying by controller.isPlaying.collectAsStateWithLifecycle()
    val current = item ?: return

    Surface(
        modifier = modifier.fillMaxWidth(),
        tonalElevation = 3.dp,
        color = MaterialTheme.colorScheme.surfaceVariant,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { controller.setExpanded(true) }
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Thumbnail(
                model = current.thumbPath?.let { File(it) },
                durationSec = 0,
                modifier = Modifier.width(56.dp).height(32.dp),
            )
            Column(modifier = Modifier.weight(1f).padding(horizontal = 10.dp)) {
                Text(
                    text = current.title,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                current.uploader?.let {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            IconButton(onClick = controller::togglePlayPause) {
                Icon(
                    imageVector = if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                    contentDescription = if (isPlaying) "一時停止" else "再生",
                )
            }
            IconButton(onClick = controller::stop) {
                Icon(Icons.Default.Stop, contentDescription = "停止")
            }
        }
    }
}

/** Full-screen player, slid up over everything else. */
@Composable
fun FullPlayer(
    controller: PlaybackController,
    modifier: Modifier = Modifier,
) {
    val expanded by controller.expanded.collectAsStateWithLifecycle()
    val item by controller.currentItem.collectAsStateWithLifecycle()

    AnimatedVisibility(
        visible = expanded && item != null,
        enter = slideInVertically { it },
        exit = slideOutVertically { it },
        modifier = modifier,
    ) {
        BackHandler(enabled = true) { controller.setExpanded(false) }
        PlayerContent(controller)
    }
}

@Composable
private fun PlayerContent(controller: PlaybackController) {
    val item by controller.currentItem.collectAsStateWithLifecycle()
    val player by controller.player.collectAsStateWithLifecycle()
    val isPlaying by controller.isPlaying.collectAsStateWithLifecycle()
    val positionMs by controller.positionMs.collectAsStateWithLifecycle()
    val durationMs by controller.durationMs.collectAsStateWithLifecycle()
    val shuffleEnabled by controller.shuffleEnabled.collectAsStateWithLifecycle()

    val current = item ?: return
    var scrubbing by remember { mutableStateOf<Float?>(null) }

    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.systemBars),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = { controller.setExpanded(false) }) {
                    Icon(Icons.Default.KeyboardArrowDown, contentDescription = "閉じる")
                }
                Text(
                    text = "再生中",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            if (current.mediaKind == MediaKind.VIDEO) {
                AndroidView(
                    factory = { context ->
                        PlayerView(context).apply {
                            useController = false
                            setBackgroundColor(android.graphics.Color.BLACK)
                        }
                    },
                    update = { view -> view.player = player },
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(16f / 9f)
                        .background(Color.Black),
                )
            } else {
                Box(
                    modifier = Modifier.fillMaxWidth().aspectRatio(1f).padding(32.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Thumbnail(
                        model = current.thumbPath?.let { File(it) },
                        durationSec = 0,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }

            Column(modifier = Modifier.padding(horizontal = 24.dp, vertical = 16.dp)) {
                Text(
                    text = current.title,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                current.uploader?.let {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
            }

            val safeDuration = durationMs.coerceAtLeast(1L)
            val fraction = (positionMs.toFloat() / safeDuration).coerceIn(0f, 1f)

            Slider(
                value = scrubbing ?: fraction,
                onValueChange = { scrubbing = it },
                onValueChangeFinished = {
                    scrubbing?.let { controller.seekTo((it * safeDuration).toLong()) }
                    scrubbing = null
                },
                modifier = Modifier.padding(horizontal = 20.dp),
            )
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = formatDuration(positionMs / 1000),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = formatDuration(durationMs / 1000),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = controller::previous) {
                    Icon(Icons.Default.SkipPrevious, contentDescription = "前へ")
                }
                IconButton(onClick = { controller.skipBy(-10_000L) }) {
                    Icon(Icons.Default.Replay10, contentDescription = "10秒戻る")
                }
                IconButton(
                    onClick = controller::togglePlayPause,
                    modifier = Modifier.size(72.dp),
                ) {
                    Icon(
                        imageVector = if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                        contentDescription = if (isPlaying) "一時停止" else "再生",
                        modifier = Modifier.size(48.dp),
                    )
                }
                IconButton(onClick = { controller.skipBy(30_000L) }) {
                    Icon(Icons.Default.Forward30, contentDescription = "30秒進む")
                }
                IconButton(onClick = controller::next) {
                    Icon(Icons.Default.SkipNext, contentDescription = "次へ")
                }
            }

            // Sits below the transport row rather than in it: shuffle is a mode
            // that stays on, not a one-shot action like the others.
            IconButton(
                onClick = controller::toggleShuffle,
                modifier = Modifier.align(Alignment.CenterHorizontally),
            ) {
                Icon(
                    imageVector = Icons.Default.Shuffle,
                    contentDescription = if (shuffleEnabled) "シャッフルを解除" else "シャッフル再生",
                    tint = if (shuffleEnabled) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
            }

            Spacer(modifier = Modifier.weight(1f))
        }
    }
}
