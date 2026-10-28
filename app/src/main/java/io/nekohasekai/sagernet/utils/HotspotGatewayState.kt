package io.nekohasekai.sagernet.utils

import android.content.Intent
import io.nekohasekai.sagernet.Action
import io.nekohasekai.sagernet.SagerNet
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.database.SettingsRegistry

/**
 * The states of the Hotspot Gateway, stored as the [code] in the shared `hotspot_gateway_status` setting.
 *
 * The list covers at least: disabled, pairing required, no gateway, probing, SOCKS5 found, not found, credentials
 * required, unsupported auth, wrong credentials, returned to the previous profile and could not return.
 */
enum class HotspotGatewayStatus(@JvmField val code: String) {
    DISABLED("DISABLED"),
    PAIRING_REQUIRED("PAIRING_REQUIRED"),
    NO_GATEWAY("NO_GATEWAY"),
    PROBING("PROBING"),
    FOUND("SOCKS5_FOUND"),
    NOT_FOUND("NOT_FOUND"),
    AUTH_REQUIRED("AUTH_REQUIRED"),
    UNSUPPORTED_AUTH("UNSUPPORTED_AUTH"),
    BAD_CREDENTIALS("BAD_CREDENTIALS"),
    REVERTED("REVERTED"),
    REVERT_FAILED("REVERT_FAILED");

    companion object {
        /** An unknown or missing stored code reads as [DISABLED]. */
        fun fromCode(code: String?): HotspotGatewayStatus = entries.firstOrNull { it.code == code } ?: DISABLED
    }
}

/** The pairing the gateway is probed and written with: the dialog's four fields, verbatim. */
data class HotspotPairing(
    @JvmField val port: Int,
    @JvmField val user: String,
    @JvmField val pass: String,
    @JvmField val uot: Boolean,
)

/** What the settings screen reads: no credential, no network object, no socket work. */
data class HotspotGatewaySnapshot(
    @JvmField val status: HotspotGatewayStatus,
    @JvmField val gateway: String,
    @JvmField val port: Int,
    @JvmField val updatedAt: Long,
)

/**
 * The shared status record of the Hotspot Gateway.
 *
 * It lives in the Room `settings` table through the uncached [DataStore.configurationStore], so the main process and
 * `:bg` read each other's outcome without a cache, and a settings summary can render it without ever touching
 * [HotspotGatewayManager.getWifiGateway] or a socket. [publish] writes the four fields in one `putAll` transaction and
 * then notifies the live UI with a package-scoped [Action.HOTSPOT_GATEWAY_STATUS] broadcast; it carries no credential.
 */
object HotspotGatewayState {

    /** The stored record; the UI summary reads only this. */
    fun snapshot(): HotspotGatewaySnapshot = HotspotGatewaySnapshot(
        status = HotspotGatewayStatus.fromCode(DataStore.hotspotGatewayStatus),
        gateway = DataStore.hotspotGatewayStatusGateway,
        port = DataStore.hotspotGatewayStatusPort,
        updatedAt = DataStore.hotspotGatewayStatusUpdatedAt,
    )

    /** The pairing to probe with, or null when the stored port is unusable (never a silent default port). */
    fun pairing(): HotspotPairing? {
        val port = DataStore.hotspotGatewayPort
        if (!HotspotGatewayPolicy.isValidPort(port)) return null
        return HotspotPairing(
            port = port,
            user = DataStore.hotspotGatewayUser,
            pass = DataStore.hotspotGatewayPass,
            uot = DataStore.hotspotGatewayUot,
        )
    }

    /**
     * Publish a new status for [gateway]:[port] as one transaction plus a package-scoped broadcast. An unchanged
     * record is not rewritten, so the timestamp means "last change" and quiet networks do not churn the table.
     */
    fun publish(status: HotspotGatewayStatus, gateway: String, port: Int) {
        val current = snapshot()
        if (current.status == status && current.gateway == gateway && current.port == port) return
        DataStore.configurationStore.putAll(
            mapOf(
                SettingsRegistry.HOTSPOT_GATEWAY_STATUS.key to SettingsRegistry.HOTSPOT_GATEWAY_STATUS.encode(status.code),
                SettingsRegistry.HOTSPOT_GATEWAY_STATUS_GATEWAY.key to
                    SettingsRegistry.HOTSPOT_GATEWAY_STATUS_GATEWAY.encode(gateway),
                SettingsRegistry.HOTSPOT_GATEWAY_STATUS_PORT.key to
                    SettingsRegistry.HOTSPOT_GATEWAY_STATUS_PORT.encode(port),
                SettingsRegistry.HOTSPOT_GATEWAY_STATUS_UPDATED_AT.key to
                    SettingsRegistry.HOTSPOT_GATEWAY_STATUS_UPDATED_AT.encode(System.currentTimeMillis()),
            ),
        )
        SagerNet.application.sendBroadcast(
            Intent(Action.HOTSPOT_GATEWAY_STATUS).setPackage(SagerNet.application.packageName),
        )
    }
}
