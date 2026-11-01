package io.nekohasekai.sagernet.utils

import io.nekohasekai.sagernet.database.SettingValidators
import java.security.MessageDigest

/**
 * The pure decisions of the Hotspot Gateway: no Android types, no sockets, no state, unit tested on the JVM.
 * Everything that touches [android.net.Network], the profile database or the shared status record lives in
 * [HotspotGatewayManager] and [HotspotGatewayState].
 */
object HotspotGatewayPolicy {

    /** Four attempts in total: 2 s after the first, 5 s after the second, 10 s after the third. */
    val RETRY_DELAYS_MS = listOf(2000L, 5000L, 10000L)

    /**
     * `hotspot_gateway_prev_selected` before anything was taken over. `0` is a *real* previous value meaning "no
     * profile was selected", so the two must never be confused: only [-1] says "the takeover never happened".
     */
    const val PREV_SELECTED_UNKNOWN: Long = -1L

    /** The floor between two reloads of *different* configs; the trailing check applies the last change after it. */
    const val MIN_RELOAD_MS: Long = 5000L

    /**
     * The wait after [attempt] (1-based), or null when the fourth attempt has just run: there is no fifth one.
     */
    fun retryDelayAfterAttempt(attempt: Int): Long? = RETRY_DELAYS_MS.getOrNull(attempt - 1)

    /**
     * A gateway candidate must ride on Wi-Fi and must not carry VPN transport, even when the network reports both:
     * a VPN's own transport wins and the record behind it is never used as a gateway.
     */
    fun isGatewayNetwork(hasWifiTransport: Boolean, hasVpnTransport: Boolean): Boolean =
        hasWifiTransport && !hasVpnTransport

    /** Blank, unparsable or out-of-range ports are rejected; there is no silent 2080 fallback. */
    fun isValidPort(port: Int): Boolean = SettingValidators.isPort(port)

    /** Same rule for raw dialog/settings text, which may be empty or non-numeric. */
    fun isValidPort(portText: String): Boolean = SettingValidators.isPort(portText)

    /** A pairing counts only with a valid port; the halves are used verbatim (the password is never trimmed here). */
    fun isValidPairing(pairing: HotspotPairing?): Boolean = pairing != null && isValidPort(pairing.port)

    /** Both halves non-empty: only then can a username-password exchange be attempted. */
    fun hasCredentials(pairing: HotspotPairing?): Boolean =
        pairing != null && pairing.user.isNotEmpty() && pairing.pass.isNotEmpty()

    /**
     * The status a probe outcome maps to. `FOUND` is the only status that lets a profile be written, so `00`/`02`
     * without a complete pairing (and every failure, and `FF`) keeps the profile, the last gateway and the last port
     * untouched:
     *
     * - failure of any class → [HotspotGatewayStatus.NOT_FOUND]
     * - `02` with an incomplete pairing → [HotspotGatewayStatus.AUTH_REQUIRED]
     * - `FF` → [HotspotGatewayStatus.UNSUPPORTED_AUTH]
     * - `02` with a valid pairing → [HotspotGatewayStatus.FOUND]
     * - `00` with a valid pairing → [HotspotGatewayStatus.FOUND] only without a credential; with one it is not our X
     *   and maps to [HotspotGatewayStatus.NOT_FOUND]
     */
    fun statusFor(result: Socks5ProbeResult, pairing: HotspotPairing?): HotspotGatewayStatus = when (result) {
        is Socks5ProbeResult.Failure -> HotspotGatewayStatus.NOT_FOUND
        is Socks5ProbeResult.Reply -> when {
            result.method == METHOD_NO_ACCEPTABLE -> HotspotGatewayStatus.UNSUPPORTED_AUTH
            result.method == METHOD_USER_PASS ->
                if (hasCredentials(pairing)) HotspotGatewayStatus.FOUND else HotspotGatewayStatus.AUTH_REQUIRED

            result.method == METHOD_NO_AUTH && isValidPairing(pairing) ->
                if (hasCredentials(pairing)) HotspotGatewayStatus.NOT_FOUND else HotspotGatewayStatus.FOUND
            else -> HotspotGatewayStatus.PAIRING_REQUIRED
        }
    }

    /** Whether this outcome may reach the managed profile (and the last-seen gateway/port). */
    fun writesProfile(result: Socks5ProbeResult, pairing: HotspotPairing?): Boolean =
        statusFor(result, pairing) == HotspotGatewayStatus.FOUND

