package com.netdiag.core.net

/** Best-effort device category, for an at-a-glance map of the LAN. */
enum class DeviceKind { SELF, ROUTER, PRINTER, NAS, PC, PHONE, TV, CAMERA, IOT, UNKNOWN }

data class DeviceType(val kind: DeviceKind, val label: String)

/**
 * Guesses what a discovered host is from the cheap signals we already have:
 * open ports, hostname keywords and the MAC vendor. Nothing here touches the
 * network again, so it is safe to call on every host as results stream in.
 */
object DeviceClassifier {

    fun classify(host: DiscoveredHost): DeviceType {
        if (host.isSelf) return DeviceType(DeviceKind.SELF, "この端末")
        if (host.isGateway) return DeviceType(DeviceKind.ROUTER, "ルーター/ゲートウェイ")

        val ports = host.openPorts.toSet()
        val name = host.hostname?.lowercase() ?: ""
        val vendor = host.vendor?.lowercase() ?: ""

        fun anyPort(vararg p: Int) = p.any { it in ports }
        fun nameHas(vararg k: String) = k.any { name.contains(it) }
        fun vendorHas(vararg k: String) = k.any { vendor.contains(it) }

        return when {
            anyPort(9100, 515, 631) || nameHas("printer", "epson", "canon", "brother", "ricoh")
                || vendorHas("brother", "epson", "canon", "ricoh") ->
                DeviceType(DeviceKind.PRINTER, "プリンター")

            anyPort(554) || nameHas("cam", "ipcam", "hikvision", "dahua", "axis")
                || vendorHas("camera", "hikvision", "axis", "acti") ->
                DeviceType(DeviceKind.CAMERA, "ネットワークカメラ")

            nameHas("nas", "synology", "qnap", "diskstation", "readynas")
                || vendorHas("nas", "synology", "qnap", "western digital") ->
                DeviceType(DeviceKind.NAS, "NAS/ストレージ")

            anyPort(8009) || nameHas("chromecast", "androidtv", "bravia", "aquos", "regza", "appletv", "airplay")
                || vendorHas("sony", "google nest") && anyPort(8008, 8009) ->
                DeviceType(DeviceKind.TV, "TV/メディア機器")

            anyPort(445, 139, 3389) || nameHas("desktop", "-pc", "win", "macbook", "imac", "ubuntu") ->
                DeviceType(DeviceKind.PC, "PC")

            anyPort(62078) || nameHas("iphone", "android", "pixel", "galaxy", "phone") ->
                DeviceType(DeviceKind.PHONE, "スマホ/タブレット")

            vendorHas("espressif", "tuya", "shelly", "philips hue", "amazon", "google") ->
                DeviceType(DeviceKind.IOT, "IoT家電")

            else -> DeviceType(DeviceKind.UNKNOWN, "不明な機器")
        }
    }
}
