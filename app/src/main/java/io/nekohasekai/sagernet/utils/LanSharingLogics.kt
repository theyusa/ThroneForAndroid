package io.nekohasekai.sagernet.utils

import io.nekohasekai.sagernet.database.SettingValidators
import io.nekohasekai.sagernet.outbound.link.LinkBuilder
import kotlin.math.abs
import org.json.JSONObject

/**
 * One row of the LAN Sharing device list; entries are keyed by IP.
 */
data class LanClientDevice(
    val ip: String,
    val activeConnections: Int,
    val uploadBytes: Long,
    val downloadBytes: Long,
    val lastHost: String?,
    val lastSeenMs: Long,
    val isHotspot: Boolean,
)

/**
 * The pure decisions of the LAN Sharing client list: turning the ARP table and the Clash API /connections
 * payload into device rows, and merging them with the caller's history into one sorted view.
 *
 * Nothing here touches Android: it runs in JVM unit tests and is fed by [LanSharingNetwork].
 */
object LanSharingLogics {

    /** A quiet device may still be shown for this long after its last sighting. */
    const val IDLE_MS: Long = 600_000L // 10 minutes

    /**
     * Parses `/proc/net/arp`. Ends with the device IP; rows with an all-zero MAC are hardware assertions, not
     * devices, and loopback / link-local / our own VPN range rows are not LAN clients either.
     */
    fun parseArpFile(text: String): List<String> {
        return text.lines().drop(1).mapNotNull { line ->
            val parts = line.trim().split(Regex("\\s+"))
            if (parts.size < 4) return@mapNotNull null
            val ip = parts[0]
            val mac = parts[3]
            val isReal = !parts.all { it == "IP address" } &&
                mac.isNotBlank() && mac != "00:00:00:00:00:00"
            val usable = listOf("127.", "169.254.", "172.19.").none { it -> ip.startsWith(it) }
            if (isReal && usable) ip else null
        }.distinct()
    }

    /**
     * Parses the Clash API `/connections` body: per source IP the number of open connections, summed traffic
     * and the most recent destination host. Empty, malformed or connection-less bodies yield an empty list.
     * Local sources (loopback, the tun range and, at merge time, the phone's own addresses) are never devices.
     */
    fun parseConnections(json: String): List<LanClientDevice> {
        val root = runCatching { JSONObject().put("body", json) }.getOrNull()
        val body = root?.optString("body")
        if (body.isNullOrEmpty()) return emptyList()
        val tidy = body.trim()
        if (tidy.isEmpty()) return emptyList()
        val parsed = runCatching { JSONObject(tidy) }.getOrNull() ?: return emptyList()
        val connections = parsed.optJSONArray("connections") ?: return emptyList()

        // Aggregate traffic per source IP while remembering the last destination host.
        data class Acc(var active: Int, var up: Long, var down: Long, var lastHost: String?)
        val acc = LinkedHashMap<String, Acc>()
        for (i in 0 until connections.length()) {
            val item = connections.getJSONObject(i)
            val meta = item.optJSONObject("metadata") ?: continue
            val ip = meta.optString("sourceIP")
            if (ip.isNullOrEmpty() || isLocalSource(ip)) continue
            val entry = acc.getOrPut(ip) { Acc(0, 0L, 0L, null) }
            entry.active += 1
            entry.up += item.optLong("upload", 0L)
            entry.down += item.optLong("download", 0L)
            item.optString("host").takeIf { it.isNotEmpty() }?.let { entry.lastHost = it }
            meta.optString("host").takeIf { it.isNotEmpty() }?.let { entry.lastHost = it }
        }
        return acc.map { (ip, a) ->
            LanClientDevice(ip, a.active, a.up, a.down, a.lastHost, lastSeenMs = 0L, isHotspot = false)
        }
    }

    /**
     * Sources that are not LAN clients: loopback (`127.*`, `::1`), the VPN/tun range (`172.19.*`) and,
     * via [localIps], the phone's own hotspot / Wi-Fi addresses. Pure and unit-testable.
     */
    fun isLocalSource(ip: String, localIps: Set<String> = emptySet()): Boolean =
        ip in localIps || ip.startsWith("127.") || ip == "::1" || ip.startsWith("172.19.")

    /**
     * Whether both IPv4 addresses share the same /24 prefix. Anything that is not a dotted IPv4 address
     * never shares a subnet. Pure and unit-testable.
     */
    fun isSameSubnet24(first: String, second: String): Boolean {
        val a = ipv4ToInt(first) ?: return false
        val b = ipv4ToInt(second) ?: return false
        return a > 0L && (a ushr 8) == (b ushr 8)
    }

