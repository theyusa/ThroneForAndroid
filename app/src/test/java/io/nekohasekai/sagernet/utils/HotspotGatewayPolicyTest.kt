package io.nekohasekai.sagernet.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The pure decisions of the Hotspot Gateway: retry budget, gateway eligibility, port validation and which probe
 * outcomes may ever reach the managed profile.
 */
class HotspotGatewayPolicyTest {

    @Test
    fun retryDelaysStopAfterFourAttempts() {
        // After attempt 1/2/3 the probe waits 2 s, 5 s and 10 s; the fourth attempt is the last one.
        assertEquals(listOf(2000L, 5000L, 10000L), (1..3).map { HotspotGatewayPolicy.retryDelayAfterAttempt(it) })
        assertNull(HotspotGatewayPolicy.retryDelayAfterAttempt(4))
        assertNull(HotspotGatewayPolicy.retryDelayAfterAttempt(5))
    }

    @Test
    fun vpnTransportIsRejectedEvenWithWifi() {
        assertFalse(HotspotGatewayPolicy.isGatewayNetwork(hasWifiTransport = true, hasVpnTransport = true))
        assertTrue(HotspotGatewayPolicy.isGatewayNetwork(hasWifiTransport = true, hasVpnTransport = false))
        assertFalse(HotspotGatewayPolicy.isGatewayNetwork(hasWifiTransport = false, hasVpnTransport = false))
        assertFalse(HotspotGatewayPolicy.isGatewayNetwork(hasWifiTransport = false, hasVpnTransport = true))
    }

    @Test
    fun blankAndInvalidPortsAreRejected() {
        // No silent 2080: an empty, unparsable or out-of-range port never becomes a candidate.
        assertFalse(HotspotGatewayPolicy.isValidPort(""))
        assertFalse(HotspotGatewayPolicy.isValidPort("   "))
        assertFalse(HotspotGatewayPolicy.isValidPort("abc"))
        assertFalse(HotspotGatewayPolicy.isValidPort("0"))
        assertFalse(HotspotGatewayPolicy.isValidPort("65536"))
        assertFalse(HotspotGatewayPolicy.isValidPort(0))
        assertFalse(HotspotGatewayPolicy.isValidPort(65536))
        assertTrue(HotspotGatewayPolicy.isValidPort(1))
        assertTrue(HotspotGatewayPolicy.isValidPort(2080))
        assertTrue(HotspotGatewayPolicy.isValidPort("2080"))
    }

    @Test
    fun onlyAVerifiedReplyWithACompletePairingWritesAProfile() {
        val pairing = HotspotPairing(port = 2080, user = "user", pass = "pass", uot = false)

        // 02 (username-password) is a SOCKS5 discovery and can produce a profile. 00 (no auth) with a configured
        // credential is rejected as "not our X": a server that skips auth never matches a credentialed pairing.
        assertTrue(HotspotGatewayPolicy.writesProfile(Socks5ProbeResult.Reply(0x02), pairing))
        assertEquals(HotspotGatewayStatus.FOUND, HotspotGatewayPolicy.statusFor(Socks5ProbeResult.Reply(0x02), pairing))
        assertFalse(HotspotGatewayPolicy.writesProfile(Socks5ProbeResult.Reply(0x00), pairing))
        assertEquals(HotspotGatewayStatus.NOT_FOUND, HotspotGatewayPolicy.statusFor(Socks5ProbeResult.Reply(0x00), pairing))

        // 00 with a valid port and no credential is still a plain no-auth SOCKS5 discovery.
        assertEquals(
            HotspotGatewayStatus.FOUND,
            HotspotGatewayPolicy.statusFor(
                Socks5ProbeResult.Reply(0x00),
                HotspotPairing(port = 2080, user = "", pass = "", uot = false),
            ),
        )

        // 02 with an incomplete pairing is "credentials required", never a profile write.
        assertEquals(
            HotspotGatewayStatus.AUTH_REQUIRED,
            HotspotGatewayPolicy.statusFor(Socks5ProbeResult.Reply(0x02), null),
        )
        assertEquals(
            HotspotGatewayStatus.AUTH_REQUIRED,
            HotspotGatewayPolicy.statusFor(
                Socks5ProbeResult.Reply(0x02),
                HotspotPairing(port = 2080, user = "", pass = "", uot = false),
            ),
        )
        assertFalse(
            HotspotGatewayPolicy.writesProfile(
                Socks5ProbeResult.Reply(0x02),
                HotspotPairing(port = 2080, user = "user", pass = "", uot = false),
            ),
        )

        // 05 FF means the server refused every offered method: "unsupported auth", not a wrong password.
        assertEquals(
            HotspotGatewayStatus.UNSUPPORTED_AUTH,
            HotspotGatewayPolicy.statusFor(Socks5ProbeResult.Reply(0xFF), pairing),
        )
        assertFalse(HotspotGatewayPolicy.writesProfile(Socks5ProbeResult.Reply(0xFF), pairing))

        // No failure is ever a profile, whatever its class.
        for (failure in Socks5ProbeFailure.entries) {
            val result = Socks5ProbeResult.Failure(failure)
            assertFalse(HotspotGatewayPolicy.writesProfile(result, pairing))
            assertEquals(HotspotGatewayStatus.NOT_FOUND, HotspotGatewayPolicy.statusFor(result, pairing))
        }

        // Even a no-auth discovery needs a valid port before the pairing counts.
        assertEquals(
            HotspotGatewayStatus.PAIRING_REQUIRED,
            HotspotGatewayPolicy.statusFor(
                Socks5ProbeResult.Reply(0x00),
                HotspotPairing(port = 0, user = "", pass = "", uot = false),
            ),
        )
    }

