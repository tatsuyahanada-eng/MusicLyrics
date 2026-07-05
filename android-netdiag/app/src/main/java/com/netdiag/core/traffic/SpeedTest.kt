package com.netdiag.core.traffic

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import java.io.IOException
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
 * Active internet speed test: actually downloads and uploads test data (via
 * Cloudflare's public speed endpoints) and measures the achieved throughput —
 * the accurate way to measure line speed. The download runs as repeated
 * fixed-size chunks until the time budget is spent, so it works regardless of
 * the server's per-request size cap.
 */
object SpeedTest {

    private const val DOWN_BASE = "https://speed.cloudflare.com/__down"
    private const val UP_URL = "https://speed.cloudflare.com/__up"
    private const val CHUNK_BYTES = 25_000_000L      // 25 MB per download request

    fun run(durationMsEach: Long = 10_000): Flow<SpeedEvent> = flow {
        val latency = measureLatency()
        if (latency != null) emit(SpeedEvent.Latency(latency))

        // ---- Download: loop chunk requests until the time budget is spent ----
        var downMbps = 0.0
        try {
            val buf = ByteArray(65536)
            var total = 0L
            val start = System.nanoTime()
            var lastEmit = 0L
            loop@ while ((System.nanoTime() - start) / 1_000_000 < durationMsEach) {
                val conn = (URL("$DOWN_BASE?bytes=$CHUNK_BYTES").openConnection() as HttpURLConnection).apply {
                    connectTimeout = 8000
                    readTimeout = 8000
                }
                if (conn.responseCode !in 200..299) {
                    conn.disconnect()
                    if (total == 0L) throw IOException("HTTP ${conn.responseCode}")
                    break@loop
                }
                conn.inputStream.use { input ->
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        total += n
                        val elapsed = (System.nanoTime() - start) / 1_000_000
                        if (elapsed >= durationMsEach) break
                        if (elapsed - lastEmit >= 200) {
                            lastEmit = elapsed
                            downMbps = mbps(total, elapsed)
                            emit(SpeedEvent.Download(downMbps, (elapsed.toFloat() / durationMsEach).coerceIn(0f, 1f)))
                        }
                    }
                }
                conn.disconnect()
            }
            val elapsed = ((System.nanoTime() - start) / 1_000_000).coerceAtLeast(1)
            downMbps = mbps(total, elapsed)
            emit(SpeedEvent.Download(downMbps, 1f))
        } catch (e: Exception) {
            emit(SpeedEvent.Error("下り測定に失敗しました: ${e.message}"))
            return@flow
        }

        // ---- Upload: one chunked POST streamed until the time budget is spent ----
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
                    if (elapsed - lastEmit >= 200) {
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
                val conn = (URL("$DOWN_BASE?bytes=0").openConnection() as HttpURLConnection).apply {
                    connectTimeout = 3000
                    readTimeout = 3000
                }
                conn.inputStream.use { it.read() }
                conn.disconnect()
                val ms = (System.nanoTime() - start) / 1_000_000
                val b = best
                if (b == null || ms < b) best = ms
            } catch (_: Exception) {
                // ignore
            }
        }
        return best
    }

    private fun mbps(bytes: Long, elapsedMs: Long): Double =
        if (elapsedMs <= 0) 0.0 else bytes * 8.0 / (elapsedMs / 1000.0) / 1_000_000.0
}
