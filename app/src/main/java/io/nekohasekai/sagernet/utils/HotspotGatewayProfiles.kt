package io.nekohasekai.sagernet.utils

import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.database.GroupRepo
import io.nekohasekai.sagernet.database.GroupSort
import io.nekohasekai.sagernet.database.ProfileManager
import io.nekohasekai.sagernet.database.ProxyGroup
import io.nekohasekai.sagernet.database.SagerDatabase
import io.nekohasekai.sagernet.database.SettingsRegistry
import io.nekohasekai.sagernet.database.SortInput
import io.nekohasekai.sagernet.ktx.Logs
import io.nekohasekai.sagernet.outbound.types.Socks
import java.util.concurrent.Callable

/**
 * The selection transition after a verified probe: [HotspotGatewayPolicy.selectionPlan] decides, this file writes it
 * inside one settings transaction so the main process and `:bg` cannot both take the takeover.
 */
sealed class SelectionResult {

    /** The managed profile is already selected: nothing was written, nothing is broadcast. */
    object AlreadySelected : SelectionResult()

    /** The selection moved to [managedId]; [previous] is the previous selection now stored (or the one that was). */
    data class Selected(
        @JvmField val managedId: Long,
        @JvmField val previous: Long,
        /** True when a running service must be told through `Action.SWITCH_PROFILE` (which restarts it: no RELOAD). */
        @JvmField val broadcastSwitch: Boolean,
    ) : SelectionResult()

    /** The managed profile row is gone: nothing changed. */
    object NoProfile : SelectionResult()
}

/** What the restore after a hotspot loss did ([HotspotGatewayPolicy.restorePlan] decides, this file writes it). */
sealed class RestoreResult {

    /** [previousId] is selected again (and reset to the sentinel, so a later takeover is a new session). */
    data class Restored(@JvmField val previousId: Long) : RestoreResult()

    /** The user selected another profile meanwhile: their choice is preserved untouched. */
    object SelectionPreserved : RestoreResult()

    /** Previous was `0` or the profile is gone and stopping is off: warn only, change nothing. */
    object WarningOnly : RestoreResult()

    /** The same case with `hotspot_gateway_stop_on_loss` on: the selection is cleared and the service stopped. */
    object Stopped : RestoreResult()

    /** The takeover never happened (sentinel): nothing to restore, no warning, no stop. */
    object NothingToRestore : RestoreResult()
}

/**
 * Whether a reload may be sent for this config. Only [Reload] may reach `Action.RELOAD`; it is returned exactly once
 * per fingerprint by the compare-and-set in [HotspotGatewayProfiles.claimReload].
 */
sealed class ReloadDecision {

    /** The claim was taken: send the reload (unless a `SWITCH_PROFILE` restart already carries the change). */
    object Reload : ReloadDecision()

    /** The stored config already is this fingerprint: no reload. Also the answer a losing concurrent claim gets. */
    object SameConfig : ReloadDecision()

    /** The managed profile is not part of the running config: nothing could be reloaded. */
    object NotRunning : ReloadDecision()

    /** Inside the 5000 ms floor: the trailing check keeps the last change and reloads after the floor. */
    object MinInterval : ReloadDecision()
}

/**
 * The profile, selection and reload transitions of the Hotspot Gateway - all of them compare-and-set writes over the
 * shared settings table, so the main process and `:bg` (separate JVMs, no shared `Mutex`) cannot both win:
 *
 * - the previous selection is recorded exactly once and never overwritten by a later takeover,
 * - a loss restores the previous profile, or warns, or (opt-in) stops - decided by [HotspotGatewayPolicy.restorePlan],
 * - a reload for one config fingerprint is claimed exactly once, with a 5000 ms floor between different configs,
 * - the dedicated, fixed-named non-subscription group is ensured and the legacy managed profile is migrated into it.
 *
 * Nothing here logs or returns a credential; the fingerprint is a SHA-256 value (see
 * [HotspotGatewayPolicy.configFingerprint]).
 */
object HotspotGatewayProfiles {

    /** The fixed display name of the dedicated group and of its managed SOCKS profile (design §7.1). */
    const val MANAGED_NAME = "Hotspot Gateway"

    /**
     * Runs in one transaction: two processes probing the same gateway both get a definite answer.
     */
    fun selectManaged(managedId: Long, serviceRunning: Boolean): SelectionResult =
        SagerDatabase.instance.runInTransaction(Callable {
            if (managedId <= 0L) return@Callable SelectionResult.NoProfile
            if (ProfileManager.getProfile(managedId) == null) return@Callable SelectionResult.NoProfile

            val selected = DataStore.selectedProxy
            val plan = HotspotGatewayPolicy.selectionPlan(
                selected = selected,
                managedId = managedId,
                prevSelected = DataStore.hotspotGatewayPrevSelected,
            ) ?: return@Callable SelectionResult.AlreadySelected

            plan.recordPrevious?.let { DataStore.hotspotGatewayPrevSelected = it }
            DataStore.selectedProxy = managedId
            val previous = plan.recordPrevious ?: DataStore.hotspotGatewayPrevSelected
            SelectionResult.Selected(managedId, previous, serviceRunning)
        })

