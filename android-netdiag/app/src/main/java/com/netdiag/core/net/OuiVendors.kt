package com.netdiag.core.net

/**
 * Tiny embedded OUI (MAC prefix) → vendor table for common consumer and IoT
 * gear. Not exhaustive — just enough to label the typical devices found on a
 * home or small-office LAN. A MAC is only available when the OS exposes the
 * ARP cache (mostly pre-Android 10).
 */
object OuiVendors {

    fun lookup(mac: String?): String? {
        if (mac == null || mac.length < 8) return null
        val prefix = mac.substring(0, 8).uppercase()
        return TABLE[prefix]
    }

    private val TABLE: Map<String, String> = mapOf(
        "F0:9F:C2" to "Ubiquiti",
        "B8:27:EB" to "Raspberry Pi",
        "DC:A6:32" to "Raspberry Pi",
        "E4:5F:01" to "Raspberry Pi",
        "00:1A:11" to "Google",
        "F4:F5:E8" to "Google",
        "1C:F2:9A" to "Google",
        "44:07:0B" to "Google Nest",
        "FC:A1:83" to "Amazon",
        "68:37:E9" to "Amazon",
        "F0:81:73" to "Amazon Echo",
        "AC:63:BE" to "Amazon",
        "18:74:2E" to "Amazon",
        "00:17:88" to "Philips Hue",
        "EC:FA:BC" to "Espressif (IoT)",
        "24:0A:C4" to "Espressif (IoT)",
        "30:AE:A4" to "Espressif (IoT)",
        "A0:20:A6" to "Espressif (IoT)",
        "B4:E6:2D" to "Espressif (IoT)",
        "5C:CF:7F" to "Espressif (IoT)",
        "00:50:56" to "VMware",
        "AC:DE:48" to "Apple",
        "F0:18:98" to "Apple",
        "3C:07:54" to "Apple",
        "A4:83:E7" to "Apple",
        "F4:0F:24" to "Apple",
        "D0:81:7A" to "Apple",
        "C8:69:CD" to "Apple",
        "00:1C:42" to "Parallels",
        "5C:51:88" to "Samsung",
        "8C:77:12" to "Samsung",
        "C0:BD:D1" to "Samsung",
        "00:80:77" to "Brother",
        "30:05:5C" to "Brother",
        "00:00:48" to "Epson",
        "9C:AE:D3" to "Epson",
        "00:1E:8F" to "Canon",
        "2C:9E:FC" to "Canon",
        "30:CD:A7" to "Sony",
        "00:24:BE" to "Sony",
        "AC:84:C6" to "TP-Link",
        "50:C7:BF" to "TP-Link",
        "B0:4E:26" to "TP-Link",
        "10:6F:3F" to "Buffalo",
        "00:24:A5" to "Buffalo",
        "00:1D:73" to "Buffalo",
        "00:0C:E7" to "MediaTek",
        "C4:E9:84" to "TP-Link",
        "00:90:A9" to "Western Digital (NAS)",
        "00:11:32" to "Synology (NAS)",
        "00:08:9B" to "QNAP (NAS)",
        "B0:C5:54" to "D-Link",
        "00:0F:7C" to "ACTi (Camera)",
        "00:40:8C" to "Axis (Camera)",
        "C0:56:E3" to "Hikvision (Camera)",
        "4C:11:BF" to "Hikvision (Camera)",
    )
}
