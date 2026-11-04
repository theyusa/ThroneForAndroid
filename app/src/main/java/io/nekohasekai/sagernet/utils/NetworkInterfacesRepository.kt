package io.nekohasekai.sagernet.utils

import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.NetworkCapabilities
import android.os.Build
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
                kind = NetworkInterfacesPolicy.inferKind(name),
                addresses = NetworkInterfacesPolicy.mergeAddresses(addresses, fallback),
            )
        }

        fun mergeLink(link: LinkProperties, kind: InterfaceKind) {
            val name = link.interfaceName ?: return
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
            val dns = read { link.dnsServers.mapNotNull { it.hostAddress } }.orEmpty()
            records[name] = previous.copy(
                up = previous.up ?: true,
                kind = kind.takeUnless { it == InterfaceKind.OTHER } ?: previous.kind,
                mtu = previous.mtu ?: if (Build.VERSION.SDK_INT >= 29) read { link.mtu }?.takeIf { it > 0 } else null,
                addresses = NetworkInterfacesPolicy.mergeAddresses(previous.addresses, addresses),
                routes = (previous.routes + routes).distinct(),
                dns = (previous.dns + dns).distinct(),
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

        val result = records.values.map { info ->
            val hotspot = if (tethered == null && info.kind == InterfaceKind.WIFI) {
                HotspotKind.NONE
            } else {
                NetworkInterfacesPolicy.classifyHotspot(info.name, tethered)
            }
            info.copy(hotspot = hotspot)
        }.sortedWith(
            compareBy<NetworkInterfaceInfo> {
                when (it.hotspot) {
                    HotspotKind.CONFIRMED -> 0
                    HotspotKind.POSSIBLE -> 1
                    HotspotKind.NONE -> 2
                }
            }.thenBy { it.up != true }.thenBy { it.loopback }.thenBy { it.name },
        )

        return NetworkInterfacesSnapshot(result, partial)
    }
}