    /**
     * The status to publish when the current gateway is already the stored, verified one and no re-check was asked
     * for. A [currentGateway] equal to [lastGateway] proves a previous [HotspotGatewayStatus.FOUND] (only that
     * outcome ever writes the last gateway), so the shared record is restored to FOUND here — otherwise the status
     * left behind by an off-Wi-Fi moment (NO_GATEWAY) or by an earlier failed probe (NOT_FOUND) would stick forever,
     * because this branch skips the probe altogether and nothing else republishes it.
     *
     * Returns null when the probe must run instead: a forced re-check, a different gateway, a pairing whose
     * fingerprint no longer matches what was applied (the config changed on the same gateway and must be re-applied),
     * or a [lastPort] that is not a usable candidate (never publish FOUND with port 0).
     */
    fun revisitStatus(
        currentGateway: String,
        lastGateway: String,
        lastPort: Int,
        currentFingerprint: String,
        appliedFingerprint: String,
        forceReload: Boolean,
    ): HotspotGatewayStatus? {
        if (forceReload || currentGateway != lastGateway) return null
        if (currentFingerprint != appliedFingerprint) return null
        return if (isValidPort(lastPort)) HotspotGatewayStatus.FOUND else null
    }

    /**
     * Whether another attempt can still change the answer: a timeout or a transport error may be transient, while a
     * protocol mismatch or a wrong version will say the same thing in ten seconds. Retries are bounded by
     * [retryDelayAfterAttempt].
     */
    fun shouldRetry(result: Socks5ProbeResult): Boolean = when (result) {
        is Socks5ProbeResult.Failure ->
            result.reason == Socks5ProbeFailure.TIMEOUT || result.reason == Socks5ProbeFailure.IO

        is Socks5ProbeResult.Reply -> false
    }

    // ------------------------------------------------------------------------------- automatic selection / restore

    /**
     * The takeover of the selection after a verified probe, or null when nothing may change (the managed profile is
     * already selected, or there is no managed profile).
     *
     * [SelectionPlan.recordPrevious] carries the value to store - and only while the stored one still is
     * [PREV_SELECTED_UNKNOWN], so a second takeover can never overwrite the selection the user had before the first
     * one. `0` is stored as-is: "there was no profile" is a legitimate previous value.
     */
    fun selectionPlan(selected: Long, managedId: Long, prevSelected: Long): SelectionPlan? {
        if (managedId <= 0L || selected == managedId) return null
        val recordPrevious = if (prevSelected == PREV_SELECTED_UNKNOWN) selected else null
        return SelectionPlan(recordPrevious)
    }

    /**
     * What a lost hotspot session does, decided before anything is written ([HotspotGatewayProfiles.restorePrevious]):
     *
     * - the takeover never happened ([PREV_SELECTED_UNKNOWN]) -> [RestoreResult.NothingToRestore];
     * - the user selected another profile meanwhile -> [RestoreResult.SelectionPreserved];
     * - a previous profile that still exists -> [RestoreResult.Restored];
     * - previous `0`, or the profile was deleted -> [RestoreResult.Stopped] only when the (default-off) `stopOnLoss`
     *   switch asks for it, otherwise [RestoreResult.WarningOnly]: nobody clears the selection and the service keeps
     *   running.
     */
    fun restorePlan(
        previous: Long,
        selected: Long,
        managedId: Long,
        previousExists: Boolean,
        stopOnLoss: Boolean,
    ): RestoreResult = when {
        previous == PREV_SELECTED_UNKNOWN -> RestoreResult.NothingToRestore
        selected != managedId -> RestoreResult.SelectionPreserved
        previous > 0L && previousExists -> RestoreResult.Restored(previous)
        stopOnLoss -> RestoreResult.Stopped
        else -> RestoreResult.WarningOnly
    }

    // ------------------------------------------------------------------------------------------------ backup restore

    /**
     * Whether a backup restore needs the Hotspot Gateway session reset: only a restore that replaces the profiles or
     * the settings can have moved the managed profile row or the selected/remembered ids (a routes-only restore does
     * not), so only then is [io.nekohasekai.sagernet.utils.HotspotGatewayManager.onBackupRestored] called.
     */
    fun restoreRequiresSessionReset(profiles: Boolean, settings: Boolean): Boolean = profiles || settings

    /**
     * The selection after a backup restore: when it pointed at the managed profile the restore just invalidated, it is
     * cleared to "no profile" (0); any other selection is the user's own and is preserved verbatim.
     */
    fun restoredSelection(selected: Long, managedId: Long): Long =
        if (managedId > 0L && selected == managedId) 0L else selected

    // ------------------------------------------------------------------------------------------------ reload claim

