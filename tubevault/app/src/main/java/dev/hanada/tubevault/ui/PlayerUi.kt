package dev.hanada.tubevault.ui

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Forward30
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.FullscreenExit
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.RepeatOne
import androidx.compose.material.icons.filled.Replay10
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Subtitles
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.Player
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import dev.hanada.tubevault.core.MediaKind
import dev.hanada.tubevault.core.formatDuration
import dev.hanada.tubevault.data.MediaItemEntity
import dev.hanada.tubevault.lyrics.LyricLine
import dev.hanada.tubevault.lyrics.LyricsController
import dev.hanada.tubevault.lyrics.LyricsUiState
import dev.hanada.tubevault.lyrics.currentLyricLineIndex
import dev.hanada.tubevault.playback.PlaybackController
import kotlinx.coroutines.delay
import java.io.File

/** Persistent bar that sits above the bottom navigation while something plays. */
@Composable
fun MiniPlayer(
    controller: PlaybackController,
    modifier: Modifier = Modifier,
) {
    val item by controller.currentItem.collectAsStateWithLifecycle()
    val isPlaying by controller.isPlaying.collectAsStateWithLifecycle()
    val positionMs by controller.positionMs.collectAsStateWithLifecycle()
    val durationMs by controller.durationMs.collectAsStateWithLifecycle()
    val current = item ?: return

    Surface(
        modifier = modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
    ) {
        Column {
            // A hairline of progress along the top edge: enough to tell how far
            // in a track is without spending any of the bar's height on it.
            // Drawn by hand rather than with a progress indicator, which
            // insists on its own height and end-cap decorations.
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(2.dp)
                    .background(MaterialTheme.colorScheme.surfaceContainerHighest),
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth(progressFraction(positionMs, durationMs))
                        .fillMaxHeight()
                        .background(MaterialTheme.colorScheme.primary),
                )
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { controller.setExpanded(true) }
                    .padding(start = 10.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Thumbnail(
                    model = current.thumbPath?.let { File(it) },
                    durationSec = 0,
                    modifier = Modifier.width(52.dp).height(32.dp),
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
                        tint = MaterialTheme.colorScheme.primary,
                    )
                }
                IconButton(onClick = controller::stop) {
                    Icon(
                        imageVector = Icons.Default.Stop,
                        contentDescription = "停止",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

/** Full-screen player, slid up over everything else. */
@Composable
fun FullPlayer(
    controller: PlaybackController,
    lyrics: LyricsController,
    modifier: Modifier = Modifier,
) {
    val expanded by controller.expanded.collectAsStateWithLifecycle()
    val item by controller.currentItem.collectAsStateWithLifecycle()

    AnimatedVisibility(
        visible = expanded && item != null,
        enter = slideInVertically { it } + fadeIn(),
        exit = slideOutVertically { it } + fadeOut(),
        modifier = modifier,
    ) {
        PlayerContent(controller, lyrics)
    }
}

@Composable
private fun PlayerContent(controller: PlaybackController, lyrics: LyricsController) {
    val item by controller.currentItem.collectAsStateWithLifecycle()
    val current = item ?: return

    // Rotating the device is a request for the immersive layout just as much as
    // pressing the button is, so both feed the same flag — otherwise turning
    // the phone sideways would leave a portrait layout stretched across it.
    val rotated = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
    var forcedLandscape by rememberSaveable { mutableStateOf(false) }
    val immersive = forcedLandscape || rotated

    LockToLandscape(forcedLandscape)
    HideSystemBars(immersive)

    BackHandler(enabled = true) {
        if (immersive) {
            // Back leaves fullscreen before it leaves the player: dropping
            // straight out of both at once loses the user's place.
            forcedLandscape = false
        } else {
            controller.setExpanded(false)
        }
    }

    if (immersive) {
        ImmersivePlayer(
            controller = controller,
            lyrics = lyrics,
            current = current,
            onExitFullscreen = { forcedLandscape = false },
        )
    } else {
        PortraitPlayer(
            controller = controller,
            lyrics = lyrics,
            current = current,
            onEnterFullscreen = { forcedLandscape = true },
        )
    }
}

// ---------------------------------------------------------------- portrait --

@Composable
private fun PortraitPlayer(
    controller: PlaybackController,
    lyrics: LyricsController,
    current: MediaItemEntity,
    onEnterFullscreen: () -> Unit,
) {
    val player by controller.player.collectAsStateWithLifecycle()
    val positionMs by controller.positionMs.collectAsStateWithLifecycle()
    var showLyricsDialog by remember { mutableStateOf(false) }

    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.systemBars),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = { controller.setExpanded(false) }) {
                    Icon(Icons.Default.KeyboardArrowDown, contentDescription = "閉じる")
                }
                Text(
                    text = "再生中",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = { showLyricsDialog = true }) {
                    Icon(
                        imageVector = Icons.Default.Subtitles,
                        contentDescription = "歌詞を検索・修正",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                IconButton(onClick = controller::stop) {
                    Icon(
                        imageVector = Icons.Default.Stop,
                        contentDescription = "停止",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            // Artist and song name, from what lyrics are actually being
            // searched for rather than the raw video title — the guess (or
            // the user's own correction) has already had a video title's
            // usual decoration stripped, so it reads like a real credit
            // instead of "Artist - Song (Official Video)".
            val query by lyrics.query.collectAsStateWithLifecycle()
            Column(modifier = Modifier.padding(horizontal = 24.dp, vertical = 4.dp)) {
                Text(
                    text = query?.title?.takeIf { it.isNotEmpty() } ?: current.title,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = query?.artist?.takeIf { it.isNotEmpty() }
                        ?: current.uploader
                        ?: current.mediaKind.label,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 12.dp),
                contentAlignment = Alignment.Center,
            ) {
                Stage(current = current, player = player)
            }

            // Takes whatever the picture above and the controls below leave
            // it, rather than sharing a fixed split with the picture — a
            // short song's lyrics should not be stretched to fill space a
            // long one would need to scroll.
            LyricsPanel(
                lyrics = lyrics,
                positionMs = positionMs,
                onSeek = controller::seekTo,
                modifier = Modifier.weight(1f).fillMaxWidth().padding(horizontal = 20.dp),
            )

            Spacer(Modifier.height(12.dp))
            SeekBar(controller = controller, modifier = Modifier.padding(horizontal = 20.dp))
            Spacer(Modifier.height(4.dp))
            TransportRow(controller = controller)
            Spacer(Modifier.height(4.dp))
            ModeRow(
                controller = controller,
                fullscreen = false,
                onToggleFullscreen = onEnterFullscreen,
                modifier = Modifier.padding(horizontal = 28.dp),
            )
            Spacer(Modifier.height(20.dp))
        }
    }

    if (showLyricsDialog) {
        LyricsCorrectionDialog(lyrics = lyrics, onDismiss = { showLyricsDialog = false })
    }
}

/**
 * The picture itself: a video surface, or the artwork standing in for audio.
 *
 * Sized by aspect ratio alone, with no `fillMaxWidth`. Pinning the width would
 * make the ratio derive a height the stage may not have on a short screen and
 * spill over the controls; left free, it takes whichever dimension runs out
 * first and stays inside the space it was given.
 */
@Composable
private fun Stage(current: MediaItemEntity, player: Player?) {
    if (current.mediaKind == MediaKind.VIDEO) {
        Surface(
            modifier = Modifier.aspectRatio(16f / 9f),
            shape = RoundedCornerShape(20.dp),
            color = Color.Black,
            shadowElevation = 12.dp,
        ) {
            VideoSurface(player = player, modifier = Modifier.fillMaxSize())
        }
    } else {
        Surface(
            modifier = Modifier.aspectRatio(1f),
            shape = RoundedCornerShape(28.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            shadowElevation = 16.dp,
        ) {
            Thumbnail(
                model = current.thumbPath?.let { File(it) },
                durationSec = 0,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

@Composable
private fun VideoSurface(player: Player?, modifier: Modifier = Modifier) {
    AndroidView(
        factory = { context ->
            PlayerView(context).apply {
                useController = false
                resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                setBackgroundColor(android.graphics.Color.BLACK)
            }
        },
        update = { view -> view.player = player },
        // Portrait and fullscreen each build their own PlayerView; detaching
        // the player on the way out stops the outgoing one from holding on to
        // the video output and leaving the incoming one black.
        onRelease = { view -> view.player = null },
        modifier = modifier,
    )
}

// ------------------------------------------------------------- fullscreen --

@Composable
private fun ImmersivePlayer(
    controller: PlaybackController,
    lyrics: LyricsController,
    current: MediaItemEntity,
    onExitFullscreen: () -> Unit,
) {
    val player by controller.player.collectAsStateWithLifecycle()
    val isPlaying by controller.isPlaying.collectAsStateWithLifecycle()

    var controlsVisible by remember { mutableStateOf(true) }
    var showLyricsDialog by remember { mutableStateOf(false) }

    // Chrome over a video is for reaching, not reading — it goes away on its
    // own while playback continues, and any tap brings it back.
    LaunchedEffect(controlsVisible, isPlaying) {
        if (controlsVisible && isPlaying) {
            delay(CONTROLS_TIMEOUT_MS)
            controlsVisible = false
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
            ) { controlsVisible = !controlsVisible },
    ) {
        if (current.mediaKind == MediaKind.VIDEO) {
            VideoSurface(player = player, modifier = Modifier.fillMaxSize())
        } else {
            Thumbnail(
                model = current.thumbPath?.let { File(it) },
                durationSec = 0,
                modifier = Modifier.align(Alignment.Center).fillMaxHeight(0.7f).aspectRatio(1f),
            )
        }

        AnimatedVisibility(
            visible = controlsVisible,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.fillMaxSize(),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.45f))
                    .windowInsetsPadding(WindowInsets.systemBars),
            ) {
                Text(
                    text = current.title,
                    style = MaterialTheme.typography.titleMedium,
                    color = Color.White,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(horizontal = 24.dp, vertical = 16.dp),
                )
                Row(modifier = Modifier.align(Alignment.TopEnd).padding(4.dp)) {
                    IconButton(onClick = { showLyricsDialog = true }) {
                        Icon(
                            imageVector = Icons.Default.Subtitles,
                            contentDescription = "歌詞を検索・修正",
                            tint = Color.White,
                        )
                    }
                    IconButton(onClick = onExitFullscreen) {
                        Icon(
                            imageVector = Icons.Default.FullscreenExit,
                            contentDescription = "全画面を終了",
                            tint = Color.White,
                        )
                    }
                }

                Column(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                ) {
                    SeekBar(controller = controller, onDark = true)
                    TransportRow(controller = controller, onDark = true)
                }
            }
        }
    }

    if (showLyricsDialog) {
        LyricsCorrectionDialog(lyrics = lyrics, onDismiss = { showLyricsDialog = false })
    }
}

// ---------------------------------------------------------------- controls --

@Composable
private fun SeekBar(
    controller: PlaybackController,
    modifier: Modifier = Modifier,
    onDark: Boolean = false,
) {
    val positionMs by controller.positionMs.collectAsStateWithLifecycle()
    val durationMs by controller.durationMs.collectAsStateWithLifecycle()
    var scrubbing by remember { mutableStateOf<Float?>(null) }

    val safeDuration = durationMs.coerceAtLeast(1L)
    val accent = if (onDark) Color.White else MaterialTheme.colorScheme.primary
    val muted = if (onDark) Color.White.copy(alpha = 0.3f) else MaterialTheme.colorScheme.surfaceContainerHighest
    val labelColor = if (onDark) Color.White.copy(alpha = 0.8f) else MaterialTheme.colorScheme.onSurfaceVariant

    Column(modifier = modifier) {
        Slider(
            value = scrubbing ?: progressFraction(positionMs, durationMs),
            onValueChange = { scrubbing = it },
            onValueChangeFinished = {
                scrubbing?.let { controller.seekTo((it * safeDuration).toLong()) }
                scrubbing = null
            },
            colors = SliderDefaults.colors(
                thumbColor = accent,
                activeTrackColor = accent,
                inactiveTrackColor = muted,
            ),
        )
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 6.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            val shown = scrubbing?.let { (it * safeDuration).toLong() } ?: positionMs
            Text(
                text = formatDuration(shown / 1000),
                style = MaterialTheme.typography.labelMedium,
                color = labelColor,
            )
            Text(
                text = formatDuration(durationMs / 1000),
                style = MaterialTheme.typography.labelMedium,
                color = labelColor,
            )
        }
    }
}

/**
 * Skip, seek and play, sized by how often each is reached for: play is a
 * filled target twice the size of anything else, track skips are tonal discs
 * either side of it, and the ten/thirty second jumps sit outermost as plain
 * glyphs.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TransportRow(
    controller: PlaybackController,
    modifier: Modifier = Modifier,
    onDark: Boolean = false,
) {
    val isPlaying by controller.isPlaying.collectAsStateWithLifecycle()

    val plain = if (onDark) Color.White.copy(alpha = 0.85f) else MaterialTheme.colorScheme.onSurfaceVariant
    val strong = if (onDark) Color.White else MaterialTheme.colorScheme.onSurface

    Row(
        modifier = modifier.fillMaxWidth().padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        GlyphButton(Icons.Default.Replay10, "10秒戻る", plain, 26.dp) { controller.skipBy(-10_000L) }
        Spacer(Modifier.width(6.dp))
        GlyphButton(Icons.Default.SkipPrevious, "前の曲へ", strong, 32.dp, controller::previous)
        Spacer(Modifier.width(10.dp))

        Surface(
            onClick = controller::togglePlayPause,
            shape = CircleShape,
            color = if (onDark) Color.White else MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(72.dp),
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    imageVector = if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                    contentDescription = if (isPlaying) "一時停止" else "再生",
                    tint = if (onDark) Color.Black else MaterialTheme.colorScheme.onPrimary,
                    modifier = Modifier.size(38.dp),
                )
            }
        }

        Spacer(Modifier.width(10.dp))
        GlyphButton(Icons.Default.SkipNext, "次の曲へ", strong, 32.dp, controller::next)
        Spacer(Modifier.width(6.dp))
        GlyphButton(Icons.Default.Forward30, "30秒進む", plain, 26.dp) { controller.skipBy(30_000L) }
    }
}

/**
 * The settings that stay put — shuffle, repeat, fullscreen. They are kept off
 * the transport row because a mode that persists should not look like an
 * action that fires once.
 */
@Composable
private fun ModeRow(
    controller: PlaybackController,
    fullscreen: Boolean,
    onToggleFullscreen: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val shuffleEnabled by controller.shuffleEnabled.collectAsStateWithLifecycle()
    val repeatMode by controller.repeatMode.collectAsStateWithLifecycle()

    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ModeButton(
            icon = Icons.Default.Shuffle,
            description = if (shuffleEnabled) "シャッフルを解除" else "シャッフル再生",
            active = shuffleEnabled,
            onClick = controller::toggleShuffle,
        )
        ModeButton(
            icon = if (repeatMode == Player.REPEAT_MODE_ONE) {
                Icons.Default.RepeatOne
            } else {
                Icons.Default.Repeat
            },
            description = when (repeatMode) {
                Player.REPEAT_MODE_OFF -> "リピート再生"
                Player.REPEAT_MODE_ALL -> "1曲リピート"
                else -> "リピートを解除"
            },
            active = repeatMode != Player.REPEAT_MODE_OFF,
            onClick = controller::cycleRepeat,
        )
        ModeButton(
            icon = if (fullscreen) Icons.Default.FullscreenExit else Icons.Default.Fullscreen,
            description = if (fullscreen) "全画面を終了" else "横画面で全画面表示",
            active = fullscreen,
            onClick = onToggleFullscreen,
        )
    }
}

/** A toggle that reads as on at a glance: filled when active, bare when not. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ModeButton(
    icon: ImageVector,
    description: String,
    active: Boolean,
    onClick: () -> Unit,
) {
    Surface(
        onClick = onClick,
        shape = CircleShape,
        color = if (active) {
            MaterialTheme.colorScheme.primaryContainer
        } else {
            MaterialTheme.colorScheme.surfaceContainerHigh
        },
        modifier = Modifier.size(44.dp),
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(
                imageVector = icon,
                contentDescription = description,
                tint = if (active) {
                    MaterialTheme.colorScheme.onPrimaryContainer
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
                modifier = Modifier.size(22.dp),
            )
        }
    }
}

@Composable
private fun GlyphButton(
    icon: ImageVector,
    description: String,
    tint: Color,
    size: Dp,
    onClick: () -> Unit,
) {
    IconButton(onClick = onClick, modifier = Modifier.size(size + 20.dp)) {
        Icon(
            imageVector = icon,
            contentDescription = description,
            tint = tint,
            modifier = Modifier.size(size),
        )
    }
}

// ------------------------------------------------------------------ lyrics --

/**
 * Lyrics in the space below the picture, rather than over it — several lines
 * at once so the song reads as a whole, with the line playing right now
 * carried in white.
 *
 * Always occupies its slot even with nothing to show, so layout does not
 * shift when lyrics show up mid-song or disappear on a track change. Only
 * timestamped lines qualify: a plain (unsynced) result cannot track playback,
 * so it is left to [LyricsCorrectionDialog] to display in full.
 *
 * A tap both seeks playback to that line and scrolls the list there, so the
 * lyric under a finger is exactly where the song jumps to.
 */
@Composable
private fun LyricsPanel(
    lyrics: LyricsController,
    positionMs: Long,
    onSeek: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by lyrics.state.collectAsStateWithLifecycle()
    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        (state as? LyricsUiState.Synced)?.let {
            SyncedLyrics(lines = it.lines, positionMs = positionMs, onSeek = onSeek)
        }
    }
}

@Composable
private fun SyncedLyrics(lines: List<LyricLine>, positionMs: Long, onSeek: (Long) -> Unit) {
    val active = remember(lines, positionMs) { currentLyricLineIndex(lines, positionMs) }
    val listState = rememberLazyListState()

    // A line's resting place is a third of the way down rather than at the
    // top, so the lines about to be sung stay on screen ahead of the one
    // being sung.
    suspend fun scrollTo(index: Int) {
        if (index < 0) return
        val viewport = listState.layoutInfo.viewportSize.height
        listState.animateScrollToItem(index, -viewport / 3)
    }

    LaunchedEffect(active) { scrollTo(active) }

    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        // Lets the first and last lines reach the same resting place every
        // other line does, instead of jamming against the panel edge.
        contentPadding = PaddingValues(vertical = 48.dp),
    ) {
        itemsIndexed(lines) { index, line ->
            val current = index == active
            Text(
                text = line.text,
                style = if (current) {
                    MaterialTheme.typography.headlineSmall
                } else {
                    MaterialTheme.typography.titleMedium
                },
                fontWeight = if (current) FontWeight.Bold else FontWeight.Normal,
                color = if (current) Color.White else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                textAlign = TextAlign.Center,
                // Clickable outside the padding, so the gap between lines
                // belongs to a line rather than to nothing — a lyric is a
                // short target to hit while the list is moving.
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onSeek(line.timeMs) }
                    .padding(vertical = 10.dp),
            )
        }
    }
}

/** Lets the user correct the artist/title lyrics are searched for, and shows what was found. */
@Composable
private fun LyricsCorrectionDialog(lyrics: LyricsController, onDismiss: () -> Unit) {
    val query by lyrics.query.collectAsStateWithLifecycle()
    val state by lyrics.state.collectAsStateWithLifecycle()
    var artist by remember(query) { mutableStateOf(query?.artist.orEmpty()) }
    var title by remember(query) { mutableStateOf(query?.title.orEmpty()) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("歌詞") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = artist,
                    onValueChange = { artist = it },
                    label = { Text("アーティスト") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    label = { Text("曲名") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    text = when (state) {
                        LyricsUiState.Idle -> ""
                        LyricsUiState.Loading -> "検索中..."
                        is LyricsUiState.Synced -> "同期歌詞が見つかりました。動画・再生画面の上に表示されます。"
                        is LyricsUiState.PlainOnly -> "歌詞は見つかりましたが、タイミング情報がないため上には表示できません。下に全文を表示します。"
                        LyricsUiState.NotFound -> "歌詞が見つかりませんでした。アーティスト名・曲名を修正して再検索してください。"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                val plain = (state as? LyricsUiState.PlainOnly)?.text
                if (plain != null) {
                    Text(
                        text = plain,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier
                            .heightIn(max = 180.dp)
                            .verticalScroll(rememberScrollState()),
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { lyrics.applyCorrection(artist, title) }) {
                Text("再検索")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("閉じる") }
        },
    )
}

// ------------------------------------------------------------------ window --

/**
 * Forces landscape while [locked], and hands the orientation back to the
 * system on the way out — including when the player is dismissed mid-rotation,
 * which is why this is a disposable rather than a one-shot call.
 */
@Composable
private fun LockToLandscape(locked: Boolean) {
    val activity = LocalView.current.context.findActivity()
    if (activity != null) {
        DisposableEffect(locked) {
            activity.requestedOrientation = if (locked) {
                ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            } else {
                ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            }
            onDispose {
                activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            }
        }
    }
}

@Composable
private fun HideSystemBars(hidden: Boolean) {
    val view = LocalView.current
    val activity = view.context.findActivity()
    if (activity != null) {
        DisposableEffect(hidden) {
            val insets = WindowCompat.getInsetsController(activity.window, view)
            if (hidden) {
                insets.systemBarsBehavior =
                    WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                insets.hide(WindowInsetsCompat.Type.systemBars())
            } else {
                insets.show(WindowInsetsCompat.Type.systemBars())
            }
            onDispose { insets.show(WindowInsetsCompat.Type.systemBars()) }
        }
    }
}

internal tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

private fun progressFraction(positionMs: Long, durationMs: Long): Float =
    (positionMs.toFloat() / durationMs.coerceAtLeast(1L)).coerceIn(0f, 1f)

private const val CONTROLS_TIMEOUT_MS = 3_500L
