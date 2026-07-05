package com.netdiag.core.traffic

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import java.net.HttpURLConnection
import java.net.URL

/** Progress / result of an active line-speed test. */
sealed interface SpeedEvent {
    data class Latency(val ms: Long) : SpeedEvent
    data class Download(val mbps: Double, val progress: Float) : SpeedEvent
    data class Upload(val mbps: Double, val progress: Float) : SpeedEvent
    data class Done(val downMbps: Double, val upMbps: Double, val latencyMs: Long?) : SpeedEvent
    data class Error(val message: String) : SpeedEvent
}

/**
 * Active internet speed test: it actually downloads and uploads test data
 * (via Cloudflare's public speed endpoints) and measures the achieved
 * throughput — the accurate way to measure line speed, unlike passively
 * watching ambient traffic.
 */
object SpeedTest {

    private const val DOWN_URL = "https://speed.cloudflare.com/__down?bytes=1000000000"
    private const val UP_URL = "https://speed.cloudflare.com/__up"
    private const val LAT_URL = "https://speed.cloudflare.com/__down?bytes=0"

    fun run(durationMsEach: Long = 10_000): Flow<SpeedEvent> = flow {
        // 1) Latency (a few quick round trips).
        val latency = measureLatency()
        if (latency != null) emit(SpeedEvent.Latency(latency))

        // 2) Download.
        var downMbps = 0.0
        try {
            val conn = (URL(DOWN_URL).openConnection() as HttpURLConnection).apply {
                connectTimeout = 8000
                readTimeout = 8000
                setRequestProperty("Accept-Encoding", "identity")
            }
            conn.inputStream.use { input ->
                val buf = ByteArray(65536)
                var total = 0L
                val start = System.nanoTime()
                var lastEmit = 0L
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    total += n
                    val elapsed = (System.nanoTime() - start) / 1_000_000
                    if (elapsed >= durationMsEach) break
                    if (elapsed - lastEmit >= 250) {
                        lastEmit = elapsed
                        downMbps = mbps(total, elapsed)
                        emit(SpeedEvent.Download(downMbps, (elapsed.toFloat() / durationMsEach).coerceIn(0f, 1f)))
                    }
                }
                val elapsed = ((System.nanoTime() - start) / 1_000_000).coerceAtLeast(1)
                downMbps = mbps(total, elapsed)
            }
            conn.disconnect()
            emit(SpeedEvent.Download(downMbps, 1f))
        } catch (e: Exception) {
            emit(SpeedEvent.Error("下り測定に失敗しました: ${e.message}"))
            return@flow
        }

        // 3) Upload.
        var upMbps = 0.0
        try {
            val conn = (URL(UP_URL).openConnection() as HttpURLConnection).apply {
                connectTimeout = 8000
                readTimeout = 8000
                doOutput = true
                requestMethod = "POST"
                setChunkedStreamingMode(65536)
                setRequestProperty("Content-Type", "application/octet-stream")
            }
            conn.outputStream.use { out ->
                val buf = ByteArray(65536)
                var total = 0L
                val start = System.nanoTime()
                var lastEmit = 0L
                while (true) {
                    out.write(buf)
                    total += buf.size
                    val elapsed = (System.nanoTime() - start) / 1_000_000
                    if (elapsed >= durationMsEach) break
                    if (elapsed - lastEmit >= 250) {
                        lastEmit = elapsed
                        upMbps = mbps(total, elapsed)
                        emit(SpeedEvent.Upload(upMbps, (elapsed.toFloat() / durationMsEach).coerceIn(0f, 1f)))
                    }
                }
                val elapsed = ((System.nanoTime() - start) / 1_000_000).coerceAtLeast(1)
                upMbps = mbps(total, elapsed)
            }
            runCatching { conn.responseCode }
            conn.disconnect()
            emit(SpeedEvent.Upload(upMbps, 1f))
        } catch (_: Exception) {
            // Keep the download result even if upload fails.
        }

        emit(SpeedEvent.Done(downMbps, upMbps, latency))
    }.flowOn(Dispatchers.IO)

    private fun measureLatency(): Long? {
        var best: Long? = null
        repeat(4) {
            try {
                val start = System.nanoTime()
                val conn = (URL(LAT_URL).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 3000
                    readTimeout = 3000
                }
                conn.inputStream.use { it.read() }
                conn.disconnect()
                val ms = (System.nanoTime() - start) / 1_000_000
                if (best == null || ms < best!!) best = ms
            } catch (_: Exception) {
                // ignore
            }
        }
        return best
    }

    private fun mbps(bytes: Long, elapsedMs: Long): Double =
        if (elapsedMs <= 0) 0.0 else bytes * 8.0 / (elapsedMs / 1000.0) / 1_000_000.0
}
