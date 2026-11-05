package io.nekohasekai.sagernet.utils

import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.NetworkCapabilities
import android.os.Build
import io.nekohasekai.sagernet.Key
import io.nekohasekai.sagernet.database.DataStore
import java.net.NetworkInterface
import java.util.Locale

class NetworkInterfacesRepository(private val connectivity: ConnectivityManager) {

    @Suppress("DEPRECATION")
    fun collect(tetheredInput: Set<String>? = null): NetworkInterfacesSnapshot {
        var partial = false
        fun <T> read(block: () -> T): T? = try {
            block()
        } catch (_: Exception) {
            partial = true
            null
        }

        val tethered = tetheredInput ?: runCatching {
            LanSharingNetwork.getTetheredIfaces(connectivity).toSet()
        }.getOrNull()

        // 1. Detect Active Network & System DNS (DoT / Private DNS)
        val activeNet = read { connectivity.activeNetwork }
        val activeCaps = activeNet?.let { read { connectivity.getNetworkCapabilities(it) } }
        val activeLink = activeNet?.let { read { connectivity.getLinkProperties(it) } }

        val isPrivateDnsActive = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            read { activeLink?.isPrivateDnsActive } ?: false
        } else false

        val privateDnsServerName = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            read { activeLink?.privateDnsServerName }
        } else null

        val activeDnsServers = read { activeLink?.dnsServers?.mapNotNull { it.hostAddress } }.orEmpty()
        val activeGateway = read {
            activeLink?.routes?.firstOrNull { it.isDefaultRoute && it.gateway != null }?.gateway?.hostAddress
        }

        val isVpnConnected = activeCaps?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true ||
                DataStore.vpnService != null

        var activeIfaceName = read { activeLink?.interfaceName }
        if (activeIfaceName == null && isVpnConnected) {
            activeIfaceName = "tun0"
        }

        val activeKind = when {
            isVpnConnected -> InterfaceKind.VPN
            activeCaps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true -> InterfaceKind.WIFI
            activeCaps?.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) == true -> InterfaceKind.ETHERNET
            activeCaps?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) == true -> InterfaceKind.CELLULAR
            else -> InterfaceKind.OTHER
        }

        val activeOverview = if (activeNet != null || isVpnConnected) {
            ActiveNetworkOverview(
                interfaceName = activeIfaceName,
                kind = activeKind,
                isVpnActive = isVpnConnected,
                isPrivateDnsActive = isPrivateDnsActive,
                privateDnsServerName = privateDnsServerName,
                dnsServers = activeDnsServers,
                gateway = activeGateway,
            )
        } else null

        // 2. Discover interfaces via Java NetworkInterface API
        val records = linkedMapOf<String, NetworkInterfaceInfo>()
        read { NetworkInterface.getNetworkInterfaces()?.toList().orEmpty() }.orEmpty().forEach { iface ->
            val name = iface.name ?: return@forEach
            val addresses = read {
                iface.interfaceAddresses.mapNotNull { value ->
                    val address = value.address?.hostAddress ?: return@mapNotNull null
                    InterfaceIp(address, value.networkPrefixLength.toInt().takeIf { it >= 0 }, value.broadcast?.hostAddress)
                }
            }.orEmpty()
            val fallback = read {
                iface.inetAddresses.toList().mapNotNull { it.hostAddress?.let(::InterfaceIp) }
            }.orEmpty()
            val kind = NetworkInterfacesPolicy.inferKind(name)
            records[name] = NetworkInterfaceInfo(
                name = name,
                index = read { iface.index }?.takeIf { it >= 0 },
                up = read { iface.isUp },
                mtu = read { iface.mtu }?.takeIf { it > 0 },
                loopback = read { iface.isLoopback } ?: (name == "lo"),
                pointToPoint = read { iface.isPointToPoint },
                multicast = read { iface.supportsMulticast() },
                virtual = read { iface.isVirtual },
                mac = read { iface.hardwareAddress }?.takeIf { it.isNotEmpty() }?.joinToString(":") {
                    "%02x".format(Locale.ROOT, it.toInt() and 255)
                }?.let(NetworkInterfacesPolicy::filterMac),
                kind = kind,
                isVpn = (kind == InterfaceKind.VPN),
                addresses = NetworkInterfacesPolicy.mergeAddresses(addresses, fallback),
            )
        }

        // 3. Merge LinkProperties from ConnectivityManager for all available networks
        fun mergeLink(link: LinkProperties, kind: InterfaceKind) {
            var name = link.interfaceName
            if (name == null && kind == InterfaceKind.VPN) {
                name = "tun0"
            }
            if (name == null) return
            val previous = records[name] ?: NetworkInterfaceInfo(name)
            val addresses = read {
                link.linkAddresses.mapNotNull {
                    it.address.hostAddress?.let { address -> InterfaceIp(address, it.prefixLength) }
                }
            }.orEmpty()
            val routes = read {
                link.routes.mapNotNull { route ->
                    val destination = route.destination
                    val host = destination.address.hostAddress ?: return@mapNotNull null
                    InterfaceRoute(
                        InterfaceIp(host, destination.prefixLength),
                        route.gateway?.takeUnless { it.isAnyLocalAddress }?.hostAddress,
                    )
                }
            }.orEmpty()
            val hasDefaultRoute = read { link.routes.any { it.isDefaultRoute } } ?: false
            val dns = read { link.dnsServers.mapNotNull { it.hostAddress } }.orEmpty()
            val domains = read { link.domains }
            records[name] = previous.copy(
                up = previous.up ?: true,
                kind = kind.takeUnless { it == InterfaceKind.OTHER } ?: previous.kind,
                isVpn = (kind == InterfaceKind.VPN) || previous.isVpn,
                isDefaultRoute = previous.isDefaultRoute || hasDefaultRoute,
                mtu = previous.mtu ?: if (Build.VERSION.SDK_INT >= 29) read { link.mtu }?.takeIf { it > 0 } else null,
                addresses = NetworkInterfacesPolicy.mergeAddresses(previous.addresses, addresses),
                routes = (previous.routes + routes).distinct(),
                dns = (previous.dns + dns).distinct(),
                domains = previous.domains ?: domains,
            )
        }

        read { connectivity.allNetworks.toList() }.orEmpty().forEach { network ->
            val capabilities = read { connectivity.getNetworkCapabilities(network) }
            val kind = when {
                capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true -> InterfaceKind.VPN
                capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true -> InterfaceKind.WIFI
                capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) == true -> InterfaceKind.ETHERNET
                capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) == true -> InterfaceKind.CELLULAR
                else -> InterfaceKind.OTHER
            }
            read { connectivity.getLinkProperties(network)?.let { mergeLink(it, kind) } }
        }

        // 4. Guarantee TUN presence and accurate VPN DNS
        val hasVpnRecord = records.values.any { it.isVpn || it.name.startsWith("tun") }
        if (!hasVpnRecord) {
            val tunMtu = DataStore.vpnMtu.takeIf { it > 0 } ?: 9000
            val tunDns = if (activeDnsServers.isNotEmpty() && isVpnConnected) activeDnsServers else listOf("172.19.0.2")
            records["tun0"] = NetworkInterfaceInfo(
                name = "tun0",
                up = isVpnConnected,
                mtu = tunMtu,
                pointToPoint = true,
                virtual = true,
                kind = InterfaceKind.VPN,
                isVpn = true,
                isDefaultRoute = isVpnConnected,
                addresses = listOf(InterfaceIp("172.19.0.1", 24)),
                dns = tunDns,
                routes = if (isVpnConnected) listOf(
                    InterfaceRoute(InterfaceIp("0.0.0.0", 0), "172.19.0.1"),
                ) else emptyList(),
            )
        } else {
            // If tun0 exists but has empty DNS, associate the active VPN DNS or default fake-ip DNS
            records.entries.forEach { (name, info) ->
                if ((info.isVpn || name.startsWith("tun")) && info.dns.isEmpty()) {
                    val fallbackDns = if (activeDnsServers.isNotEmpty() && isVpnConnected) activeDnsServers else listOf("172.19.0.2")
                    records[name] = info.copy(dns = fallbackDns, isVpn = true)
                }
            }
        }

        // 5. Classify hotspots and sort interfaces intelligently
        val result = records.values.map { info ->
            val hotspot = if (tethered == null && info.kind == InterfaceKind.WIFI) {
                HotspotKind.NONE
            } else {
                NetworkInterfacesPolicy.classifyHotspot(info.name, tethered)
            }
            info.copy(hotspot = hotspot)
        }.sortedWith(
            compareBy<NetworkInterfaceInfo> {
                NetworkInterfacesPolicy.sortPriority(it, activeIfaceName)
            }.thenBy { it.name }
        )

        return NetworkInterfacesSnapshot(
            interfaces = result,
            activeNetwork = activeOverview,
            partial = partial,
        )
    }
}
