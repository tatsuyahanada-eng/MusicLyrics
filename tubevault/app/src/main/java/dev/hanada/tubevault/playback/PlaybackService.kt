package dev.hanada.tubevault.playback

import android.content.Intent
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import dev.hanada.tubevault.TubeVaultApp
import kotlinx.coroutines.runBlocking

/**
 * Hosts the one ExoPlayer instance. Living in a service is what lets audio keep
 * playing with the screen off and puts transport controls on the lock screen —
 * media3 supplies the notification itself.
 */
class PlaybackService : MediaSessionService() {

    private var session: MediaSession? = null

    override fun onCreate() {
        super.onCreate()
        val player = ExoPlayer.Builder(this)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                    .setUsage(C.USAGE_MEDIA)
                    .build(),
                /* handleAudioFocus = */ true,
            )
            .setHandleAudioBecomingNoisy(true)
            .build()

        session = MediaSession.Builder(this, player).build()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session

    override fun onTaskRemoved(rootIntent: Intent?) {
        // Swiping the app away from recents while paused can tear this
        // service down before PlaybackController's own save — launched
        // fire-and-forget on pause — has actually landed in the database.
        // This blocks briefly to flush the position that is about to be
        // lost, closing that race at the one point guaranteed to run first.
        persistCurrentPosition()
        val player = session?.player
        if (player == null || !player.playWhenReady || player.mediaItemCount == 0) {
            stopSelf()
        }
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        persistCurrentPosition()
        session?.run {
            player.release()
            release()
        }
        session = null
        super.onDestroy()
    }

    private fun persistCurrentPosition() {
        val player = session?.player ?: return
        val itemId = player.currentMediaItem?.mediaId?.toLongOrNull() ?: return
        val position = player.currentPosition.coerceAtLeast(0L)
        val library = (application as TubeVaultApp).container.library
        runBlocking { runCatching { library.recordPlayback(itemId, position) } }
    }
}
