package com.netdiag.core.wifi

import android.annotation.SuppressLint
import android.content.Context
import android.net.wifi.ScanResult
import android.net.wifi.WifiManager

/** Security posture of an access point, ranked for the UI. */
enum class SecurityRisk { SAFE, OK, WEAK, DANGER, UNKNOWN }

data class WifiAp(
    val ssid: String,
    val bssid: String,
    val rssi: Int,
    val frequency: Int,
    val channel: Int,
    val band: String,          // "2.4GHz" / "5GHz" / "6GHz"
    val security: String,      // e.g. "WPA3", "WPA2", "WEP", "オープン"
    val risk: SecurityRisk,
    val channelWidthMhz: Int,
    val isConnected: Boolean,
) {
    /** Rough signal quality 0..100 derived from RSSI. */
    val signalQuality: Int
        get() = when {
            rssi <= -100 -> 0
            rssi >= -50 -> 100
            else -> 2 * (rssi + 100)
        }
}

data class ChannelLoad(val band: String, val channel: Int, val apCount: Int)

/**
 * Wi-Fi environment survey: surrounding access points, their signal strength,
 * channel usage and security standard. Requires location permission to read
 * scan results (an Android platform rule, not our choice).
 */
class WifiSurvey(context: Context) {

    private val appContext = context.applicationContext
    private val wifi = appContext.getSystemService(Context.WIFI_SERVICE) as WifiManager

    fun isWifiEnabled(): Boolean = wifi.isWifiEnabled

    /** Asks the system to refresh scan results. Throttled by the OS on 9+. */
    @SuppressLint("MissingPermission")
    fun requestScan(): Boolean = try {
        @Suppress("DEPRECATION")
        wifi.startScan()
    } catch (_: Exception) {
        false
    }

    /** Returns surrounding access points sorted by signal strength (strongest first). */
    @SuppressLint("MissingPermission")
    fun scanResults(): List<WifiAp> {
        val connectedBssid = connectedBssid()
        val results: List<ScanResult> = try {
            wifi.scanResults ?: emptyList()
        } catch (_: SecurityException) {
            emptyList()
        }
        return results.map { it.toAp(connectedBssid) }
            // Some devices report the same BSSID twice; keep the strongest so
            // BSSID stays a safe unique key for the UI list.
            .sortedByDescending { it.rssi }
            .distinctBy { it.bssid }
    }

    /** Live RSSI / link speed for the currently connected network, if any. */
    @SuppressLint("MissingPermission")
    fun connectedRssi(): Int? {
        @Suppress("DEPRECATION")
        val info = wifi.connectionInfo ?: return null
        @Suppress("DEPRECATION")
        return if (info.networkId == -1) null else info.rssi
    }

    /** Aggregates AP counts per channel so callers can spot congestion. */
    fun channelLoad(aps: List<WifiAp>): List<ChannelLoad> =
        aps.groupBy { it.band to it.channel }
            .map { (key, list) -> ChannelLoad(key.first, key.second, list.size) }
            .sortedWith(compareBy({ it.band }, { it.channel }))

    @SuppressLint("MissingPermission")
    private fun connectedBssid(): String? {
        @Suppress("DEPRECATION")
        val info = wifi.connectionInfo ?: return null
        @Suppress("DEPRECATION")
        val bssid = info.bssid
        return if (bssid == null || bssid == "02:00:00:00:00:00") null else bssid
    }

    @Suppress("DEPRECATION")
    private fun ScanResult.toAp(connectedBssid: String?): WifiAp {
        val ssidName = (SSID ?: "").ifBlank { "(ステルス/非公開)" }
        val width = when (channelWidth) {
            ScanResult.CHANNEL_WIDTH_20MHZ -> 20
            ScanResult.CHANNEL_WIDTH_40MHZ -> 40
            ScanResult.CHANNEL_WIDTH_80MHZ -> 80
            ScanResult.CHANNEL_WIDTH_160MHZ -> 160
            ScanResult.CHANNEL_WIDTH_80MHZ_PLUS_MHZ -> 80
            else -> 20
        }
        val (security, risk) = classifySecurity(capabilities ?: "")
        return WifiAp(
            ssid = ssidName,
            bssid = BSSID ?: "",
            rssi = level,
            frequency = frequency,
            channel = frequencyToChannel(frequency),
            band = bandOf(frequency),
            security = security,
            risk = risk,
            channelWidthMhz = width,
            isConnected = BSSID != null && BSSID.equals(connectedBssid, ignoreCase = true),
        )
    }

    companion object {
        fun bandOf(freq: Int): String = when {
            freq in 2401..2499 -> "2.4GHz"
            freq in 4900..5899 -> "5GHz"
            freq in 5925..7125 -> "6GHz"
            else -> "?"
        }

        fun frequencyToChannel(freq: Int): Int = when {
            freq == 2484 -> 14
            freq in 2401..2472 -> (freq - 2407) / 5
            freq in 5000..5899 -> (freq - 5000) / 5
            freq in 5925..7125 -> (freq - 5950) / 5
            else -> -1
        }

        /** Maps an AP's capability string to a label and risk level. */
        fun classifySecurity(capabilities: String): Pair<String, SecurityRisk> {
            val caps = capabilities.uppercase()
            return when {
                caps.contains("WEP") -> "WEP（脆弱）" to SecurityRisk.DANGER
                caps.contains("SAE") || caps.contains("WPA3") -> "WPA3" to SecurityRisk.SAFE
                caps.contains("OWE") -> "拡張オープン(OWE)" to SecurityRisk.OK
                caps.contains("RSN") || caps.contains("WPA2") -> "WPA2" to SecurityRisk.OK
                caps.contains("WPA") -> "WPA（旧式）" to SecurityRisk.WEAK
                caps.contains("PSK") -> "WPA/WPA2" to SecurityRisk.OK
                // Only "[ESS]" with no security tokens means an open network.
                caps.contains("ESS") -> "オープン（暗号化なし）" to SecurityRisk.DANGER
                else -> "不明" to SecurityRisk.UNKNOWN
            }
        }
    }
}
