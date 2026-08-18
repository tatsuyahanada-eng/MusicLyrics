package dev.hanada.tubevault.download

import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import dev.hanada.tubevault.MainActivity
import dev.hanada.tubevault.R
import dev.hanada.tubevault.TubeVaultApp
import kotlinx.coroutines.launch

/**
 * Keeps the process alive and shows progress while [DownloadCenter] works.
 * It owns no state: the queue lives in the application container, and this
 * service simply mirrors it into a notification and stops once it drains.
 */
class DownloadService : LifecycleService() {

    override fun onCreate() {
        super.onCreate()
        startAsForeground(buildNotification(activeCount = 0, running = null))

        val center = (application as TubeVaultApp).container.downloads
        lifecycleScope.launch {
            center.jobs.collect { jobs ->
                val active = jobs.filter { it.isActive }
                if (active.isEmpty()) {
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                } else {
                    val running = active.firstOrNull { it.state == JobState.RUNNING }
                    notify(buildNotification(active.size, running))
                }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        return START_NOT_STICKY
    }

    private fun startAsForeground(notification: Notification) {
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
        } else {
            0
        }
        runCatching {
            ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, type)
        }.onFailure { Log.w(TAG, "startForeground refused", it) }
    }

    private fun notify(notification: Notification) {
        runCatching {
            NotificationManagerCompat.from(this).notify(NOTIFICATION_ID, notification)
        }.onFailure { Log.w(TAG, "notification suppressed", it) }
    }

    private fun buildNotification(activeCount: Int, running: DownloadJob?): Notification {
        val contentIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        val title = when {
            running != null -> running.title
            activeCount > 0 -> "ダウンロードを準備しています"
            else -> "ダウンロード"
        }
        val text = if (activeCount > 1) "残り ${activeCount} 件" else running?.statusLine.orEmpty()

        val builder = NotificationCompat.Builder(this, TubeVaultApp.CHANNEL_DOWNLOADS)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(contentIntent)
            .setOngoing(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)

        if (running != null && running.progress > 0f) {
            builder.setProgress(100, (running.progress * 100).toInt(), false)
        } else {
            builder.setProgress(0, 0, true)
        }

        return builder.build()
    }

    companion object {
        private const val TAG = "DownloadService"
        private const val NOTIFICATION_ID = 4201

        fun start(context: Context) {
            val intent = Intent(context, DownloadService::class.java)
            runCatching { ContextCompat.startForegroundService(context, intent) }
                .onFailure { Log.w(TAG, "could not start download service", it) }
        }
    }
}