    @Test
    fun revisitingTheVerifiedGatewayRestoresFound() {
        // The early-return branch in checkAndUpdate() must republish the record: only FOUND ever writes
        // hotspotGatewayLast, so a matching gateway with a usable port and an unchanged config is a verified one again.
        assertEquals(
            HotspotGatewayStatus.FOUND,
            HotspotGatewayPolicy.revisitStatus(
                currentGateway = "192.168.137.1",
                lastGateway = "192.168.137.1",
                lastPort = 2080,
                currentFingerprint = "fp",
                appliedFingerprint = "fp",
                forceReload = false,
            ),
        )

        // Regression: NO_GATEWAY (Wi-Fi dropped) or NOT_FOUND (earlier failed probe) used to stick forever,
        // because this branch returned without publishing anything.
        // -> revisitStatus() is what publishes FOUND in that branch, so the stale status is replaced.

        // A forced re-check (switch/dialog path) never short-circuits: probe again.
        assertNull(
            HotspotGatewayPolicy.revisitStatus(
                currentGateway = "192.168.137.1",
                lastGateway = "192.168.137.1",
                lastPort = 2080,
                currentFingerprint = "fp",
                appliedFingerprint = "fp",
                forceReload = true,
            ),
        )

        // Another gateway (or no stored gateway) always probes.
        assertNull(
            HotspotGatewayPolicy.revisitStatus(
                currentGateway = "192.168.43.1",
                lastGateway = "192.168.137.1",
                lastPort = 2080,
                currentFingerprint = "fp",
                appliedFingerprint = "fp",
                forceReload = false,
            ),
        )
        assertNull(
            HotspotGatewayPolicy.revisitStatus(
                currentGateway = "192.168.137.1",
                lastGateway = "",
                lastPort = 2080,
                currentFingerprint = "fp",
                appliedFingerprint = "fp",
                forceReload = false,
            ),
        )

        // A config change on the same gateway (e.g. a new port or credential) must re-probe and re-apply, not reuse
        // the verified record.
        assertNull(
            HotspotGatewayPolicy.revisitStatus(
                currentGateway = "192.168.137.1",
                lastGateway = "192.168.137.1",
                lastPort = 2080,
                currentFingerprint = "fp2",
                appliedFingerprint = "fp",
                forceReload = false,
            ),
        )

        // Never claim FOUND with an unusable port: fall through so pairing()/probe can decide.
        assertNull(
            HotspotGatewayPolicy.revisitStatus(
                currentGateway = "192.168.137.1",
                lastGateway = "192.168.137.1",
                lastPort = 0,
                currentFingerprint = "fp",
                appliedFingerprint = "fp",
                forceReload = false,
            ),
        )
        assertNull(
            HotspotGatewayPolicy.revisitStatus(
                currentGateway = "192.168.137.1",
                lastGateway = "192.168.137.1",
                lastPort = 65536,
                currentFingerprint = "fp",
                appliedFingerprint = "fp",
                forceReload = false,
            ),
        )
    }

