package io.nekohasekai.sagernet.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The pure decisions of the LAN Sharing client list: ARP-table parsing, Clash /connections parsing and the
 * merge/dedupe/sort/expire pipeline that turns raw network state into the device rows.
 */
class LanSharingLogicsTest {

    @Test
    fun arpSkipsLoopbackLinkLocalAndUnresolvedRows() {
        val arp = """
            IP address       HW type     Flags       HW address            Mask     Device
            192.168.43.52    0x1         0x2         aa:bb:cc:dd:ee:01     *        wlan1
            192.168.43.53    0x1         0x2         00:00:00:00:00:00     *        wlan1
            127.0.0.4        0x1         0x0         ab:cd:ef:01:02:03     *        lo
            169.254.3.9      0x1         0x2         ab:cd:ef:01:02:04     *        wlan0
            192.168.43.54    0x1         0x2         aa:bb:cc:dd:ee:02     *        ap0
            172.19.0.7       0x1         0x2         ab:cd:ef:01:02:05     *        tun0
        """.trimIndent()
        assertEquals(listOf("192.168.43.52", "192.168.43.54"), LanSharingLogics.parseArpFile(arp))
    }

    @Test
    fun connectionsParsesAndSumsPerSourceIp() {
        val json = """
            {
              "connections": [
                {"metadata": {"sourceIP": "192.168.43.52", "host": "example.com:443"},
                 "upload": 1000, "download": 2000},
                {"metadata": {"sourceIP": "192.168.43.52", "host": "example.net:80"},
                 "upload": 500, "download": 100},
                {"metadata": {"sourceIP": "192.168.43.60", "host": null},
                 "upload": 7, "download": 9}
              ]
            }
        """.trimIndent()
        val devices = LanSharingLogics.parseConnections(json)
        assertEquals(2, devices.size)
        val first = devices.first { it.ip == "192.168.43.52" }
        assertEquals(2, first.activeConnections)
        assertEquals(1500L, first.uploadBytes)
        assertEquals(2100L, first.downloadBytes)
        assertEquals("example.net:80", first.lastHost)
        assertFalse(first.isHotspot)
        // Malformed and empty input never throws and always yields an empty list.
        assertEquals(0, LanSharingLogics.parseConnections("").size)
        assertEquals(0, LanSharingLogics.parseConnections("not json").size)
        assertEquals(0, LanSharingLogics.parseConnections("{\"connections\":[]}").size)
    }

    @Test
    fun mergeDedupesSortsAndExpires() {
        val now = 10_000_000L
        val history = mapOf(
            // Idle beyond the cutoff: expired, must not reappear.
            "192.168.43.70" to LanClientDevice(
                "192.168.43.70", 0, 0L, 0L, null, lastSeenMs = now - 20 * LanSharingLogics.IDLE_MS, isHotspot = false
            ),
            // Historical row inside the cutoff, now quiet: kept and shown as idle.
            "192.168.43.71" to LanClientDevice(
                "192.168.43.71", 0, 0L, 0L, null, lastSeenMs = now - 60_000L, isHotspot = false
            ),
        )
        val clash = listOf(
            // Two entries for the same IP: the poller already aggregated them upstream.
            LanClientDevice("192.168.43.52", 3, 300L, 400L, "a.example.com:443", now, false),
            // isHotspot is decided by the hotspot IP, not by the device.
            LanClientDevice("192.168.43.90", 1, 1L, 2L, "b.example.com:80", now - 1000L, false),
        )
        val arp = listOf("192.168.43.52", "192.168.43.54")

        val merged = LanSharingLogics.mergeDevices(history, arp, clash, hotspotIp = "192.168.43.52", nowMs = now)

        val ips = merged.map { it.ip }
        // No duplicates; history entry with no fresh signal is retained once; expired entry is gone.
        assertTrue(merged.count { it.ip == "192.168.43.52" } == 1)
        assertTrue(merged.count { it.ip == "192.168.43.54" } == 1)
        assertTrue(merged.any { it.ip == "192.168.43.71" })
        assertFalse(ips.contains("192.168.43.70"))
        // Active connections first, then recency within each group.
        assertTrue((merged[0].activeConnections) >= (merged[1].activeConnections))
        assertEquals("192.168.43.52", merged[0].ip)
        // isHotspot comes from the hotspot ip alone.
        assertTrue(merged.first { it.ip == "192.168.43.52" }.isHotspot)
        assertFalse(merged.first { it.ip == "192.168.43.71" }.isHotspot)
    }

