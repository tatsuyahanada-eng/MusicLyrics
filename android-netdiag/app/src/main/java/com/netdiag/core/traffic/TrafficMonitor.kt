package com.netdiag.core.traffic

import android.content.Context
import android.net.TrafficStats
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn

/** Instantaneous device-wide throughput sample. */
data class ThroughputSample(val rxBytesPerSec: Long, val txBytesPerSec: Long)

/**
 * Live device-wide throughput, sampled from [TrafficStats] (no root, no
 * special permission). Useful for spotting a runaway download or a flood.
 */
class TrafficMonitor(context: Context) {

    @Suppress("unused")
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
