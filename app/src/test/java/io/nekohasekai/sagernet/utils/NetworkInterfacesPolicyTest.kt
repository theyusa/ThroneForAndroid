package io.nekohasekai.sagernet.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NetworkInterfacesPolicyTest {

    @Test
    fun inferKindClassifiesKnownPatterns() {
        assertEquals(InterfaceKind.CELLULAR, NetworkInterfacesPolicy.inferKind("rmnet_data0"))
        assertEquals(InterfaceKind.CELLULAR, NetworkInterfacesPolicy.inferKind("v4-rmnet_data0"))
        assertEquals(InterfaceKind.CELLULAR, NetworkInterfacesPolicy.inferKind("ccmni0"))
        assertEquals(InterfaceKind.CELLULAR, NetworkInterfacesPolicy.inferKind("pdp0"))
        assertEquals(InterfaceKind.CELLULAR, NetworkInterfacesPolicy.inferKind("wwan0"))

        assertEquals(InterfaceKind.VPN, NetworkInterfacesPolicy.inferKind("tun0"))
        assertEquals(InterfaceKind.VPN, NetworkInterfacesPolicy.inferKind("tap0"))
        assertEquals(InterfaceKind.VPN, NetworkInterfacesPolicy.inferKind("ppp0"))
        assertEquals(InterfaceKind.VPN, NetworkInterfacesPolicy.inferKind("wg0"))
        assertEquals(InterfaceKind.VPN, NetworkInterfacesPolicy.inferKind("ipsec0"))
        assertEquals(InterfaceKind.VPN, NetworkInterfacesPolicy.inferKind("vti0"))

        assertEquals(InterfaceKind.OTHER, NetworkInterfacesPolicy.inferKind("ap0"))
        assertEquals(InterfaceKind.OTHER, NetworkInterfacesPolicy.inferKind("wlan0"))
        assertEquals(InterfaceKind.OTHER, NetworkInterfacesPolicy.inferKind("eth0"))
    }

    @Test
    fun classifyHotspotWithTetheredAndHeuristics() {
        // When tethered set is provided (from OS reflection)
        val tetheredSet = setOf("ap0", "rndis0")
        assertEquals(HotspotKind.CONFIRMED, NetworkInterfacesPolicy.classifyHotspot("ap0", tetheredSet))
        assertEquals(HotspotKind.CONFIRMED, NetworkInterfacesPolicy.classifyHotspot("rndis0", tetheredSet))
        assertEquals(HotspotKind.NONE, NetworkInterfacesPolicy.classifyHotspot("wlan0", tetheredSet))

        // When tethered set is empty
        assertEquals(HotspotKind.NONE, NetworkInterfacesPolicy.classifyHotspot("ap0", emptySet()))

        // When tethered set is null (heuristics fallback)
        assertEquals(HotspotKind.POSSIBLE, NetworkInterfacesPolicy.classifyHotspot("ap0", null))
        assertEquals(HotspotKind.POSSIBLE, NetworkInterfacesPolicy.classifyHotspot("softap0", null))
        assertEquals(HotspotKind.POSSIBLE, NetworkInterfacesPolicy.classifyHotspot("swlan0", null))
        assertEquals(HotspotKind.POSSIBLE, NetworkInterfacesPolicy.classifyHotspot("wlan0", null))
        assertEquals(HotspotKind.POSSIBLE, NetworkInterfacesPolicy.classifyHotspot("rndis0", null))
        assertEquals(HotspotKind.POSSIBLE, NetworkInterfacesPolicy.classifyHotspot("usb0", null))
        assertEquals(HotspotKind.POSSIBLE, NetworkInterfacesPolicy.classifyHotspot("bnep0", null))
        assertEquals(HotspotKind.NONE, NetworkInterfacesPolicy.classifyHotspot("rmnet0", null))
        assertEquals(HotspotKind.NONE, NetworkInterfacesPolicy.classifyHotspot("eth0", null))
    }

    @Test
    fun addressSourcesMergePartialMetadataAndDeduplicateScopes() {
        val ipv4 = InterfaceIp("192.168.43.1", 24, "192.168.43.255")
        val result = NetworkInterfacesPolicy.mergeAddresses(
            listOf(InterfaceIp("192.168.43.1"), InterfaceIp("fe80::1%ap0")),
            listOf(ipv4, InterfaceIp("fe80::1", 64)),
        )
        assertEquals(2, result.size)
        assertEquals(24, result[0].prefixLength)
        assertEquals("192.168.43.255", result[0].broadcast)
        assertEquals("fe80::1%ap0", result[1].address)
        assertEquals(64, result[1].prefixLength)
    }

    @Test
    fun netmasksCoverPrefixBoundaryCases() {
        val ipv4 = InterfaceIp("192.168.43.1", 24)
        assertEquals("255.255.255.0", ipv4.netmask)
        assertEquals("0.0.0.0", ipv4.copy(prefixLength = 0).netmask)
        assertEquals("255.255.255.255", ipv4.copy(prefixLength = 32).netmask)
        assertEquals("255.255.255.254", ipv4.copy(prefixLength = 31).netmask)

        val ipv6 = InterfaceIp("fd00::1", 64)
        assertNull(ipv6.netmask)
        assertNull(ipv4.copy(prefixLength = null).netmask)
    }

    @Test
    fun filterMacMasksDummyHardwareAddress() {
        assertNull(NetworkInterfacesPolicy.filterMac("02:00:00:00:00:00"))
        assertNull(NetworkInterfacesPolicy.filterMac(null))
        assertEquals("00:1a:2b:3c:4d:5e", NetworkInterfacesPolicy.filterMac("00:1a:2b:3c:4d:5e"))
    }
}
