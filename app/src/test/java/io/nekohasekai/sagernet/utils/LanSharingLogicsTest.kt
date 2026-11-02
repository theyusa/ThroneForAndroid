package io.nekohasekai.sagernet.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
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

        val merged = LanSharingLogics.mergeDevices(
            history = history,
            arpIps = arp,
            clash = clash,
            hotspotIp = "192.168.43.52",
            hotspotPrefix = null,
            localIps = emptySet(),
            nowMs = now,
        )

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
        // Item 3: sharing the hotspot's /24 prefix makes a device a hotspot client, not only the exact interface IP.
        assertTrue(merged.first { it.ip == "192.168.43.90" }.isHotspot)
        assertTrue(merged.first { it.ip == "192.168.43.71" }.isHotspot)
    }

    @Test
    fun connectionsFilterLocalSources() {
        val json = """
            {
              "connections": [
                {"metadata": {"sourceIP": "192.168.43.52", "host": "example.com:443"},
                 "upload": 10, "download": 20},
                {"metadata": {"sourceIP": "127.0.0.5"}, "upload": 1, "download": 1},
                {"metadata": {"sourceIP": "::1"}, "upload": 1, "download": 1},
                {"metadata": {"sourceIP": "172.19.0.9"}, "upload": 1, "download": 1},
                {"metadata": {"sourceIP": ""}, "upload": 1, "download": 1}
              ]
            }
        """.trimIndent()
        val devices = LanSharingLogics.parseConnections(json)
        assertEquals(1, devices.size)
        assertEquals("192.168.43.52", devices[0].ip)
    }

    @Test
    fun sameSubnet24Matches() {
        assertTrue(LanSharingLogics.isSameSubnet24("192.168.43.1", "192.168.43.222"))
        assertFalse(LanSharingLogics.isSameSubnet24("192.168.43.1", "192.168.9.222"))
        assertFalse(LanSharingLogics.isSameSubnet24("::1", "192.168.43.1"))
        assertFalse(LanSharingLogics.isSameSubnet24("not-an-ip", "192.168.43.1"))
        assertFalse(LanSharingLogics.isSameSubnet24("0.0.0.0", "192.168.43.1"))
    }

    @Test
    fun sameSubnetHonorsPrefixAndFallsBackTo24() {
        // Same /30 network (10.125.69.72..75) but the /24s agree; with /30 they do not.
        assertTrue(LanSharingLogics.isSameSubnet("10.125.69.74", "10.125.69.75", 30))
        assertFalse(LanSharingLogics.isSameSubnet("10.125.69.74", "10.125.69.77", 30))
        assertTrue(LanSharingLogics.isSameSubnet("10.125.69.74", "10.125.69.77", 24))
        // A null prefix falls back to the /24 default.
        assertTrue(LanSharingLogics.isSameSubnet("192.168.43.1", "192.168.43.222", null))
        assertFalse(LanSharingLogics.isSameSubnet("192.168.43.1", "192.168.9.222", null))
        // Out-of-range prefixes are clamped; /32 means exact address equality.
        assertTrue(LanSharingLogics.isSameSubnet("10.0.0.1", "10.0.0.1", 32))
        assertFalse(LanSharingLogics.isSameSubnet("10.0.0.1", "10.0.0.2", 32))
        // Garbage and the all-zero address never share a subnet.
        assertFalse(LanSharingLogics.isSameSubnet("::1", "192.168.43.1", null))
        assertFalse(LanSharingLogics.isSameSubnet("not-an-ip", "192.168.43.1", null))
        assertFalse(LanSharingLogics.isSameSubnet("0.0.0.0", "192.168.43.1", null))
    }

    @Test
    fun mergeDevicesUsesReportedHotspotPrefixNotTheDefault() {
        val now = 10_000_000L
        val clash = listOf(
            // Same /30 as the hotspot IP 10.125.69.74 (network .72..75).
            LanClientDevice("10.125.69.75", 1, 1L, 1L, null, now, false),
            // Same /24 but a different /30: only the /24 default would mark it a hotspot client.
            LanClientDevice("10.125.69.77", 1, 2L, 2L, null, now, false),
        )
        val merged = LanSharingLogics.mergeDevices(
            history = emptyMap(),
            arpIps = emptyList(),
            clash = clash,
            hotspotIp = "10.125.69.74",
            hotspotPrefix = 30,
            localIps = emptySet(),
            nowMs = now,
        )
        assertTrue(merged.first { it.ip == "10.125.69.75" }.isHotspot)
        assertFalse(merged.first { it.ip == "10.125.69.77" }.isHotspot)
    }

    @Test
    fun localSourcesNeverBecomeDevices() {
        val now = 10_000_000L
        val arp = listOf("192.168.43.52", "172.19.0.9")
        val clash = listOf(
            LanClientDevice("127.0.0.9", 1, 1L, 1L, "x.example.com:80", now, false),
            LanClientDevice("192.168.43.90", 1, 1L, 2L, null, now, false),
        )
        val history = mapOf(
            // The phone's own hotspot address (detectedHotspotIp) fed back through history.
            "192.168.43.1" to LanClientDevice(
                "192.168.43.1", 0, 0L, 0L, null, lastSeenMs = now, isHotspot = false
            ),
        )
        val merged = LanSharingLogics.mergeDevices(
            history = history,
            arpIps = arp,
            clash = clash,
            hotspotIp = "192.168.43.1",
            hotspotPrefix = null,
            localIps = setOf("192.168.43.1", "192.168.43.7"),
            nowMs = now,
        )
        val ips = merged.map { it.ip }
        assertFalse(ips.contains("172.19.0.9"))
        assertFalse(ips.contains("127.0.0.9"))
        assertFalse(ips.contains("192.168.43.1"))
        assertTrue(ips.contains("192.168.43.52"))
        assertTrue(ips.contains("192.168.43.90"))
        // Any device on the hotspot subnet counts as a hotspot client, not just the interface IP itself.
        assertTrue(merged.first { it.ip == "192.168.43.52" }.isHotspot)
        assertTrue(merged.first { it.ip == "192.168.43.90" }.isHotspot)
    }

    @Test
    fun clashApiSecretGeneratedOnlyWhenBlank() {
        // Blank: a secret must be generated; an existing one is never overwritten.
        assertTrue(LanSharingLogics.clashApiSecretShouldBeGenerated(""))
        assertTrue(LanSharingLogics.clashApiSecretShouldBeGenerated("   "))
        assertFalse(LanSharingLogics.clashApiSecretShouldBeGenerated("FixedSecret"))

        val first = LanSharingLogics.generateClashApiSecret()
        val second = LanSharingLogics.generateClashApiSecret()
        assertEquals(64, first.length)
        assertTrue(first.matches(Regex("[0-9a-f]{64}")))
        // Two generations are independent fresh randomness.
        assertNotEquals(first, second)
    }

    @Test
    fun maskSocks5LinkHidesPasswordOnly() {
        // user:pass@ is masked; the host, port and remark stay readable.
        assertEquals(
            "socks5://yusa:\u2022\u2022\u2022\u2022@192.168.43.1:2080#LAN",
            LanSharingLogics.maskSocks5Link("socks5://yusa:secretPw@192.168.43.1:2080#LAN"),
        )
        // A password containing @ is masked to its first user:pass pair boundary (last @ wins: the authority
        // boundary is the last one before the host).
        assertEquals(
            "socks5://yusa:\u2022\u2022\u2022\u2022@192.168.43.1:2080",
            LanSharingLogics.maskSocks5Link("socks5://yusa:pw@with@it@192.168.43.1:2080"),
        )
        // A link without credentials is unchanged.
        assertEquals(
            "socks5://192.168.43.1:2080",
            LanSharingLogics.maskSocks5Link("socks5://192.168.43.1:2080"),
        )
        // Garbage in, unchanged out: never throws.
        assertEquals("", LanSharingLogics.maskSocks5Link(""))
        assertEquals("socks5://host:2080#x", LanSharingLogics.maskSocks5Link("socks5://host:2080#x"))
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