    /**
     * The restore of a lost hotspot session, written in one transaction: [RestoreResult.Restored] and
     * [RestoreResult.Stopped] are the only outcomes that touch the selection, and both reset the previous selection
     * back to the sentinel so a later verification starts a new session.
     */
    fun restorePrevious(stopOnLoss: Boolean): RestoreResult =
        SagerDatabase.instance.runInTransaction(Callable {
            val previous = DataStore.hotspotGatewayPrevSelected
            val managedId = DataStore.hotspotGatewayProfileId
            val selected = DataStore.selectedProxy
            val previousExists = previous > 0L && ProfileManager.getProfile(previous) != null
            val result = HotspotGatewayPolicy.restorePlan(
                previous = previous,
                selected = selected,
                managedId = managedId,
                previousExists = previousExists,
                stopOnLoss = stopOnLoss,
            )
            when (result) {
                is RestoreResult.Restored -> {
                    DataStore.selectedProxy = result.previousId
                    DataStore.hotspotGatewayPrevSelected = HotspotGatewayPolicy.PREV_SELECTED_UNKNOWN
                }

                RestoreResult.Stopped -> {
                    DataStore.selectedProxy = 0L
                    DataStore.hotspotGatewayPrevSelected = HotspotGatewayPolicy.PREV_SELECTED_UNKNOWN
                }

                else -> Unit
            }
            result
        })

    /**
     * The reload claim, a true compare-and-swap. The gate (running profile, same fingerprint, 5000 ms floor, wall
     * clock) is the pure [HotspotGatewayPolicy.reloadDecision]; the actual claim is a single `UPDATE ... WHERE
     * value = :stored` statement ([io.nekohasekai.sagernet.database.preference.SettingEntry.Dao.compareAndSwapValue])
     * that sets `hotspot_gateway_config_fingerprint` only if its stored value still equals the one read above:
     *
     * - 1 affected row -> this caller won; it persists [nowMs] to `hotspot_gateway_last_reload_at` (best-effort) and
     *   returns [ReloadDecision.Reload];
     * - 0 affected rows and the current value already equals [fingerprint] -> a concurrent winner applied the same
     *   config, [ReloadDecision.SameConfig] (no reload);
     * - 0 affected rows and the current value differs -> a concurrent claim for another fingerprint won,
     *   [ReloadDecision.SameConfig] (no reload from this caller);
     * - 0 affected rows because the row does not exist yet (first claim) -> [insertOrIgnore] materializes it; a rowid
     *   (`!= -1L`) means we inserted and won ([ReloadDecision.Reload]), `-1` means a concurrent insert won
     *   ([ReloadDecision.SameConfig]).
     *
     * The atomicity is in the single `UPDATE` (and in the single `INSERT OR IGNORE` for the first claim), so the
     * "two concurrent claims -> one reload" guarantee holds at the SQLite statement level; it does not rely on
     * [SagerDatabase.runInTransaction] or on `BEGIN EXCLUSIVE`/`DEFERRED`. No `ON CONFLICT ... DO UPDATE` is used, so
     * the statements run on the project's minSdk SQLite (3.9.2, API 24) and every later release.
     *
     * [nowMs] belongs to the caller so a test (or a skewed wall clock) can drive the 5000 ms floor; a clock that
     * moved backwards is treated as expired rather than locking the claim.
     */
    fun claimReload(managedId: Long, fingerprint: String, nowMs: Long): ReloadDecision {
        val runningUses = managedId > 0L && ProfileManager.runningUses(managedId)
        val storedFingerprint = DataStore.hotspotGatewayConfigFingerprint
        val decision = HotspotGatewayPolicy.reloadDecision(
            runningUses = runningUses,
            candidateFingerprint = fingerprint,
            storedFingerprint = storedFingerprint,
            lastReloadAt = DataStore.hotspotGatewayLastReloadAt,
            nowMs = nowMs,
        )
        if (decision != ReloadDecision.Reload) return decision

        val dao = SagerDatabase.instance.settingsDao()
        val fingerprintKey = SettingsRegistry.HOTSPOT_GATEWAY_CONFIG_FINGERPRINT.key

        // The single-statement compare-and-swap: claim the fingerprint iff it still equals what we read. A concurrent
        // claim in the other process changes it first and makes this UPDATE affect 0 rows.
        if (dao.compareAndSwapValue(fingerprintKey, storedFingerprint, fingerprint) == 1) {
            // Won the claim: the fingerprint row is already the claim; the timestamp is best-effort.
            DataStore.hotspotGatewayLastReloadAt = nowMs
            return ReloadDecision.Reload
        }

        // 0 rows: already applied, lost to a different fingerprint, or the row is absent (first claim).
        val current = dao[fingerprintKey]
        if (current == fingerprint) return ReloadDecision.SameConfig // a concurrent winner wrote the same config
        if (current != null) return ReloadDecision.SameConfig        // a different fingerprint won; no reload from us

        // First claim: materialize the row. INSERT OR IGNORE is itself atomic; a rowid != -1L means we inserted (won),
        // -1L means we lost a concurrent first insert.
        return if (dao.insertOrIgnore(fingerprintKey, fingerprint) != -1L) {
            DataStore.hotspotGatewayLastReloadAt = nowMs
            ReloadDecision.Reload
        } else {
            ReloadDecision.SameConfig
        }
    }

