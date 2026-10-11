package com.netdiag.core.net

import android.net.Network
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.ConnectException
import java.net.InetSocketAddress
import java.net.Socket

/** Overall health verdict for a one-tap wired-LAN cable/port check. */
enum class CableVerdict { PASS, WARN, FAIL }

/** Result of probing the gateway, bound directly to the Ethernet link. */
data class GatewayCheck(val reachable: Boolean, val rttMs: Long?, val note: String)

data class CableTestResult(
    val downstreamMbps: Int?,
    val upstreamMbps: Int?,
    val ipObtained: Boolean,
    val ip: String?,
    val gateway: String?,
    val gatewayCheck: GatewayCheck?,
    val verdict: CableVerdict,
    val summary: String,
)

/**
 * One-tap "is this LAN port/cable alive and usable" check, built entirely
 * from signals a phone can obtain without extra hardware: the adapter's
 * negotiated link rate, whether DHCP handed out an address, and whether the
 * gateway actually answers on the wire.
 *
 * The gateway probe is bound directly to the Ethernet [Network] object via
 * [Network.bindSocket] rather than relying on the app's process-wide network
 * pin — important because [PingTool] shells out to the system `ping` binary
 * as a *separate process*, which `ConnectivityManager.bindProcessToNetwork`
 * does not affect, so a plain ping here could silently test Wi-Fi instead of
 * the cable. A bound Java socket can't make that mistake.
 *
 * What this can NOT do: physical wire-map / continuity / split-pair
 * detection. That needs dedicated tester hardware with a remote loopback —
 * a USB-C-to-Ethernet adapter has no access to individual twisted pairs.
 */
object CableTest {

    // Common ports a LAN gateway is likely to answer on, tried in order; the
    // first response (open OR actively refused — both prove the host is
    // alive) wins.
    private val PROBE_PORTS = intArrayOf(80, 443, 53, 22, 7)
    private const val PORT_TIMEOUT_MS = 1200

    suspend fun run(
        network: Network,
        downstreamMbps: Int?,
        upstreamMbps: Int?,
        info: NetInfo,
    ): CableTestResult = withContext(Dispatchers.IO) {
        val gatewayCheck = info.gateway?.let { probeGateway(network, it) }

        val (verdict, summary) = when {
            !info.hasIpv4 -> CableVerdict.FAIL to
                "DHCPでIPアドレスを取得できていません。ケーブル・ポート・DHCPサーバーを確認してください。"
            gatewayCheck != null && !gatewayCheck.reachable -> CableVerdict.FAIL to
                "IPは取得できましたが、ゲートウェイが応答しません。配線またはルーター側の問題の可能性があります。"
            downstreamMbps != null && downstreamMbps in 1..99 -> CableVerdict.WARN to
                "リンク速度が${downstreamMbps}Mbpsと低めです。ケーブルの劣化・接触不良、" +
                    "またはポート/ケーブルが100M止まりの可能性があります。"
            else -> CableVerdict.PASS to
                "リンク・IP取得・ゲートウェイ応答、いずれも正常です。"
        }

        CableTestResult(
            downstreamMbps = downstreamMbps,
            upstreamMbps = upstreamMbps,
            ipObtained = info.hasIpv4,
            ip = info.ipv4,
            gateway = info.gateway,
            gatewayCheck = gatewayCheck,
            verdict = verdict,
            summary = summary,
        )
    }

    private fun probeGateway(network: Network, gatewayIp: String): GatewayCheck {
        for (port in PROBE_PORTS) {
            val start = System.nanoTime()
            val socket = Socket()
            try {
                network.bindSocket(socket)
                socket.connect(InetSocketAddress(gatewayIp, port), PORT_TIMEOUT_MS)
                return GatewayCheck(true, elapsedMs(start), "port $port 応答あり")
            } catch (_: ConnectException) {
                // Actively refused (fast RST) still proves the host is alive —
                // just that this particular port is closed.
                return GatewayCheck(true, elapsedMs(start), "到達（ポートは閉）")
            } catch (_: Exception) {
                // Timeout / no route on this port — try the next one.
            } finally {
                runCatching { socket.close() }
            }
        }
        return GatewayCheck(false, null, "応答なし")
    }

    private fun elapsedMs(startNanos: Long) = (System.nanoTime() - startNanos) / 1_000_000
}
