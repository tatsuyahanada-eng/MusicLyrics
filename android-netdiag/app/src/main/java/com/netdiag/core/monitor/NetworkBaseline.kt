package com.netdiag.core.monitor

import android.annotation.SuppressLint
import android.content.Context
import android.net.wifi.WifiManager
import com.netdiag.core.net.NetInfo
import com.netdiag.core.net.NetworkInfoProvider
import org.json.JSONArray
import org.json.JSONObject

/**
 * What "normal" looks like on one network. Captured the first time a network
 * is seen, then compared on every monitor tick — a gateway, DHCP server or DNS
 * server that changes underneath you is the signature of a rogue DHCP server
 * or a man-in-the-middle, and is exactly the kind of fault that is otherwise
 * very hard to notice.
 */
data class NetworkSnapshot(
    val networkKey: String,          // SSID, or the subnet when wired
    val ssid: String? = null,
    val bssid: String? = null,       // AP MAC; a change can mean an evil twin
    val gateway: String? = null,
    val dhcpServer: String? = null,
    val dnsServers: List<String> = emptyList(),
    val subnet: String? = null,
    val capturedAt: Long = System.currentTimeMillis(),
) {
    fun toJson(): String = JSONObject().apply {
        put("networkKey", networkKey)
        put("ssid", ssid ?: JSONObject.NULL)
        put("bssid", bssid ?: JSONObject.NULL)
        put("gateway", gateway ?: JSONObject.NULL)
        put("dhcpServer", dhcpServer ?: JSONObject.NULL)
        put("dnsServers", JSONArray(dnsServers))
        put("subnet", subnet ?: JSONObject.NULL)
        put("capturedAt", capturedAt)
    }.toString()

    companion object {
        fun fromJson(raw: String): NetworkSnapshot? = runCatching {
            val o = JSONObject(raw)
            val dns = o.optJSONArray("dnsServers")
            NetworkSnapshot(
                networkKey = o.getString("networkKey"),
                ssid = o.optStringOrNull("ssid"),
                bssid = o.optStringOrNull("bssid"),
                gateway = o.optStringOrNull("gateway"),
                dhcpServer = o.optStringOrNull("dhcpServer"),
                dnsServers = buildList {
                    if (dns != null) for (i in 0 until dns.length()) add(dns.getString(i))
                },
                subnet = o.optStringOrNull("subnet"),
                capturedAt = o.optLong("capturedAt"),
            )
        }.getOrNull()

        private fun JSONObject.optStringOrNull(name: String): String? =
            if (isNull(name)) null else optString(name).takeIf { it.isNotBlank() }
    }
}

/**
 * Reads the current network configuration and remembers a baseline per
 * network, so comparisons only happen within the same network (moving between
 * Wi-Fi networks must not raise false alarms).
 */
class NetworkBaseline(context: Context) {

    private val appContext = context.applicationContext
    private val infoProvider = NetworkInfoProvider(appContext)
    private val prefs = appContext
        .getSharedPreferences("netdiag_baseline", Context.MODE_PRIVATE)

    /** Reads the live configuration of whatever network we're on right now. */
    @SuppressLint("MissingPermission")
    fun capture(): NetworkSnapshot? {
        val info: NetInfo = infoProvider.current()
        if (!info.hasIpv4) return null

        val wifi = runCatching {
            appContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
        }.getOrNull()

        @Suppress("DEPRECATION")
        val connection = runCatching { wifi?.connectionInfo }.getOrNull()
        @Suppress("DEPRECATION")
        val rawSsid = runCatching { connection?.ssid }.getOrNull()
            ?.trim('"')
            ?.takeIf { it.isNotBlank() && it != "<unknown ssid>" }
        @Suppress("DEPRECATION")
        val bssid = runCatching { connection?.bssid }.getOrNull()
            ?.takeIf { it.isNotBlank() && it != "02:00:00:00:00:00" }

        @Suppress("DEPRECATION")
        val dhcpServer = runCatching { wifi?.dhcpInfo?.serverAddress }
            .getOrNull()
            ?.takeIf { it != 0 }
            ?.let { intToIp(it) }

        val subnet = info.ipv4?.let { ip ->
            info.prefixLength?.let { prefix -> "${networkAddress(ip, prefix)}/$prefix" }
        }

        return NetworkSnapshot(
            networkKey = rawSsid ?: subnet ?: "unknown",
            ssid = rawSsid,
            bssid = bssid,
            gateway = info.gateway,
            dhcpServer = dhcpServer,
            dnsServers = info.dnsServers,
            subnet = subnet,
        )
    }