    /**
     * Records that the core about to start is built from [fingerprint] - the start applies the config itself, so no
     * reload follows, but a later check must not restart the same config again.
     */
    fun recordAppliedConfig(fingerprint: String, nowMs: Long = System.currentTimeMillis()) {
        SagerDatabase.instance.runInTransaction { recordAppliedLocked(fingerprint, nowMs) }
    }

    private fun recordAppliedLocked(fingerprint: String, nowMs: Long) {
        DataStore.hotspotGatewayConfigFingerprint = fingerprint
        DataStore.hotspotGatewayLastReloadAt = nowMs
    }

    // ------------------------------------------------------------------------------------------------ dedicated group

    /**
     * The dedicated group of the feature: a fixed-named ([MANAGED_NAME]), non-subscription (`url=""`) group whose id
     * is stored in `hotspot_gateway_group_id` once resolved (design §7.1). A stored id is used only while it still
     * names that exact group; anything else - first use, a group the user deleted, a restore from another device -
     * re-resolves the fixed name and stores the id again.
     *
     * The resolution also runs the legacy migration: the managed profile id an earlier commit stored is honored only
     * when its row still is the fixed-named SOCKS profile; that row keeps its id and moves into the dedicated group.
     * A profile of any other shape behind the stored id belongs to the user and is never adopted - the id is
     * forgotten so [ProfileManager.upsertNamed] creates a fresh row in the dedicated group instead of rewriting the
     * user's profile in place. The write never goes through [ProfileManager.addProfile]'s default/current-group
     * path: the caller passes this group's id explicitly.
     */
    suspend fun ensureDedicatedGroup(): ProxyGroup {
        val storedId = DataStore.hotspotGatewayGroupId
        if (storedId > 0L) {
            val stored = GroupRepo.get(storedId)
            if (stored != null && stored.name == MANAGED_NAME && !stored.isSubscription) return stored
        }
        val group = GroupRepo.ensureNamedNonSubscription(MANAGED_NAME)
        DataStore.hotspotGatewayGroupId = group.id
        migrateLegacyManagedProfile(group.id)
        return group
    }

    /**
     * Moves the legacy managed profile into the dedicated group, keeping its id (the single
     * `UPDATE profiles SET gid..., user_order...` of ProxyEntity.Dao.setGroup).
     * Runs once, when the dedicated group is first resolved: after the move the profile already sits in the
     * dedicated group and later calls return early. Both touched groups get a reload notification after the
     * transaction commits, and the destination group's kept sort is repositioned.
     */
    private suspend fun migrateLegacyManagedProfile(groupId: Long) {
        val legacyId = DataStore.hotspotGatewayProfileId
        if (legacyId <= 0L) return
        val legacy = ProfileManager.getProfile(legacyId) ?: return
        if (legacy.outbound !is Socks || legacy.name != MANAGED_NAME) {
            // Not the fixed-named SOCKS profile: a user profile that must never be adopted; forget the id.
            DataStore.hotspotGatewayProfileId = 0L
            return
        }
        if (legacy.groupId == groupId) return
        val oldGroupId = legacy.groupId
        SagerDatabase.instance.runInTransaction {
            // Fresh re-read inside the transaction: the row may have moved or been deleted meanwhile.
            val fresh = ProfileManager.getProfile(legacyId) ?: return@runInTransaction
            if (fresh.groupId == groupId) return@runInTransaction
            val dao = SagerDatabase.proxyDao
            dao.setGroup(fresh.id, groupId, dao.nextOrder(groupId))
        }
        Logs.d("hotspot gateway profile $legacyId moved into the dedicated group")
        GroupRepo.postReload(oldGroupId)
        GroupRepo.postReload(groupId)
        GroupSort.repositionLater(mapOf(groupId to listOf(legacyId)), SortInput.MEMBERSHIP)
    }
}