    @Test
    fun transientProbeFailuresAreTheOnlyRetryableOnes() {
        assertTrue(
            HotspotGatewayPolicy.shouldRetry(Socks5ProbeResult.Failure(Socks5ProbeFailure.TIMEOUT)),
        )
        assertTrue(HotspotGatewayPolicy.shouldRetry(Socks5ProbeResult.Failure(Socks5ProbeFailure.IO)))
        // A protocol mismatch will not change within 17 seconds.
        assertFalse(HotspotGatewayPolicy.shouldRetry(Socks5ProbeResult.Failure(Socks5ProbeFailure.NOT_SOCKS5)))
        assertFalse(HotspotGatewayPolicy.shouldRetry(Socks5ProbeResult.Failure(Socks5ProbeFailure.PROTOCOL)))
        assertFalse(HotspotGatewayPolicy.shouldRetry(Socks5ProbeResult.Reply(0x00)))
    }

    // ---------------------------------------------------------------------------- selection (automatic takeover)

    @Test
    fun selectionRecordsThePreviousChoiceOnlyOnce() {
        // Unknown previous (-1): the current selection becomes the previous one. `0` is a real previous value
        // ("there was no profile"), not a hole.
        assertEquals(
            0L,
            HotspotGatewayPolicy.selectionPlan(
                selected = 0L,
                managedId = 9L,
                prevSelected = HotspotGatewayPolicy.PREV_SELECTED_UNKNOWN,
            )!!.recordPrevious,
        )
        assertEquals(
            5L,
            HotspotGatewayPolicy.selectionPlan(
                selected = 5L,
                managedId = 9L,
                prevSelected = HotspotGatewayPolicy.PREV_SELECTED_UNKNOWN,
            )!!.recordPrevious,
        )

        // Already recorded: never overwritten, so a second takeover cannot destroy the first previous value.
        val second = HotspotGatewayPolicy.selectionPlan(selected = 12L, managedId = 9L, prevSelected = 5L)
        assertNotNull(second)
        assertNull(second!!.recordPrevious)

        // Already selected, or no managed profile to select: no transition at all.
        assertNull(HotspotGatewayPolicy.selectionPlan(selected = 9L, managedId = 9L, prevSelected = -1L))
        assertNull(HotspotGatewayPolicy.selectionPlan(selected = 5L, managedId = 0L, prevSelected = -1L))
    }

    // ---------------------------------------------------------------------------------- restore after a hotspot loss

    @Test
    fun missingPreviousDefaultsToWarningOnly() {
        // Previous selection was "no profile" (0): clear nothing, stop nothing - only a warning may come out.
        assertEquals(
            RestoreResult.WarningOnly,
            HotspotGatewayPolicy.restorePlan(
                previous = 0L,
                selected = 9L,
                managedId = 9L,
                previousExists = false,
                stopOnLoss = false,
            ),
        )

        // The profile the previous selection pointed at was deleted: the same default applies.
        assertEquals(
            RestoreResult.WarningOnly,
            HotspotGatewayPolicy.restorePlan(
                previous = 4L,
                selected = 9L,
                managedId = 9L,
                previousExists = false,
                stopOnLoss = false,
            ),
        )

        // Only the explicit, default-off opt-in may clear the selection and stop the service.
        assertEquals(
            RestoreResult.Stopped,
            HotspotGatewayPolicy.restorePlan(
                previous = 0L,
                selected = 9L,
                managedId = 9L,
                previousExists = false,
                stopOnLoss = true,
            ),
        )
        assertEquals(
            RestoreResult.Stopped,
            HotspotGatewayPolicy.restorePlan(
                previous = 4L,
                selected = 9L,
                managedId = 9L,
                previousExists = false,
                stopOnLoss = true,
            ),
        )

        // A previous profile that still exists is restored.
        assertEquals(
            RestoreResult.Restored(4L),
            HotspotGatewayPolicy.restorePlan(
                previous = 4L,
                selected = 9L,
                managedId = 9L,
                previousExists = true,
                stopOnLoss = false,
            ),
        )

        // The sentinel (the takeover never happened): nothing to restore, no warning, no stop.
        assertEquals(
            RestoreResult.NothingToRestore,
            HotspotGatewayPolicy.restorePlan(
                previous = HotspotGatewayPolicy.PREV_SELECTED_UNKNOWN,
                selected = 9L,
                managedId = 9L,
                previousExists = false,
                stopOnLoss = true,
            ),
        )

        // The user picked another profile while the hotspot was gone: their choice is never overwritten.
        assertEquals(
            RestoreResult.SelectionPreserved,
            HotspotGatewayPolicy.restorePlan(
                previous = 4L,
                selected = 12L,
                managedId = 9L,
                previousExists = true,
                stopOnLoss = true,
            ),
        )
    }

