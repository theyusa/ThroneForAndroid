package io.nekohasekai.sagernet.database.backup

import io.nekohasekai.sagernet.database.SettingsRegistry
import io.nekohasekai.sagernet.outbound.json.JsonInput

/**
 * The backup-side redaction of the Hotspot Gateway identity.
 *
 * The pairing (host, port, username, password, UoT) is device-local: it is never written into a backup, either as a
 * settings row or inside the managed SOCKS profile's `outbound_json`. Both functions fail closed - anything that
 * cannot be proven safe stops the export instead of leaking a credential.
 */
object HotspotBackupSanitizer {

    /**
     * Removes only the `username` and `password` of the managed SOCKS profile's `outbound_json`; every other field is
     * kept verbatim. A malformed document, or one that is not a SOCKS outbound, stops the export (fail-closed) rather
     * than risk exporting a credential that was not recognised as one.
     */
    fun redactManagedSocks(outboundJson: String): String {
        val obj = JsonInput.parseObjectOrNull(outboundJson)
            ?: error("hotspot gateway managed profile has malformed outbound JSON")
        if (obj.string("type") != "socks") error("hotspot gateway managed profile is not a SOCKS outbound")
        obj.remove("username")
        obj.remove("password")
        return obj.toCompact()
    }

    /**
     * The settings snapshot without the device-local keys (R10 §8.3): the Hotspot Gateway pairing and its runtime /
     * session fields are always elided, the rest pass through unchanged.
     */
    fun sanitizeExportedSettings(stored: Map<String, String>): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        for ((key, value) in stored) if (key !in SettingsRegistry.DEVICE_LOCAL_KEYS) out[key] = value
        return out
    }
}
