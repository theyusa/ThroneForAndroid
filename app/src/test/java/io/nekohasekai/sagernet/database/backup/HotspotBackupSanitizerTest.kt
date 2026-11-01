package io.nekohasekai.sagernet.database.backup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The fail-closed redaction of the Hotspot Gateway identity in a backup: the managed SOCKS profile's credentials are
 * removed from its `outbound_json`, and the pairing / runtime settings rows never reach an exported snapshot.
 */
class HotspotBackupSanitizerTest {

    @Test
    fun redactManagedSocksRemovesOnlyTheCredentials() {
        val json =
            """{"type":"socks","server":"192.168.137.1","server_port":2080,"username":"canary-user","password":"canary-pass","version":5}"""
        val out = HotspotBackupSanitizer.redactManagedSocks(json)

        assertFalse(out.contains("canary-user"))
        assertFalse(out.contains("canary-pass"))
        assertFalse(out.contains("username"))
        assertFalse(out.contains("password"))

        // Every other field survives, verbatim.
        assertTrue(out.contains("\"server\":\"192.168.137.1\""))
        assertTrue(out.contains("\"server_port\":2080"))
        assertTrue(out.contains("\"type\":\"socks\""))
        assertTrue(out.contains("\"version\":5"))
    }

    @Test
    fun redactManagedSocksFailsClosedOnMalformedJson() {
        assertThrows(IllegalStateException::class.java) {
            HotspotBackupSanitizer.redactManagedSocks("{not json")
        }
    }

    @Test
    fun redactManagedSocksFailsClosedOnNonSocks() {
        // A non-SOCKS outbound must never be half-redacted: it fails the export instead.
        assertThrows(IllegalStateException::class.java) {
            HotspotBackupSanitizer.redactManagedSocks("""{"type":"vless","server":"example.com","uuid":"canary-uuid"}""")
        }
    }

    @Test
    fun sanitizeExportedSettingsStripsTheHotspotIdentityOnly() {
        val stored = linkedMapOf(
            "hotspot_gateway_user" to "canary-user",
            "hotspot_gateway_pass" to "canary-pass",
            "hotspot_gateway_port" to "2080",
            "hotspot_gateway_last" to "192.168.137.1",
            "hotspot_gateway_last_port" to "2080",
            "hotspot_gateway_profile_id" to "9",
            "hotspot_gateway_group_id" to "3",
            "hotspot_gateway_prev_selected" to "5",
            "hotspot_gateway_config_fingerprint" to "abc123",
            "hotspot_gateway_applied_fingerprint" to "def456",
            "hotspot_gateway_last_reload_at" to "123456",
            "hotspot_gateway_status" to "SOCKS5_FOUND",
            "hotspot_gateway_status_gateway" to "192.168.137.1",
            "hotspot_gateway_status_port" to "2080",
            "hotspot_gateway_status_updated_at" to "123456",
            "hotspot_gateway_enabled" to "true",
            "hotspot_gateway_uot" to "false",
            "hotspot_gateway_stop_on_loss" to "false",
            "current_group" to "1",
            "some_other_key" to "keep-me",
        )

        val out = HotspotBackupSanitizer.sanitizeExportedSettings(stored)

        // The whole Hotspot Gateway identity and its runtime/session state is elided.
        for (key in stored.keys) {
            if (key.startsWith("hotspot_gateway_")) {
                assertFalse("key $key must be elided", out.containsKey(key))
            }
        }

        // Everything else passes through untouched.
        assertEquals("1", out["current_group"])
        assertEquals("keep-me", out["some_other_key"])
        assertEquals(stored.keys.count { !it.startsWith("hotspot_gateway_") }, out.size)
    }
}