    /** Packs a dotted-quad IPv4 string into a Long (0..255 per octet), or null when it is not an IPv4 address. */
    private fun ipv4ToInt(ip: String): Long? {
        val parts = ip.trim().split(".")
        if (parts.size != 4) return null
        var value = 0L
        for (part in parts) {
            val octet = part.toIntOrNull() ?: return null
            if (octet !in 0..255) return null
            value = value shl 8 or octet.toLong()
        }
        return value
    }

    /**
     * Merges a history map (rows the caller kept from earlier polls) with a fresh ARP table and fresh Clash
     * entries, deduplicating by IP with Clash winning over ARP. Rows past [IDLE_MS] are dropped; the rest are
     * shown with active connections first and the most recently seen afterwards. Local sources ([localIps]
     * plus the static loopback and tun ranges) never become devices.
     */
    fun mergeDevices(
        history: Map<String, LanClientDevice>,
        arpIps: List<String>,
        clash: List<LanClientDevice>,
        hotspotIp: String?,
        localIps: Set<String>,
        nowMs: Long,
    ): List<LanClientDevice> {
        val byIp = LinkedHashMap<String, LanClientDevice>()
        arpIps.forEach { ip ->
            if (isLocalSource(ip, localIps)) return@forEach
            val historyRow = history[ip]
            byIp[ip] = LanClientDevice(
                ip,
                0,
                0L,
                0L,
                lastHost = null,
                lastSeenMs = historyRow?.lastSeenMs ?: nowMs,
                isHotspot = isHotspotClient(ip, hotspotIp),
            )
        }
        history.forEach { (ip, row) ->
            if (isLocalSource(ip, localIps)) return@forEach
            if (row.lastSeenMs >= nowMs - IDLE_MS) byIp.putIfAbsent(ip, row)
        }
        clash.forEach { entry ->
            if (isLocalSource(entry.ip, localIps)) return@forEach
            byIp[entry.ip] = entry.copy(
                lastSeenMs = entry.lastSeenMs.takeIf { it > 0L } ?: nowMs,
                isHotspot = isHotspotClient(entry.ip, hotspotIp),
            )
        }
        return byIp.values
            .filter { nowMs - it.lastSeenMs < IDLE_MS || it.activeConnections > 0 }
            .sortedWith(compareByDescending<LanClientDevice> { it.activeConnections > 0 }
                .thenByDescending { it.lastSeenMs })
    }

    /** A hotspot client is any IPv4 device that shares the hotspot interface's /24 prefix. */
    private fun isHotspotClient(ip: String, hotspotIp: String?): Boolean =
        hotspotIp != null && isSameSubnet24(ip, hotspotIp)

    data class ClashApiAutoEnable(val newRawPort: Int, val changed: Boolean)

    /**
     * When LAN Sharing turns on and Clash API is off (`<= 0`), flips the sign-encoded port positive so
     * `/connections` becomes reachable after a service reload.
     */
    fun ensureClashApiEnabledPort(rawPort: Int): ClashApiAutoEnable {
        if (rawPort > 0) return ClashApiAutoEnable(rawPort, changed = false)
        val positive = abs(rawPort).takeIf { it in 1..65535 } ?: 9090
        return ClashApiAutoEnable(positive, changed = true)
    }

    /**
     * Validates the user-entered port string in the Hotspot Gateway configuration dialog.
     * Blank or out-of-range strings return null and never fall back to a default port.
     */
    fun validateDialogPort(rawInput: String): Int? {
        val port = rawInput.trim().toIntOrNull() ?: return null
        return port.takeIf { SettingValidators.isPort(it) }
    }

    /**
     * Pure status classifier used by the LAN Sharing hero/summary view so any persisted [HotspotGatewaySnapshot]
     * (including a stale PROBING record after process restart) maps cleanly without throwing.
     */
    fun statusSummaryKind(enabled: Boolean, snapshot: HotspotGatewaySnapshot): String {
        if (!enabled) return "DISABLED"
        return snapshot.status.name
    }

    /**
     * Builds a shareable `socks5://[user[:pass]@]host:port[?uot=1][#remark]` URI compatible with
     * [io.nekohasekai.sagernet.outbound.types.Socks.parseFromLink]. Username is trimmed once; password is kept
     * verbatim. Returns an empty string when [host] is blank or [port] is invalid.
     */
    fun buildSocks5ShareLink(
        host: String,
        port: Int,
        username: String,
        password: String,
        uot: Boolean,
        remark: String = "",
    ): String {
        val cleanHost = host.trim()
        if (cleanHost.isEmpty() || !SettingValidators.isPort(port)) return ""
        val url = LinkBuilder("socks5")
        url.host = cleanHost
        url.port = port
        val cleanUser = username.trim()
        if (cleanUser.isNotEmpty()) url.setUserName(cleanUser)
        if (password.isNotEmpty()) url.setPassword(password)
        if (uot) url.addQueryItem("uot", "1")
        val cleanRemark = remark.trim()
        if (cleanRemark.isNotEmpty()) url.fragment = cleanRemark
        return url.build()
    }
}
