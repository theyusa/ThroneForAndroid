package io.nekohasekai.sagernet.utils

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
            if (ip.isNullOrEmpty()) continue
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
     * Merges a history map (rows the caller kept from earlier polls) with a fresh ARP table and fresh Clash
     * entries, deduplicating by IP with Clash winning over ARP. Rows past [IDLE_MS] are dropped; the rest are
     * shown with active connections first and the most recently seen afterwards.
     */
    fun mergeDevices(
        history: Map<String, LanClientDevice>,
        arpIps: List<String>,
        clash: List<LanClientDevice>,
        hotspotIp: String?,
        nowMs: Long,
    ): List<LanClientDevice> {
        val byIp = LinkedHashMap<String, LanClientDevice>()
        arpIps.forEach { ip ->
            val historyRow = history[ip]
            byIp[ip] = LanClientDevice(
                ip,
                0,
                0L,
                0L,
                lastHost = null,
                lastSeenMs = historyRow?.lastSeenMs ?: nowMs,
                isHotspot = ip == hotspotIp,
            )
        }
        history.forEach { (ip, row) ->
            if (row.lastSeenMs >= nowMs - IDLE_MS) byIp.putIfAbsent(ip, row)
        }
        clash.forEach { entry ->
            byIp[entry.ip] = entry.copy(
                lastSeenMs = entry.lastSeenMs.takeIf { it > 0L } ?: nowMs,
                isHotspot = entry.ip == hotspotIp,
            )
        }
        return byIp.values
            .filter { nowMs - it.lastSeenMs < IDLE_MS || it.activeConnections > 0 }
            .sortedWith(compareByDescending<LanClientDevice> { it.activeConnections > 0 }
                .thenByDescending { it.lastSeenMs })
    }
}
