package com.netdiag.core.net

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.InetAddress

/** One hop along the path to the destination. */
data class Hop(
    val ttl: Int,
    val ip: String?,
    val hostname: String?,
    val rttMs: Double?,
    val reached: Boolean,
)

sealed interface TraceEvent {
    data class HopFound(val hop: Hop) : TraceEvent
    data class Done(val reached: Boolean, val hops: Int) : TraceEvent
    data class Error(val message: String) : TraceEvent
}

/**
 * Traceroute implemented by repeatedly running `ping` with an increasing TTL
 * and reading which router reports "Time to live exceeded". Works without
 * root because it never opens raw sockets. Lets you tell whether a problem is
 * inside your LAN, at your router, or out on the provider's network.
 */
object Traceroute {

    private val FROM_REGEX = Regex("[Ff]rom\\s+([0-9]{1,3}(?:\\.[0-9]{1,3}){3})")
    private val TIME_REGEX = Regex("time=([0-9.]+)\\s*ms", RegexOption.IGNORE_CASE)

    fun trace(
        host: String,
        maxHops: Int = 30,
        perHopTimeoutSec: Int = 2,
        resolveNames: Boolean = true,
    ): Flow<TraceEvent> = flow {
        val target = try {
            InetAddress.getByName(host).hostAddress ?: host
        } catch (e: Exception) {
            emit(TraceEvent.Error("ホスト名を解決できません: $host"))
            return@flow
        }

        var reached = false
        var ttl = 1
        while (ttl <= maxHops && !reached) {
            val cmd = listOf(
                "ping", "-c", "1", "-t", ttl.toString(),
                "-W", perHopTimeoutSec.toString(), target,
            )
            val output = runPing(cmd)
            if (output == null) {
                emit(TraceEvent.Error("ping を起動できません"))
                return@flow
            }

            val hopIp = FROM_REGEX.find(output)?.groupValues?.get(1)
            val rtt = TIME_REGEX.find(output)?.groupValues?.get(1)?.toDoubleOrNull()
            // We've reached the destination when the echo reply itself comes
            // back from the target address.
            reached = output.contains("bytes from") &&
                (hopIp == target || hopIp == null) ||
                hopIp == target

            val name = if (resolveNames && hopIp != null) reverse(hopIp) else null
            emit(
                TraceEvent.HopFound(
                    Hop(
                        ttl = ttl,
                        ip = hopIp,
                        hostname = name,
                        rttMs = rtt,
                        reached = reached,
                    )
                )
            )
            ttl++
        }
        emit(TraceEvent.Done(reached, ttl - 1))
    }.flowOn(Dispatchers.IO)

    private fun runPing(cmd: List<String>): String? {
        return try {
            val process = ProcessBuilder(cmd).redirectErrorStream(true).start()
            val text = BufferedReader(InputStreamReader(process.inputStream)).use { it.readText() }
            runCatching { process.waitFor() }
            text
        } catch (_: Exception) {
            null
        }
    }

    private fun reverse(ip: String): String? = try {
        val name = InetAddress.getByName(ip).canonicalHostName
        if (name == ip) null else name
    } catch (_: Exception) {
        null
    }
}