    @Test
    fun switchOnEnablesClashApiWhenOff() {
        // Negative sign-encoded port means Clash API is off: enabling LAN sharing flips it positive and requests reload.
        val fromNegative = LanSharingLogics.ensureClashApiEnabledPort(-9090)
        assertEquals(9090, fromNegative.newRawPort)
        assertTrue(fromNegative.changed)

        val fromZero = LanSharingLogics.ensureClashApiEnabledPort(0)
        assertEquals(9090, fromZero.newRawPort)
        assertTrue(fromZero.changed)

        // Already positive: kept untouched, no extra reload.
        val alreadyOn = LanSharingLogics.ensureClashApiEnabledPort(9091)
        assertEquals(9091, alreadyOn.newRawPort)
        assertFalse(alreadyOn.changed)
    }

    @Test
    fun firstEnableWithoutConfigRefusesInvalidOrBlankPort() {
        assertEquals(null, LanSharingLogics.validateDialogPort(""))
        assertEquals(null, LanSharingLogics.validateDialogPort("   "))
        assertEquals(null, LanSharingLogics.validateDialogPort("0"))
        assertEquals(null, LanSharingLogics.validateDialogPort("70000"))
        assertEquals(null, LanSharingLogics.validateDialogPort("abc"))
        assertEquals(1080, LanSharingLogics.validateDialogPort(" 1080 "))
    }

    @Test
    fun staleSnapshotRendersWithoutCrashForEveryStatus() {
        for (status in HotspotGatewayStatus.entries) {
            val snap = HotspotGatewaySnapshot(status = status, gateway = "", port = 0, updatedAt = 0L)
            val key = LanSharingLogics.statusSummaryKind(enabled = true, snapshot = snap)
            assertTrue(key.isNotEmpty())
        }
        val disabledKey = LanSharingLogics.statusSummaryKind(
            enabled = false,
            snapshot = HotspotGatewaySnapshot(HotspotGatewayStatus.PROBING, "", 0, 0L),
        )
        assertEquals("DISABLED", disabledKey)
    }

    @Test
    fun buildSocks5ShareLinkEncodesCredentialsUotAndParsesBack() {
        // Anonymous link without UoT or remark
        val plain = LanSharingLogics.buildSocks5ShareLink(
            host = "192.168.43.1",
            port = 2080,
            username = "",
            password = "",
            uot = false,
            remark = "",
        )
        assertEquals("socks5://192.168.43.1:2080", plain)

        // With credentials (special characters in password preserved verbatim, username trimmed) + UoT + remark
        val withAuth = LanSharingLogics.buildSocks5ShareLink(
            host = "192.168.43.1",
            port = 2080,
            username = " yusa ",
            password = " p@ss:1#2 ",
            uot = true,
            remark = "Hotspot Gateway",
        )
        val parsed = io.nekohasekai.sagernet.outbound.types.Socks()
        assertTrue(parsed.parseFromLink(withAuth))
        assertEquals("192.168.43.1", parsed.server)
        assertEquals(2080, parsed.serverPort)
        assertEquals("yusa", parsed.username)
        assertEquals(" p@ss:1#2 ", parsed.password)
        assertTrue(parsed.uot)
        assertEquals("Hotspot Gateway", parsed.name)

        // Blank host or invalid port returns empty string
        assertEquals("", LanSharingLogics.buildSocks5ShareLink("", 2080, "u", "p", false, ""))
        assertEquals("", LanSharingLogics.buildSocks5ShareLink("192.168.43.1", 0, "u", "p", false, ""))
    }
}