    // --------------------------------------------------------------------------------------- reload claim / 5 s floor

    @Test
    fun aReloadClaimIsTakenOnceForTheSameFingerprint() {
        val now = 1_000_000L

        // First claim: nothing applied yet, the running config uses the managed profile.
        assertEquals(
            ReloadDecision.Reload,
            HotspotGatewayPolicy.reloadDecision(
                runningUses = true,
                candidateFingerprint = "fp",
                storedFingerprint = "",
                lastReloadAt = 0L,
                nowMs = now,
            ),
        )

        // The winning claim stores fingerprint + timestamp; a concurrent second claim of the same fingerprint
        // reads that write and produces no reload. (The two writers are serialized by the single UPDATE statement.)
        assertEquals(
            ReloadDecision.SameConfig,
            HotspotGatewayPolicy.reloadDecision(
                runningUses = true,
                candidateFingerprint = "fp",
                storedFingerprint = "fp",
                lastReloadAt = now,
                nowMs = now + 1,
            ),
        )

        // A real config change inside the 5 s floor waits (the trailing check keeps the last change).
        assertEquals(
            ReloadDecision.MinInterval,
            HotspotGatewayPolicy.reloadDecision(
                runningUses = true,
                candidateFingerprint = "fp2",
                storedFingerprint = "fp",
                lastReloadAt = now,
                nowMs = now + 4999,
            ),
        )
        assertEquals(
            ReloadDecision.Reload,
            HotspotGatewayPolicy.reloadDecision(
                runningUses = true,
                candidateFingerprint = "fp2",
                storedFingerprint = "fp",
                lastReloadAt = now,
                nowMs = now + 5000,
            ),
        )

        // The managed profile is not part of the running config: nothing to reload.
        assertEquals(
            ReloadDecision.NotRunning,
            HotspotGatewayPolicy.reloadDecision(
                runningUses = false,
                candidateFingerprint = "fp2",
                storedFingerprint = "fp",
                lastReloadAt = now,
                nowMs = now + 100_000,
            ),
        )

        // A wall clock that moved backwards must not lock the claim forever: it is treated as expired.
        assertEquals(
            ReloadDecision.Reload,
            HotspotGatewayPolicy.reloadDecision(
                runningUses = true,
                candidateFingerprint = "fp2",
                storedFingerprint = "fp",
                lastReloadAt = now + 50_000,
                nowMs = now,
            ),
        )
    }

    // ------------------------------------------------------------------------------- config fingerprint (never logged)

    @Test
    fun configFingerprintCoversEveryPairingField() {
        fun fp(gateway: String, port: Int, user: String, pass: String, uot: Boolean) =
            HotspotGatewayPolicy.configFingerprint(gateway, port, user, pass, uot)

        val base = fp("192.168.137.1", 2080, "user", "secret-pass", false)
        assertEquals(base, fp("192.168.137.1", 2080, "user", "secret-pass", false))

        assertNotEquals(base, fp("192.168.43.1", 2080, "user", "secret-pass", false))
        assertNotEquals(base, fp("192.168.137.1", 1080, "user", "secret-pass", false))
        assertNotEquals(base, fp("192.168.137.1", 2080, "user2", "secret-pass", false))
        assertNotEquals(base, fp("192.168.137.1", 2080, "user", "other-pass", false))
        assertNotEquals(base, fp("192.168.137.1", 2080, "user", "secret-pass", true))

        // The value travels through logs and the shared record: it must not carry the credential itself.
        assertFalse(base.contains("secret-pass"))
        assertFalse(base.contains("user"))
    }