    fun saved(networkKey: String): NetworkSnapshot? =
        prefs.getString(key(networkKey), null)?.let(NetworkSnapshot::fromJson)

    fun save(snapshot: NetworkSnapshot) {
        prefs.edit().putString(key(snapshot.networkKey), snapshot.toJson()).apply()
    }

    fun forget(networkKey: String) {
        prefs.edit().remove(key(networkKey)).apply()
    }

    private fun key(networkKey: String) = "baseline_$networkKey"

    companion object {
        /**
         * Compares a fresh reading against the stored baseline. Each difference
         * is a finding; an unchanged network produces an empty list.
         */
        fun diff(baseline: NetworkSnapshot, current: NetworkSnapshot): List<Alert> {
            val now = current.capturedAt
            val out = mutableListOf<Alert>()

            if (!baseline.gateway.isNullOrBlank() && !current.gateway.isNullOrBlank() &&
                baseline.gateway != current.gateway
            ) {
                out += Alert(
                    key = "gateway-changed",
                    timeMillis = now,
                    severity = AlertSeverity.CRITICAL,
                    category = AlertCategory.GATEWAY,
                    title = "ゲートウェイが変わりました",
                    detail = "${baseline.gateway} → ${current.gateway}。" +
                        "同じネットワークで出口が変わるのは、不正なルーターの設置や" +
                        "中間者攻撃の可能性があります。",
                )
            }

            if (!baseline.dhcpServer.isNullOrBlank() && !current.dhcpServer.isNullOrBlank() &&
                baseline.dhcpServer != current.dhcpServer
            ) {
                out += Alert(
                    key = "dhcp-server-changed",
                    timeMillis = now,
                    severity = AlertSeverity.CRITICAL,
                    category = AlertCategory.DHCP,
                    title = "DHCPサーバーが変わりました",
                    detail = "${baseline.dhcpServer} → ${current.dhcpServer}。" +
                        "別の機器がIPアドレスを配っています（不正DHCPサーバーの疑い）。" +
                        "勝手に接続されたルーターがないか確認してください。",
                )
            }

            val baseDns = baseline.dnsServers.toSet()
            val currentDns = current.dnsServers.toSet()
            if (baseDns.isNotEmpty() && currentDns.isNotEmpty() && baseDns != currentDns) {
                out += Alert(
                    key = "dns-changed",
                    timeMillis = now,
                    severity = AlertSeverity.CRITICAL,
                    category = AlertCategory.DNS,
                    title = "DNSサーバーが変わりました",
                    detail = "${baseDns.joinToString(", ")} → ${currentDns.joinToString(", ")}。" +
                        "DNSの書き換えは偽サイトへの誘導に使われます。",
                )
            }

            if (!baseline.subnet.isNullOrBlank() && !current.subnet.isNullOrBlank() &&
                baseline.subnet != current.subnet
            ) {
                out += Alert(
                    key = "subnet-changed",
                    timeMillis = now,
                    severity = AlertSeverity.WARN,
                    category = AlertCategory.DHCP,
                    title = "サブネットが変わりました",
                    detail = "${baseline.subnet} → ${current.subnet}。" +
                        "別のDHCPサーバーから異なる体系のIPを受け取った可能性があります。",
                )
            }

            if (!baseline.bssid.isNullOrBlank() && !current.bssid.isNullOrBlank() &&
                baseline.bssid != current.bssid
            ) {
                out += Alert(
                    key = "bssid-changed",
                    timeMillis = now,
                    severity = AlertSeverity.INFO,
                    category = AlertCategory.GATEWAY,
                    title = "接続先アクセスポイントが変わりました",
                    detail = "${baseline.bssid} → ${current.bssid}。" +
                        "複数APの環境では通常のローミングですが、覚えのないAPなら注意してください。",
                )
            }

            return out
        }

        /**
         * Checks that don't need a baseline — they flag configurations that are
         * unusual in a small office / home network on their own.
         */
        fun staticChecks(current: NetworkSnapshot): List<Alert> {
            val now = current.capturedAt
            val out = mutableListOf<Alert>()

            val dhcp = current.dhcpServer
            val gateway = current.gateway
            if (!dhcp.isNullOrBlank() && !gateway.isNullOrBlank() && dhcp != gateway) {
                out += Alert(
                    key = "dhcp-not-gateway",
                    timeMillis = now,
                    severity = AlertSeverity.WARN,
                    category = AlertCategory.DHCP,
                    title = "DHCPサーバーとゲートウェイが別です",
                    detail = "DHCP=$dhcp / ゲートウェイ=$gateway。" +
                        "専用のDHCPサーバーがある環境では正常ですが、" +
                        "小規模ネットワークでは不正DHCPの可能性があります。",
                )
            }

            val subnet = current.subnet
            val offLan = current.dnsServers.filter { dns ->
                subnet != null && !sameSubnet(dns, subnet) && !isWellKnownResolver(dns)
            }
            if (offLan.isNotEmpty()) {
                out += Alert(
                    key = "dns-unexpected",
                    timeMillis = now,
                    severity = AlertSeverity.WARN,
                    category = AlertCategory.DNS,
                    title = "見慣れないDNSサーバーです",
                    detail = "${offLan.joinToString(", ")} はLAN内でも既知の公開DNSでもありません。" +
                        "意図して設定したものか確認してください。",
                )
            }

            return out
        }

        /** Public resolvers that are perfectly normal to see configured. */
        private val WELL_KNOWN_RESOLVERS = setOf(
            "8.8.8.8", "8.8.4.4",           // Google
            "1.1.1.1", "1.0.0.1",           // Cloudflare
            "9.9.9.9", "149.112.112.112",   // Quad9
            "208.67.222.222", "208.67.220.220", // OpenDNS
            "129.250.35.250", "129.250.35.251", // NTT
        )

        private fun isWellKnownResolver(ip: String) = ip in WELL_KNOWN_RESOLVERS

        private fun sameSubnet(ip: String, subnetCidr: String): Boolean = runCatching {
            val (netPart, prefixPart) = subnetCidr.split("/")
            val prefix = prefixPart.toInt()
            networkAddress(ip, prefix) == netPart
        }.getOrDefault(false)

        /** Masks an IPv4 address down to its network address for [prefix] bits. */
        fun networkAddress(ip: String, prefix: Int): String {
            val octets = ip.split(".").map { it.toIntOrNull() ?: 0 }
            if (octets.size != 4) return ip
            val value = octets.fold(0L) { acc, o -> (acc shl 8) or o.toLong() }
            val mask = if (prefix <= 0) 0L else (0xFFFFFFFFL shl (32 - prefix)) and 0xFFFFFFFFL
            val net = value and mask
            return "${(net shr 24) and 0xFF}.${(net shr 16) and 0xFF}." +
                "${(net shr 8) and 0xFF}.${net and 0xFF}"
        }

        /** Android's DhcpInfo packs IPv4 addresses little-endian. */
        fun intToIp(value: Int): String =
            "${value and 0xFF}.${(value shr 8) and 0xFF}." +
                "${(value shr 16) and 0xFF}.${(value shr 24) and 0xFF}"
    }
}
