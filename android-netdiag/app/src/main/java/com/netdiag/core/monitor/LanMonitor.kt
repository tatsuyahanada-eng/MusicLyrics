package com.netdiag.core.monitor

import android.content.Context
import com.netdiag.core.DiagnosticsLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** Live state of the monitor, for the UI. */
data class MonitorStatus(
    val running: Boolean = false,
    val networkKey: String? = null,
    val baseline: NetworkSnapshot? = null,
    val current: NetworkSnapshot? = null,
    val packetsSeen: Long = 0,
    val knownDevices: Int = 0,
    val lastCheckMillis: Long = 0,
    val recentPackets: List<ObservedPacket> = emptyList(),
)

/**
 * The LAN watchdog. Two detectors run side by side:
 *
 *  1. **Configuration drift** — re-reads the gateway / DHCP server / DNS every
 *     [CHECK_INTERVAL_MS] and compares against the baseline recorded the first
 *     time this network was seen. A rogue DHCP server or a man-in-the-middle
 *     shows up here as a changed gateway or resolver.
 *  2. **Multicast behaviour** — watches mDNS/SSDP (traffic addressed to every
 *     host, so no interception of anyone's private traffic) for malformed
 *     packets, devices that appear out of nowhere, and broadcast storms.
 *
 * Everything works without root and without a VPN, so the device's normal
 * connectivity — including a corporate VPN — is untouched.
 */
object LanMonitor {

    private const val CHECK_INTERVAL_MS = 15_000L
    private const val WINDOW_MS = 10_000L
    private const val FLOOD_PER_SOURCE = 120      // packets / 10s from one host
    private const val FLOOD_TOTAL = 600           // packets / 10s across the LAN
    private const val RECENT_PACKETS = 100

    private val _status = MutableStateFlow(MonitorStatus())
    val status: StateFlow<MonitorStatus> = _status.asStateFlow()

    private var scope: CoroutineScope? = null
    private var job: Job? = null

    /** Timestamps of recently observed packets, per source IP. */
    private val recentBySource = HashMap<String, ArrayDeque<Long>>()
    private var knownDevices = mutableSetOf<String>()
    private var devicePrefsKey: String? = null

    fun isRunning(): Boolean = _status.value.running

    fun start(context: Context) {
        if (job?.isActive == true) return
        val app = context.applicationContext
        AlertStore.init(app)
        DiagnosticsLog.init(app)

        val newScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        scope = newScope
        _status.update { it.copy(running = true) }

        job = newScope.launch {
            launch { configurationLoop(app) }
            launch { multicastLoop(app) }
        }
        DiagnosticsLog.add("MONITOR 監視を開始しました")
    }

    fun stop() {
        job?.cancel()
        job = null
        scope?.cancel()
        scope = null
        recentBySource.clear()
        _status.update { it.copy(running = false) }
        DiagnosticsLog.add("MONITOR 監視を停止しました")
    }

    /** Accepts the current configuration as the new "normal" for this network. */
    fun rebaseline(context: Context) {
        val app = context.applicationContext
        val baseline = NetworkBaseline(app)
        val current = baseline.capture() ?: return
        baseline.save(current)
        _status.update { it.copy(baseline = current, current = current) }
        DiagnosticsLog.add("MONITOR 基準値を再取得: ${current.networkKey}")
    }

    // ---- Detector 1: configuration drift ------------------------------------

    private suspend fun configurationLoop(context: Context) {
        val store = NetworkBaseline(context)
        while (currentScopeActive()) {
            val current = store.capture()
            if (current != null) {
                val saved = store.saved(current.networkKey)
                if (saved == null) {
                    store.save(current)
                    loadKnownDevices(context, current.networkKey)
                    raise(
                        Alert(
                            key = "baseline-${current.networkKey}",
                            timeMillis = current.capturedAt,
                            severity = AlertSeverity.INFO,
                            category = AlertCategory.GATEWAY,
                            title = "このネットワークの基準値を記録しました",
                            detail = "GW=${current.gateway ?: "-"} / " +
                                "DHCP=${current.dhcpServer ?: "-"} / " +
                                "DNS=${current.dnsServers.joinToString(",").ifBlank { "-" }}。" +
                                "以降はここからの変化を監視します。",
                        )
                    )
                } else {
                    if (devicePrefsKey != current.networkKey) {
                        loadKnownDevices(context, current.networkKey)
                    }
                    NetworkBaseline.diff(saved, current).forEach(::raise)
                }
                NetworkBaseline.staticChecks(current).forEach(::raise)
                _status.update {
                    it.copy(
                        networkKey = current.networkKey,
                        baseline = store.saved(current.networkKey),
                        current = current,
                        lastCheckMillis = current.capturedAt,
                    )
                }
            }
            delay(CHECK_INTERVAL_MS)
        }
    }

