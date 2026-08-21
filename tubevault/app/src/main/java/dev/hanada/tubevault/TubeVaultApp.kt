package dev.hanada.tubevault

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import dev.hanada.tubevault.data.AppDatabase
import dev.hanada.tubevault.data.LibraryRepository
import dev.hanada.tubevault.data.SettingsStore
import dev.hanada.tubevault.download.DownloadCenter
import dev.hanada.tubevault.importer.LocalImporter
import dev.hanada.tubevault.playback.PlaybackController
import dev.hanada.tubevault.potoken.PoTokenProvider
import dev.hanada.tubevault.ytdlp.YtDlpEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

/**
 * Hand-rolled service locator. A single-user app with six collaborators does
 * not need a DI framework, and this keeps the build free of another annotation
 * processor.
 */
class AppContainer(private val app: Application) {

    val appContext: Context get() = app

    val database: AppDatabase by lazy { AppDatabase.build(app) }
    val settings: SettingsStore by lazy { SettingsStore(app) }
    val poTokens: PoTokenProvider by lazy { PoTokenProvider(app) }

    val engine: YtDlpEngine by lazy { YtDlpEngine(app, settings, poTokens) }

    val library: LibraryRepository by lazy {
        LibraryRepository(app, database.categoryDao(), database.mediaDao())
    }

    val downloads: DownloadCenter by lazy { DownloadCenter(app, engine, library) }

    val importer: LocalImporter by lazy { LocalImporter(app, library) }

    val playback: PlaybackController by lazy { PlaybackController(app, library) }

    /** A URL shared into the app from YouTube, waiting for the search screen. */
    val sharedLink = MutableStateFlow<String?>(null)
}

class TubeVaultApp : Application() {

    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        createNotificationChannel()

        // Unpacking the yt-dlp payload takes a moment; get it out of the way
        // before the user's first search rather than during it.
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            runCatching {
                container.library.seedDefaultsIfEmpty()
                container.library.harmonizeLegacyColors()
                container.library.ensureFoldersExist()
                container.library.pruneMissingFiles()
            }
            runCatching { container.engine.ensureInit() }
            refreshYtDlpIfStale()
        }
    }

    /**
     * The yt-dlp bundled into the APK is frozen at whatever the library shipped,
     * while YouTube's extraction breaks on its own schedule — so the binary is
     * refreshed in the background rather than waiting for the user to find the
     * button in settings after something already failed.
     */
    private suspend fun refreshYtDlpIfStale() {
        val lastUpdate = container.settings.current.ytDlpUpdatedAt
        val age = System.currentTimeMillis() - lastUpdate
        if (lastUpdate != 0L && age < UPDATE_INTERVAL_MS) return

        val updated = runCatching { container.engine.updateYtDlp() }.isSuccess
        if (updated) {
            container.settings.update { it.copy(ytDlpUpdatedAt = System.currentTimeMillis()) }
        }
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_DOWNLOADS,
            getString(R.string.channel_downloads_name),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = getString(R.string.channel_downloads_desc)
            setShowBadge(false)
        }
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(channel)
    }

    companion object {
        const val CHANNEL_DOWNLOADS = "downloads"

        /** Weekly is often enough to keep pace with YouTube's changes. */
        private const val UPDATE_INTERVAL_MS = 7L * 24 * 60 * 60 * 1000
    }
}
