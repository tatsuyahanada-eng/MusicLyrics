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

/** How congested a single candidate channel is. `score` 0 = empty, higher = busier. */
data class ChannelScore(
    val channel: Int,
    val score: Double,
    val apCount: Int,     // APs whose center sits exactly on this channel
    val isCurrent: Boolean,
) {
    /** Coarse label so the UI can colour-code without re-deriving thresholds. */
    val rating: ChannelRating
        get() = when {
            score < 0.30 -> ChannelRating.CLEAR
            score < 1.20 -> ChannelRating.MODERATE
            else -> ChannelRating.BUSY
        }
}

enum class ChannelRating { CLEAR, MODERATE, BUSY }

/** Per-band recommendation of the least-congested channels to move to. */
data class BandAdvice(
    val band: String,
    val currentChannel: Int?,     // channel the connected AP uses, if any
    val recommended: List<Int>,   // clearest candidate channels, best first
    val ranked: List<ChannelScore>,
)

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

    /** Recommends the clearest channels per band so the user can dodge congestion. */
    fun channelAdvice(aps: List<WifiAp>): List<BandAdvice> = computeChannelAdvice(aps)

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
        // The channels worth recommending: for 2.4GHz only 1/6/11 are truly
        // non-overlapping; for 5/6GHz we prefer non-DFS blocks that home routers
        // can pick without radar-detection delays.
        private val CANDIDATES_24 = listOf(1, 6, 11)
        private val CANDIDATES_5 = listOf(36, 40, 44, 48, 149, 153, 157, 161)
        private val CANDIDATES_6 = listOf(37, 53, 69, 85, 101, 117, 133, 149)

        /**
         * Scores each recommended candidate channel by how much surrounding
         * traffic would overlap it, weighted by that AP's signal strength, then
         * ranks them clearest-first. Pure function so it is trivial to unit test.
         */
        fun computeChannelAdvice(aps: List<WifiAp>): List<BandAdvice> =
            listOf("2.4GHz", "5GHz", "6GHz").mapNotNull { band ->
                val bandAps = aps.filter { it.band == band && it.channel > 0 }
                if (bandAps.isEmpty()) return@mapNotNull null
                val current = bandAps.firstOrNull { it.isConnected }?.channel
                val candidates = when (band) {
                    "2.4GHz" -> CANDIDATES_24
                    "5GHz" -> CANDIDATES_5
                    else -> CANDIDATES_6
                }
                val ranked = candidates.map { ch ->
                    ChannelScore(
                        channel = ch,
                        score = channelInterference(ch, bandAps),
                        apCount = bandAps.count { it.channel == ch },
                        isCurrent = ch == current,
                    )
                }.sortedBy { it.score }
                BandAdvice(
                    band = band,
                    currentChannel = current,
                    recommended = ranked.take(3).map { it.channel },
                    ranked = ranked,
                )
            }

        /**
         * Overlap-weighted interference on [candidate] from every AP in the band.
         * Wi-Fi channel numbers are spaced 5 MHz apart, so a channel that is
         * `w` MHz wide reaches `w / 5` channel-numbers before its energy fades;
         * closer APs and stronger signals count for more.
         */
        private fun channelInterference(candidate: Int, aps: List<WifiAp>): Double =
            aps.sumOf { ap ->
                val reach = (ap.channelWidthMhz / 5.0).coerceAtLeast(4.0)
                val distance = kotlin.math.abs(ap.channel - candidate)
                val overlap = (1.0 - distance / reach).coerceAtLeast(0.0)
                overlap * (ap.signalQuality / 100.0)
            }

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