    @Test
    fun profileMatchesRejectsAnyStaleField() {
        fun matches(
            server: String, serverPort: Int, username: String, password: String, uot: Boolean, version: Int,
        ) = HotspotGatewayPolicy.profileMatches(
            server = server, serverPort = serverPort, username = username, password = password, uot = uot, version = version,
            gateway = "192.168.137.1", port = 2080, pairingUsername = "user", pairingPassword = "secret-pass", pairingUot = false,
        )

        // The exact verified pairing matches.
        assertTrue(matches("192.168.137.1", 2080, "user", "secret-pass", false, 5))
        // A stale server (gateway IP changed) must not match.
        assertFalse(matches("192.168.43.1", 2080, "user", "secret-pass", false, 5))
        // A stale port.
        assertFalse(matches("192.168.137.1", 1080, "user", "secret-pass", false, 5))
        // A stale username / password.
        assertFalse(matches("192.168.137.1", 2080, "user2", "secret-pass", false, 5))
        assertFalse(matches("192.168.137.1", 2080, "user", "other-pass", false, 5))
        // A stale UoT.
        assertFalse(matches("192.168.137.1", 2080, "user", "secret-pass", true, 5))
        // A non-SOCKS5 version never matches.
        assertFalse(matches("192.168.137.1", 2080, "user", "secret-pass", false, 4))
    }

    // ------------------------------------------------- reload claim: the pure pre-check (SQL CAS is not JVM-testable)

    /**
     * The pure [HotspotGatewayPolicy.claimReloadDecision], the equality test [HotspotGatewayPolicy.reloadDecision]
     * turns into `SameConfig`. The *actual* single-statement compare-and-swap
     * ([io.nekohasekai.sagernet.database.preference.SettingEntry.Dao.compareAndSwapValue], a plain
     * `UPDATE ... WHERE value = :expectedValue` returning the affected-row count, plus an `INSERT OR IGNORE` for the
     * first claim) is what makes "two concurrent claims -> one reload" atomic across the two processes; proving THAT
     * needs a Room-instrumented test and is not covered here. This test pins down the decision the CAS is built on:
     * a candidate equal to the stored fingerprint is `Same` (no reload), anything else is a fresh `Claimed`.
     */
    @Test
    fun claimReloadDecisionSeparatesSameFromClaimed() {
        val t0 = 1_000_000L

        // Nothing stored yet: the claim proceeds and carries the fingerprint + timestamp.
        assertEquals(
            ReloadClaim.Claimed("fp", t0),
            HotspotGatewayPolicy.claimReloadDecision(storedFingerprint = "", candidateFingerprint = "fp", nowMs = t0),
        )

        // The winner's write is read by a concurrent second claim for the SAME fingerprint: flagged Same.
        assertEquals(
            ReloadClaim.Same,
            HotspotGatewayPolicy.claimReloadDecision(storedFingerprint = "fp", candidateFingerprint = "fp", nowMs = t0 + 1),
        )

        // A genuinely different config is a fresh claim.
        assertEquals(
            ReloadClaim.Claimed("fp2", t0 + 1),
            HotspotGatewayPolicy.claimReloadDecision(storedFingerprint = "fp", candidateFingerprint = "fp2", nowMs = t0 + 1),
        )
    }

    // ------------------------------------------------------------------ backup restore reconciliation

    @Test
    fun selectiveRestoreRequiresSessionReset() {
        // Only a restore that replaces the profiles or the settings can have moved the managed profile or the
        // selected/remembered ids: profiles or settings true requires the session reset, routes-only does not.
        assertTrue(HotspotGatewayPolicy.restoreRequiresSessionReset(profiles = true, settings = false))
        assertTrue(HotspotGatewayPolicy.restoreRequiresSessionReset(profiles = false, settings = true))
        assertTrue(HotspotGatewayPolicy.restoreRequiresSessionReset(profiles = true, settings = true))
        assertFalse(HotspotGatewayPolicy.restoreRequiresSessionReset(profiles = false, settings = false))
    }

    @Test
    fun selectionPointingAtOldManagedProfileIsCleared() {
        // The old selection named the managed profile the restore invalidated: cleared to "no profile" (0).
        assertEquals(0L, HotspotGatewayPolicy.restoredSelection(selected = 9L, managedId = 9L))

        // Any other selection is the user's own and is preserved.
        assertEquals(5L, HotspotGatewayPolicy.restoredSelection(selected = 5L, managedId = 9L))

        // No managed profile: nothing can be cleared, the selection is preserved.
        assertEquals(5L, HotspotGatewayPolicy.restoredSelection(selected = 5L, managedId = 0L))
        assertEquals(0L, HotspotGatewayPolicy.restoredSelection(selected = 0L, managedId = 0L))
    }
}
