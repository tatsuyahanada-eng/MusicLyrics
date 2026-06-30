package com.netdiag.core.traffic

import android.app.AppOpsManager
import android.app.usage.NetworkStats
import android.app.usage.NetworkStatsManager
import android.content.Context
import android.net.ConnectivityManager
import android.net.TrafficStats
import android.os.Process
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext

/** Instantaneous device-wide throughput sample. */
data class ThroughputSample(val rxBytesPerSec: Long, val txBytesPerSec: Long)

/** Cumulative Wi-Fi data usage attributed to one app. */
data class AppTraffic(
    val uid: Int,
    val label: String,
    val packageName: String?,
    val rxBytes: Long,
    val txBytes: Long,
) {
    val totalBytes: Long get() = rxBytes + txBytes
}

/**
 * Traffic insight without root or packet capture:
 *  - [throughput] streams live device-wide up/down speed (spot a runaway
 *    download or a flood).
 *  - [wifiUsageByApp] attributes Wi-Fi data to apps so you can see *who* is
 *    eating the bandwidth (needs the user to grant "Usage access").
 *
 * Protocol-level capture (HTTP/DNS/NTP ratios) requires a VpnService and is
 * intentionally left for a later phase to keep v1 safe and connectivity-safe.
 */
class TrafficMonitor(context: Context) {

    private val appContext = context.applicationContext

    fun throughput(intervalMs: Long = 1000): Flow<ThroughputSample> = flow {
        var lastRx = TrafficStats.getTotalRxBytes()
        var lastTx = TrafficStats.getTotalTxBytes()
        if (lastRx == TrafficStats.UNSUPPORTED.toLong()) {
            // Device doesn't expose counters; emit zeros rather than crash.
            while (true) {
                emit(ThroughputSample(0, 0))
                delay(intervalMs)
            }
        }
        while (true) {
            delay(intervalMs)
            val rx = TrafficStats.getTotalRxBytes()
            val tx = TrafficStats.getTotalTxBytes()
            val rxRate = ((rx - lastRx) * 1000 / intervalMs).coerceAtLeast(0)
            val txRate = ((tx - lastTx) * 1000 / intervalMs).coerceAtLeast(0)
            lastRx = rx
            lastTx = tx
            emit(ThroughputSample(rxRate, txRate))
        }
    }.flowOn(Dispatchers.Default)

    /** True if the user has granted the special "Usage access" permission. */
    fun hasUsageAccess(): Boolean {
        val appOps = appContext.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        val mode = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
            appOps.unsafeCheckOpNoThrow(
                AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), appContext.packageName,
            )
        } else {
            @Suppress("DEPRECATION")
            appOps.checkOpNoThrow(
                AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), appContext.packageName,
            )
        }
        return mode == AppOpsManager.MODE_ALLOWED
    }

    /**
     * Returns per-app Wi-Fi usage since [sinceMillisAgo] ago, largest first.
     * Requires [hasUsageAccess]; returns an empty list otherwise.
     */
    suspend fun wifiUsageByApp(
        sinceMillisAgo: Long = 24 * 60 * 60 * 1000,
        limit: Int = 25,
    ): List<AppTraffic> = withContext(Dispatchers.IO) {
        if (!hasUsageAccess()) return@withContext emptyList()
        val nsm = appContext.getSystemService(Context.NETWORK_STATS_SERVICE) as NetworkStatsManager
        val end = System.currentTimeMillis()
        val start = end - sinceMillisAgo
        val perUid = HashMap<Int, LongArray>() // uid -> [rx, tx]
        try {
            @Suppress("DEPRECATION")
            val stats = nsm.querySummary(ConnectivityManager.TYPE_WIFI, null, start, end)
            val bucket = NetworkStats.Bucket()
            stats.use {
                while (it.hasNextBucket()) {
                    it.getNextBucket(bucket)
                    val arr = perUid.getOrPut(bucket.uid) { LongArray(2) }
                    arr[0] += bucket.rxBytes
                    arr[1] += bucket.txBytes
                }
            }
        } catch (_: Exception) {
            return@withContext emptyList()
        }

        perUid.entries
            .map { (uid, arr) ->
                AppTraffic(uid, labelForUid(uid), packageForUid(uid), arr[0], arr[1])
            }
            .sortedByDescending { it.totalBytes }
            .take(limit)
    }

    private fun packageForUid(uid: Int): String? = when (uid) {
        android.os.Process.SYSTEM_UID -> "android.system"
        else -> appContext.packageManager.getPackagesForUid(uid)?.firstOrNull()
    }

    private fun labelForUid(uid: Int): String {
        return when (uid) {
            android.os.Process.SYSTEM_UID -> "システム"
            else -> {
                val pm = appContext.packageManager
                val pkg = pm.getPackagesForUid(uid)?.firstOrNull() ?: return "UID $uid"
                try {
                    pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
                } catch (_: Exception) {
                    pkg
                }
            }
        }
    }

    companion object {
        fun formatBytes(bytes: Long): String {
            if (bytes < 1024) return "$bytes B"
            val units = arrayOf("KB", "MB", "GB", "TB")
            var value = bytes.toDouble() / 1024
            var i = 0
            while (value >= 1024 && i < units.size - 1) {
                value /= 1024; i++
            }
            return String.format("%.1f %s", value, units[i])
        }

        fun formatRate(bytesPerSec: Long): String = formatBytes(bytesPerSec) + "/s"
    }
}
