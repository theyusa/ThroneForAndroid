package io.nekohasekai.sagernet.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure JVM tests for [LanSharingNetwork]: Clash API URL construction from sign-encoded port,
 * hotspot interface / IP classification, and graceful failure when the Clash endpoint errors.
 */
class LanSharingNetworkLogicTest {

    @Test
    fun clashUrlUsesAbsPortAndDefaultsWhenZero() {
        assertEquals(
            "http://127.0.0.1:9090/connections",
            LanSharingNetwork.buildClashConnectionsUrl(-9090),
        )
        assertEquals(
            "http://127.0.0.1:9091/connections",
            LanSharingNetwork.buildClashConnectionsUrl(9091),
        )
        assertEquals(
            "http://127.0.0.1:9090/connections",
            LanSharingNetwork.buildClashConnectionsUrl(0),
        )
    }

    @Test
    fun excludesCellularAndVirtualInterfacesAndMatchesHotspotOnes() {
        assertTrue(LanSharingNetwork.isCellularOrVirtualInterface("rmnet0"))
        assertTrue(LanSharingNetwork.isCellularOrVirtualInterface("r_rmnet_data0"))
        assertTrue(LanSharingNetwork.isCellularOrVirtualInterface("dummy0"))
        assertTrue(LanSharingNetwork.isCellularOrVirtualInterface("tun0"))
        assertFalse(LanSharingNetwork.isCellularOrVirtualInterface("wlan1"))
        assertFalse(LanSharingNetwork.isCellularOrVirtualInterface("ap0"))

        assertTrue(LanSharingNetwork.isHotspotInterfaceName("ap0"))
        assertTrue(LanSharingNetwork.isHotspotInterfaceName("wlan1"))
        assertTrue(LanSharingNetwork.isHotspotInterfaceName("swlan0"))
        assertTrue(LanSharingNetwork.isHotspotInterfaceName("softap0"))
        assertFalse(LanSharingNetwork.isHotspotInterfaceName("wlan0"))
        assertFalse(LanSharingNetwork.isHotspotInterfaceName("rmnet_data1"))

        assertTrue(LanSharingNetwork.isHotspotIpPattern("192.168.43.1"))
        assertTrue(LanSharingNetwork.isHotspotIpPattern("172.20.10.1"))
        assertFalse(LanSharingNetwork.isHotspotIpPattern("10.0.0.1"))

        assertTrue(LanSharingNetwork.isValidHotspotIp("192.168.43.1"))
        assertFalse(LanSharingNetwork.isValidHotspotIp("127.0.0.1"))
        assertFalse(LanSharingNetwork.isValidHotspotIp("169.254.1.1"))
        assertFalse(LanSharingNetwork.isValidHotspotIp("172.19.0.1"))
    }

    @Test
    fun pollerUsesAbsPortAndSecretHeaderAndReturnsNullOnError() {
        var capturedUrl: String? = null
        var capturedAuth: String? = null
        val body = LanSharingNetwork.pollConnectionsWith(
            rawPort = -9191,
            secret = "my-secret",
            transport = { url, authHeader ->
                capturedUrl = url
                capturedAuth = authHeader
                "{\"connections\":[]}"
            },
        )
        assertEquals("http://127.0.0.1:9191/connections", capturedUrl)
        assertEquals("Bearer my-secret", capturedAuth)
        assertEquals("{\"connections\":[]}", body)

        // Blank secret -> no Authorization header; transport exception -> null (never throws).
        var blankAuth: String? = "not-null"
        val failed = LanSharingNetwork.pollConnectionsWith(
            rawPort = 9090,
            secret = "   ",
            transport = { _, authHeader ->
                blankAuth = authHeader
                throw RuntimeException("HTTP 401")
            },
        )
        assertNull(blankAuth)
        assertNull(failed)
    }
}
