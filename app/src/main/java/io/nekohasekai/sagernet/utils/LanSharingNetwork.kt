package io.nekohasekai.sagernet.utils

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.database.SettingsRegistry
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.net.Inet4Address
import java.net.NetworkInterface
import java.util.concurrent.TimeUnit
import kotlin.math.abs

/**
 * Android network sniffing (hotspot / Wi-Fi local IPs, `/proc/net/arp`) and the Clash API
 * `/connections` poller that feeds [LanSharingLogics].
 */
object LanSharingNetwork {

    private val httpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(1, TimeUnit.SECONDS)
            .readTimeout(2, TimeUnit.SECONDS)
            .build()
    }

    /**
     * Builds the loopback `/connections` URL from the sign-encoded `core_box_clash_api` port.
     */
    fun buildClashConnectionsUrl(rawPort: Int): String {
        val port = abs(rawPort).takeIf { it > 0 } ?: abs(SettingsRegistry.CORE_BOX_CLASH_API.default)
        return "http://127.0.0.1:$port/connections"
    }

    fun clashUrl(): String = buildClashConnectionsUrl(DataStore.coreBoxClashApi)

    fun isCellularOrVirtualInterface(name: String): Boolean {
        val n = name.lowercase()
        return n.startsWith("rmnet") ||
            n.startsWith("v4-") ||
            n.startsWith("r_") ||
            n.startsWith("ccmni") ||
            n.startsWith("pdp") ||
            n.startsWith("wwan") ||
            n.startsWith("dummy") ||
            n.startsWith("tun") ||
            n.startsWith("sit") ||
            n.startsWith("ip6") ||
            n.startsWith("clat") ||
            n.startsWith("seth") ||
            n.startsWith("ipa") ||
            n.startsWith("epdg") ||
            n.startsWith("bond") ||
            n == "lo"
    }

    fun isHotspotInterfaceName(name: String): Boolean {
        if (isCellularOrVirtualInterface(name)) return false
        val n = name.lowercase()
        return n.startsWith("ap") ||
            n == "wlan1" ||
            n.startsWith("swlan") ||
            n.startsWith("softap") ||
            n.startsWith("wigig") ||
            n.contains("hotspot") ||
            n.contains("rndis")
    }

    fun isHotspotIpPattern(ip: String): Boolean {
        return ip.startsWith("192.168.43.") ||
            ip.startsWith("192.168.44.") ||
            ip.startsWith("192.168.49.") ||
            ip.startsWith("192.168.50.") ||
            ip.startsWith("172.20.10.")
    }

    fun isValidHotspotIp(ip: String): Boolean {
        if (ip.isBlank()) return false
        return ip != "127.0.0.1" &&
            ip != "0.0.0.0" &&
            !ip.startsWith("127.") &&
            !ip.startsWith("169.254.") &&
            !ip.startsWith("172.19.")
    }

    fun isHotspotActive(context: Context): Boolean {
        val appCtx = context.applicationContext
        val wifiManager = appCtx.getSystemService(Context.WIFI_SERVICE) as? WifiManager
        val apEnabled = runCatching {
            val method = wifiManager?.javaClass?.getDeclaredMethod("isWifiApEnabled")
            method?.isAccessible = true
            method?.invoke(wifiManager) as? Boolean
        }.getOrNull()
        if (apEnabled == true) return true

        val apState = runCatching {
            val method = wifiManager?.javaClass?.getDeclaredMethod("getWifiApState")
            method?.isAccessible = true
            method?.invoke(wifiManager) as? Int
        }.getOrNull()
        if (apState == 12 || apState == 13) return true

        val cm = appCtx.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        val tethered = runCatching {
            val method = cm?.javaClass?.getDeclaredMethod("getTetheredIfaces")
            method?.isAccessible = true
            (method?.invoke(cm) as? Array<*>)?.filterIsInstance<String>().orEmpty()
        }.getOrDefault(emptyList())
        if (tethered.isNotEmpty()) return true

        return findHotspotIp(context) != null
    }

    fun findHotspotIp(context: Context): String? {
        val appCtx = context.applicationContext
        val cm = appCtx.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        val tetheredIfaces = runCatching {
            val method = cm?.javaClass?.getDeclaredMethod("getTetheredIfaces")
            method?.isAccessible = true
            (method?.invoke(cm) as? Array<*>)?.filterIsInstance<String>().orEmpty()
        }.getOrDefault(emptyList())

        val interfaces = runCatching {
            NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()
        }.getOrDefault(emptyList())

        // 1. Check explicitly tethered interfaces first.
        for (tetherName in tetheredIfaces) {
            val intf = interfaces.firstOrNull { it.name.equals(tetherName, ignoreCase = true) } ?: continue
            val ips = intf.inetAddresses.toList()
                .filterIsInstance<Inet4Address>()
                .mapNotNull { it.hostAddress }
                .filter { isValidHotspotIp(it) }
            val matched = ips.firstOrNull { isHotspotIpPattern(it) } ?: ips.firstOrNull()
            if (matched != null) return matched
        }

        // 2. Check interfaces matching known hotspot interface names.
        val wifiIp = findWifiIp(context)
        for (intf in interfaces) {
            val isUp = runCatching { intf.isUp && !intf.isLoopback }.getOrDefault(false)
            if (!isUp || !isHotspotInterfaceName(intf.name)) continue
            val ips = intf.inetAddresses.toList()
                .filterIsInstance<Inet4Address>()
                .mapNotNull { it.hostAddress }
                .filter { isValidHotspotIp(it) && it != wifiIp }
            val matched = ips.firstOrNull { isHotspotIpPattern(it) } ?: ips.firstOrNull()
            if (matched != null) return matched
        }

        // 3. Fallback: non-cellular interface holding a classic hotspot subnet IP.
        for (intf in interfaces) {
            val isUp = runCatching { intf.isUp && !intf.isLoopback }.getOrDefault(false)
            if (!isUp || isCellularOrVirtualInterface(intf.name)) continue
            val matched = intf.inetAddresses.toList()
                .filterIsInstance<Inet4Address>()
                .mapNotNull { it.hostAddress }
                .firstOrNull { isValidHotspotIp(it) && it != wifiIp && isHotspotIpPattern(it) }
            if (matched != null) return matched
        }
        return null
    }

    fun findWifiIp(context: Context): String? {
        val appCtx = context.applicationContext
        val cm = appCtx.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return null
        val networks = runCatching { cm.allNetworks.toList() }.getOrDefault(emptyList())
        for (network in networks) {
            val caps = runCatching { cm.getNetworkCapabilities(network) }.getOrNull() ?: continue
            if (!caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) continue
            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) continue
            val lp = runCatching { cm.getLinkProperties(network) }.getOrNull() ?: continue
            val ip = lp.linkAddresses
                .map { it.address }
                .filterIsInstance<Inet4Address>()
                .mapNotNull { it.hostAddress }
                .firstOrNull { isValidHotspotIp(it) }
            if (ip != null) return ip
        }
        return null
    }

    fun readArp(): List<String> {
        val text = runCatching { File("/proc/net/arp").readText() }.getOrNull() ?: return emptyList()
        return LanSharingLogics.parseArpFile(text)
    }

    /**
     * Testable core of the Clash `/connections` poller: builds the URL + optional Bearer header
     * and returns null on any transport exception.
     */
    fun pollConnectionsWith(
        rawPort: Int,
        secret: String,
        transport: (url: String, authorizationHeader: String?) -> String?,
    ): String? {
        val url = buildClashConnectionsUrl(rawPort)
        val trimmedSecret = secret.trim()
        val auth = if (trimmedSecret.isNotEmpty()) "Bearer $trimmedSecret" else null
        return runCatching { transport(url, auth) }.getOrNull()
    }

    fun fetchConnections(url: String = clashUrl(), secret: String = DataStore.coreBoxClashApiSecret): String? {
        val trimmedSecret = secret.trim()
        val auth = if (trimmedSecret.isNotEmpty()) "Bearer $trimmedSecret" else null
        return runCatching {
            val reqBuilder = Request.Builder().url(url).get()
            if (auth != null) {
                reqBuilder.header("Authorization", auth)
            }
            httpClient.newCall(reqBuilder.build()).execute().use { response ->
                if (!response.isSuccessful) null else response.body?.string()
            }
        }.getOrNull()
    }
}
