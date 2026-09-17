package com.netdiag.core.monitor

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.netdiag.MainActivity
import com.netdiag.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Keeps [LanMonitor] running while the app is in the background and turns each
 * new finding into a notification, so a problem that only happens while you're
 * away from the phone still gets noticed.
 *
 * Runs as a foreground service because that is the only way Android will let a
 * socket keep listening indefinitely; the persistent "監視中" notification is
 * the visible, honest signal that monitoring is active.
 */
class MonitorService : Service() {

    private var scope: CoroutineScope? = null
    private var alertCounter = ONGOING_NOTIFICATION_ID + 1

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannels()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }

        startForegroundSafely()
        AlertStore.init(this)
        LanMonitor.start(this)

        if (scope == null) {
            val newScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
            scope = newScope
            newScope.launch {
                AlertStore.raised.collect { alert ->
                    if (alert.severity != AlertSeverity.INFO) notifyAlert(alert)
                }
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        scope?.cancel()
        scope = null
        LanMonitor.stop()
        super.onDestroy()
    }

    private fun startForegroundSafely() {
        val stopIntent = PendingIntent.getService(
            this, 1,
            Intent(this, MonitorService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(this, CHANNEL_ONGOING)
            .setContentTitle("ネットワーク監視中")
            .setContentText("LANの異常を監視しています")
            .setSmallIcon(R.drawable.ic_stat_monitor)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(openAppIntent())
            .addAction(0, "停止", stopIntent)
            .build()

        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
        } else {
            0
        }
        ServiceCompat.startForeground(this, ONGOING_NOTIFICATION_ID, notification, type)
    }

    private fun notifyAlert(alert: Alert) {
        val manager = NotificationManagerCompat.from(this)
        if (!manager.areNotificationsEnabled()) return

        val notification = NotificationCompat.Builder(this, CHANNEL_ALERTS)
            .setContentTitle("[${alert.category.label}] ${alert.title}")
            .setContentText(alert.detail)
            .setStyle(NotificationCompat.BigTextStyle().bigText(alert.detail))
            .setSmallIcon(R.drawable.ic_stat_monitor)
            .setAutoCancel(true)
            .setPriority(
                if (alert.severity == AlertSeverity.CRITICAL) NotificationCompat.PRIORITY_HIGH
                else NotificationCompat.PRIORITY_DEFAULT
            )
            .setContentIntent(openAppIntent())
            .build()

        // POST_NOTIFICATIONS is checked above via areNotificationsEnabled().
        runCatching { manager.notify(alertCounter++, notification) }
    }

    private fun openAppIntent(): PendingIntent = PendingIntent.getActivity(
        this, 0,
        Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_IMMUTABLE,
    )

    private fun createChannels() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(NotificationManager::class.java) ?: return
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ONGOING, "監視の稼働状態",
                NotificationManager.IMPORTANCE_LOW,
            ).apply { description = "監視が動いていることを示す常駐通知です" }
        )
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ALERTS, "ネットワーク異常アラート",
                NotificationManager.IMPORTANCE_HIGH,
            ).apply {
                description = "不正DHCP・ゲートウェイ変化・ブロードキャストストームなどの通知"
                enableVibration(true)
            }
        )
    }

    companion object {
        private const val CHANNEL_ONGOING = "netscope_monitor_ongoing"
        private const val CHANNEL_ALERTS = "netscope_monitor_alerts"
        private const val ONGOING_NOTIFICATION_ID = 1001
        const val ACTION_STOP = "com.netdiag.action.STOP_MONITOR"

        fun start(context: Context) {
            ContextCompat.startForegroundService(
                context, Intent(context, MonitorService::class.java)
            )
        }

        fun stop(context: Context) {
            context.startService(
                Intent(context, MonitorService::class.java).setAction(ACTION_STOP)
            )
        }
    }
}