    // ---- Detector 2: multicast behaviour ------------------------------------

    private suspend fun multicastLoop(context: Context) {
        MulticastSniffer(context).observe().collect { packet ->
            val now = packet.timeMillis
            _status.update {
                it.copy(
                    packetsSeen = it.packetsSeen + 1,
                    recentPackets = (it.recentPackets + packet).takeLast(RECENT_PACKETS),
                )
            }

            if (packet.malformed) {
                raise(
                    Alert(
                        key = "malformed-${packet.protocol}-${packet.sourceIp}",
                        timeMillis = now,
                        severity = AlertSeverity.WARN,
                        category = AlertCategory.PROTOCOL,
                        title = "不正な形式の${packet.protocol}パケット",
                        detail = "送信元 ${packet.sourceIp} から規格に合わないパケット " +
                            "(${packet.sizeBytes}B): ${packet.summary}。" +
                            "機器の不具合か、細工されたパケットの可能性があります。",
                    )
                )
            }

            noteNewDevice(context, packet)
            checkFlood(now, packet)
        }
    }

    private fun noteNewDevice(context: Context, packet: ObservedPacket) {
        val ip = packet.sourceIp
        if (ip == "?" || ip in knownDevices) return
        knownDevices.add(ip)
        saveKnownDevices(context)
        _status.update { it.copy(knownDevices = knownDevices.size) }
        raise(
            Alert(
                key = "new-device-$ip",
                timeMillis = packet.timeMillis,
                severity = AlertSeverity.INFO,
                category = AlertCategory.DEVICE,
                title = "新しい機器がLANに現れました",
                detail = "$ip が${packet.protocol}で自分を告知しています: ${packet.summary}。" +
                    "覚えのない機器なら接続元を確認してください。",
            )
        )
    }

    private fun checkFlood(now: Long, packet: ObservedPacket) {
        val window = recentBySource.getOrPut(packet.sourceIp) { ArrayDeque() }
        window.addLast(now)
        while (window.isNotEmpty() && now - window.first() > WINDOW_MS) window.removeFirst()

        // Drop stale sources so the map doesn't grow without bound.
        recentBySource.entries.removeAll { (_, times) ->
            times.isEmpty() || now - times.last() > WINDOW_MS * 3
        }

        if (window.size >= FLOOD_PER_SOURCE) {
            raise(
                Alert(
                    key = "flood-${packet.sourceIp}",
                    timeMillis = now,
                    severity = AlertSeverity.WARN,
                    category = AlertCategory.TRAFFIC,
                    title = "特定の機器が大量に送信しています",
                    detail = "${packet.sourceIp} が10秒で${window.size}件の" +
                        "${packet.protocol}パケットを送出。機器の暴走や設定ミス、" +
                        "ネットワークループの可能性があります。",
                )
            )
        }

        val total = recentBySource.values.sumOf { it.size }
        if (total >= FLOOD_TOTAL) {
            raise(
                Alert(
                    key = "broadcast-storm",
                    timeMillis = now,
                    severity = AlertSeverity.CRITICAL,
                    category = AlertCategory.TRAFFIC,
                    title = "ブロードキャストストームの疑い",
                    detail = "LAN全体で10秒に${total}件のマルチキャストパケット。" +
                        "配線のループ（スイッチ同士の二重接続）が典型的な原因です。" +
                        "通信が極端に遅くなっている場合は要確認。",
                )
            )
        }
    }

    // ---- Shared helpers -----------------------------------------------------

    private fun raise(alert: Alert) {
        AlertStore.raise(alert)?.let {
            DiagnosticsLog.add("ALERT [${it.severity}] ${it.title} — ${it.detail}")
        }
    }

    private fun loadKnownDevices(context: Context, networkKey: String) {
        devicePrefsKey = networkKey
        val prefs = context.getSharedPreferences("netdiag_devices", Context.MODE_PRIVATE)
        knownDevices = prefs.getStringSet(networkKey, emptySet())!!.toMutableSet()
        _status.update { it.copy(knownDevices = knownDevices.size) }
    }

    private fun saveKnownDevices(context: Context) {
        val key = devicePrefsKey ?: return
        context.getSharedPreferences("netdiag_devices", Context.MODE_PRIVATE)
            .edit()
            .putStringSet(key, knownDevices)
            .apply()
    }

    private fun currentScopeActive(): Boolean = scope?.isActive == true
}
