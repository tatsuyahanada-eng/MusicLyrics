package com.netdiag.core.net

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import java.io.BufferedReader
import java.io.InputStreamReader
import kotlin.math.sqrt

/** One streamed line of ping output. */
sealed interface PingEvent {
    data class Reply(val seq: Int, val ttl: Int?, val rttMs: Double) : PingEvent
    data class Timeout(val seq: Int) : PingEvent
    data class Summary(
        val transmitted: Int,
        val received: Int,
        val lossPercent: Double,
        val minMs: Double?,
        val avgMs: Double?,
        val maxMs: Double?,
        /** Jitter, derived from the rtt samples we saw (population stddev). */
        val jitterMs: Double?,
    ) : PingEvent
    data class Error(val message: String) : PingEvent
}

/**
 * Advanced ICMP ping built on the system `ping` binary (no root required).
 * Streams each reply so the UI can show packet loss and jitter building up
 * in real time. Use this to diagnose "occasionally drops" / "randomly slow".
 */
object PingTool {

    private val REPLY_REGEX = Regex(
        "icmp_seq=(\\d+).*?(?:ttl=(\\d+))?.*?time=([0-9.]+)\\s*ms",
        RegexOption.IGNORE_CASE,
    )
    private val SUMMARY_REGEX = Regex(
        "(\\d+) packets transmitted, (\\d+)\\s+(?:packets )?received.*?([0-9.]+)% packet loss",
        RegexOption.IGNORE_CASE,
    )

    fun ping(
        host: String,
        count: Int = 10,
        packetSizeBytes: Int = 56,
        intervalMs: Int = 1000,
        perPacketTimeoutSec: Int = 2,
    ): Flow<PingEvent> = flow {
        val cmd = listOf(
            "ping",
            "-c", count.toString(),
            "-i", (intervalMs / 1000.0).coerceAtLeast(0.2).toString(),
            "-s", packetSizeBytes.toString(),
            "-W", perPacketTimeoutSec.toString(),
            host,
        )
        val process = try {
            ProcessBuilder(cmd).redirectErrorStream(true).start()
        } catch (e: Exception) {
            emit(PingEvent.Error("ping を起動できません: ${e.message}"))
            return@flow
        }

        val rtts = ArrayList<Double>()
        var transmitted = 0
        var received = 0
        var loss = 0.0
        var sawSummary = false

        BufferedReader(InputStreamReader(process.inputStream)).use { reader ->
            var line: String?
            while (reader.readLine().also { line = it } != null) {
                val l = line!!.trim()
                val reply = REPLY_REGEX.find(l)
                val summary = SUMMARY_REGEX.find(l)
                when {
                    reply != null -> {
                        val seq = reply.groupValues[1].toIntOrNull() ?: continue
                        val ttl = reply.groupValues[2].toIntOrNull()
                        val rtt = reply.groupValues[3].toDoubleOrNull() ?: continue
                        rtts.add(rtt)
                        emit(PingEvent.Reply(seq, ttl, rtt))
                    }
                    summary != null -> {
                        sawSummary = true
                        transmitted = summary.groupValues[1].toIntOrNull() ?: transmitted
                        received = summary.groupValues[2].toIntOrNull() ?: received
                        loss = summary.groupValues[3].toDoubleOrNull() ?: loss
                    }
                }
            }
        }
        runCatching { process.waitFor() }

        if (!sawSummary && rtts.isEmpty()) {
            emit(PingEvent.Error("応答がありません（宛先に到達できないか、ICMP がブロックされています）"))
            return@flow
        }

        emit(
            PingEvent.Summary(
                transmitted = if (transmitted > 0) transmitted else count,
                received = if (received > 0) received else rtts.size,
                lossPercent = loss,
                minMs = rtts.minOrNull(),
                avgMs = rtts.average().takeIf { rtts.isNotEmpty() },
                maxMs = rtts.maxOrNull(),
                jitterMs = stddev(rtts),
            )
        )
    }.flowOn(Dispatchers.IO)

    private fun stddev(values: List<Double>): Double? {
        if (values.size < 2) return null
        val mean = values.average()
        val variance = values.sumOf { (it - mean) * (it - mean) } / values.size
        return sqrt(variance)
    }
}
