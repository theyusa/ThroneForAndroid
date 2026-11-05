package io.nekohasekai.sagernet.utils

enum class InterfaceKind { WIFI, ETHERNET, CELLULAR, VPN, OTHER }
enum class HotspotKind { NONE, POSSIBLE, CONFIRMED }

data class InterfaceIp(
    val address: String,
    val prefixLength: Int? = null,
    val broadcast: String? = null,
) {
    val ipv6: Boolean get() = address.contains(':')
    val netmask: String?
        get() {
            val prefix = prefixLength ?: return null
            if (ipv6 || prefix !in 0..32) return null
            val bits = if (prefix == 0) 0L else (0xffffffffL shl (32 - prefix)) and 0xffffffffL
            return (3 downTo 0).joinToString(".") { ((bits shr (it * 8)) and 255).toString() }
        }
}

data class InterfaceRoute(val destination: InterfaceIp, val gateway: String?)

data class ActiveNetworkOverview(
    val interfaceName: String?,
    val kind: InterfaceKind,
    val isVpnActive: Boolean,
    val isPrivateDnsActive: Boolean = false,
    val privateDnsServerName: String? = null,
    val dnsServers: List<String> = emptyList(),
    val gateway: String? = null,
)

data class NetworkInterfaceInfo(
    val name: String,
    val index: Int? = null,
    val up: Boolean? = null,
    val mtu: Int? = null,
    val loopback: Boolean = false,
    val pointToPoint: Boolean? = null,
    val multicast: Boolean? = null,
    val virtual: Boolean? = null,
    val mac: String? = null,
    val kind: InterfaceKind = InterfaceKind.OTHER,
    val hotspot: HotspotKind = HotspotKind.NONE,
    val isDefaultRoute: Boolean = false,
    val isVpn: Boolean = false,
    val addresses: List<InterfaceIp> = emptyList(),
    val routes: List<InterfaceRoute> = emptyList(),
    val dns: List<String> = emptyList(),
    val domains: String? = null,
)

data class NetworkInterfacesSnapshot(
    val interfaces: List<NetworkInterfaceInfo> = emptyList(),
    val activeNetwork: ActiveNetworkOverview? = null,
    val partial: Boolean = false,
)

object NetworkInterfacesPolicy {
    fun classifyHotspot(name: String, tethered: Set<String>?): HotspotKind = when {
        tethered != null -> if (name in tethered) HotspotKind.CONFIRMED else HotspotKind.NONE
        name.matches(Regex("(?i)(ap\\d+|softap.*|swlan.*|wlan\\d+|rndis.*|usb\\d+|bnep.*)")) -> HotspotKind.POSSIBLE
        else -> HotspotKind.NONE
    }

    fun inferKind(name: String): InterfaceKind = when {
        name.matches(Regex("(?i)(tun.*|tap.*|ppp.*|wg\\d+|ipsec.*|vti.*)")) -> InterfaceKind.VPN
        name.matches(Regex("(?i)(v4-)?(rmnet.*|ccmni.*|pdp.*|wwan.*)")) -> InterfaceKind.CELLULAR
        else -> InterfaceKind.OTHER
    }

    fun mergeAddresses(first: List<InterfaceIp>, second: List<InterfaceIp>): List<InterfaceIp> =
        (first + second).groupBy { it.address.substringBefore('%') }.values.map { candidates ->
            val primary = candidates.first()
            primary.copy(
                prefixLength = candidates.firstNotNullOfOrNull { it.prefixLength },
                broadcast = candidates.firstNotNullOfOrNull { it.broadcast },
            )
        }.sortedWith(compareBy<InterfaceIp> { it.ipv6 }.thenBy { it.address })

    fun filterMac(mac: String?): String? =
        mac?.takeUnless { it == "02:00:00:00:00:00" }

    fun sortPriority(info: NetworkInterfaceInfo, activeIface: String?): Int = when {
        info.name == activeIface -> 0
        info.isDefaultRoute -> 1
        info.kind == InterfaceKind.VPN && info.up == true -> 2
        info.hotspot == HotspotKind.CONFIRMED -> 3
        info.kind == InterfaceKind.WIFI && info.up == true -> 4
        info.kind == InterfaceKind.CELLULAR && info.up == true -> 5
        info.kind == InterfaceKind.ETHERNET && info.up == true -> 6
        info.hotspot == HotspotKind.POSSIBLE -> 7
        info.kind == InterfaceKind.VPN -> 8 // configured / standby VPN
        info.up == true && !info.loopback && info.kind != InterfaceKind.OTHER -> 9
        info.up == true && !info.loopback -> 10 // dummy0 etc.
        info.loopback -> 11 // lo
        else -> 12 // down interfaces
    }
}