    /**
     * The pure "already applied?" pre-check of the reload claim. A candidate fingerprint equal to the stored one
     * returns [ReloadClaim.Same] (so [reloadDecision] short-circuits to `SameConfig` without a pointless write);
     * anything else returns [ReloadClaim.Claimed] and may proceed. The *atomic* part of the claim is not this equality
     * test but the single SQL statement
     * [io.nekohasekai.sagernet.database.preference.SettingEntry.Dao.compareAndSwapValue], which writes the fingerprint
     * only while the stored value still matches.
     */
    fun claimReloadDecision(storedFingerprint: String, candidateFingerprint: String, nowMs: Long): ReloadClaim =
        if (candidateFingerprint.isNotEmpty() && candidateFingerprint == storedFingerprint) ReloadClaim.Same
        else ReloadClaim.Claimed(candidateFingerprint, nowMs)

    /**
     * Whether a reload may be sent for [candidateFingerprint] at [nowMs]:
     *
     * - not while the managed profile is outside the running config ([ReloadDecision.NotRunning]);
     * - never for a fingerprint the core already runs ([ReloadDecision.SameConfig], decided by [claimReloadDecision]) -
     *   also the answer a concurrent claim loses with, because the winner stores the fingerprint in the same
     *   transaction;
     * - never inside [MIN_RELOAD_MS] of the previous reload ([ReloadDecision.MinInterval]);
     * - a wall clock that moved backwards counts as expired, so the claim can never lock up.
     */
    fun reloadDecision(
        runningUses: Boolean,
        candidateFingerprint: String,
        storedFingerprint: String,
        lastReloadAt: Long,
        nowMs: Long,
    ): ReloadDecision = when {
        !runningUses -> ReloadDecision.NotRunning
        claimReloadDecision(storedFingerprint, candidateFingerprint, nowMs) is ReloadClaim.Same ->
            ReloadDecision.SameConfig

        nowMs < lastReloadAt -> ReloadDecision.Reload
        nowMs - lastReloadAt < MIN_RELOAD_MS -> ReloadDecision.MinInterval
        else -> ReloadDecision.Reload
    }

    // ------------------------------------------------------------------------------------------------ config identity

    /**
     * The fingerprint of the applied config: gateway, port, username, password and UoT, hashed so the value can be
     * stored and compared without carrying the credential itself. Never logged with its input.
     */
    fun configFingerprint(gateway: String, port: Int, username: String, password: String, uot: Boolean): String =
        sha256Hex("$gateway $port $username $password $uot")

    /**
     * Whether a stored SOCKS5 profile already points at the gateway and pairing just verified: server, port, username,
     * password, UoT and version (always 5). A mismatch means the core would dial a stale address and the profile must
     * be rewritten first. Pure, so the start-time decision is unit-testable.
     */
    fun profileMatches(
        server: String,
        serverPort: Int,
        username: String,
        password: String,
        uot: Boolean,
        version: Int,
        gateway: String,
        port: Int,
        pairingUsername: String,
        pairingPassword: String,
        pairingUot: Boolean,
    ): Boolean = version == 5 && server == gateway && serverPort == port &&
        username == pairingUsername && password == pairingPassword && uot == pairingUot

    /** SHA-256 of [text] as lowercase hex (the digest style of SubscriptionReconcile.contentKey). */
    private fun sha256Hex(text: String): String {
        val bytes = MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8))
        val sb = StringBuilder(bytes.size * 2)
        for (b in bytes) {
            val v = b.toInt() and 0xFF
            sb.append(HEX[v shr 4]).append(HEX[v and 0xF])
        }
        return sb.toString()
    }

    private const val HEX = "0123456789abcdef"

    private const val METHOD_NO_AUTH = 0x00
    private const val METHOD_USER_PASS = 0x02
    private const val METHOD_NO_ACCEPTABLE = 0xFF
}

/**
 * The automatic-selection write plan: null [recordPrevious] means "the previous selection is already stored, keep
 * it"; a non-null value is written once, before the selection moves to the managed profile.
 */
data class SelectionPlan(@JvmField val recordPrevious: Long?)

/**
 * The outcome of the pure reload-claim pre-check ([HotspotGatewayPolicy.claimReloadDecision]): [ReloadClaim.Same] means
 * the stored fingerprint already equals the candidate (write nothing); [ReloadClaim.Claimed] means the claim may
 * proceed and carries the fingerprint + timestamp. The *atomic* "one reload per fingerprint" guarantee is the single
 * SQL statement in [io.nekohasekai.sagernet.database.preference.SettingEntry.Dao.compareAndSwapValue], not this type.
 */
sealed class ReloadClaim {

    /** The stored fingerprint already equals the candidate: a concurrent winner (or a previous reload) applied it. */
    object Same : ReloadClaim()

    /** This claim won: persist [fingerprint] and [appliedAt]. */
    data class Claimed(val fingerprint: String, val appliedAt: Long) : ReloadClaim()
}
