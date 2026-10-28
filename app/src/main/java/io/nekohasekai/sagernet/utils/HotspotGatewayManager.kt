package io.nekohasekai.sagernet.utils

import android.content.Intent
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import android.os.Handler
import android.os.Looper
import io.nekohasekai.sagernet.Action
import io.nekohasekai.sagernet.SagerNet
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.database.ProfileManager
import io.nekohasekai.sagernet.database.ProxyEntity
import io.nekohasekai.sagernet.ktx.Logs
import io.nekohasekai.sagernet.ktx.runOnDefaultDispatcher
import io.nekohasekai.sagernet.outbound.types.Socks
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.net.Inet4Address

/**
 * Keeps the managed "Hotspot Gateway" profile on the Wi-Fi gateway that actually speaks SOCKS5.
 *
 * A check ([checkAndUpdate]) locates the gateway ([getWifiGateway]), probes the paired port with the anonymous
 * [Socks5Probe] (at most [HotspotGatewayPolicy.retryDelayAfterAttempt] four attempts) and only a [FOUND] outcome -
 * a verified `00`/`02` reply plus a complete [HotspotPairing] - writes the profile and the last-seen gateway/port.
 * A paired port that does not verify (refused, timed out, not SOCKS5, `FF`) falls back to the bounded parallel
 * scan of [HotspotPortDiscovery]: the paired and last-verified port first, then the fixed candidate list, at most
 * four probes in flight, still anonymous. Only the port the scan verified becomes the persistent pairing port
 * ([DataStore.hotspotGatewayPort]) and reaches the profile; a failed scan changes neither profile nor selection.
 * Every outcome is recorded through [HotspotGatewayState] for the settings screen and the other process; logs carry
 * the gateway, the port and a failure class, never a credential or a payload.
 *
 * A [FOUND] outcome also hands the selection over ([HotspotGatewayProfiles.selectManaged]): the previous selection is
 * recorded once and a running service is told through `SWITCH_PROFILE` only. A gateway that disappears starts a
 * 10-second loss grace ([LOSS_GRACE_MS]) that a later verification cancels; when it expires the previous profile
 * comes back, or - when there is none and stopping is off - only a warning is shown. Reloading happens exclusively
 * through the [HotspotGatewayProfiles.claimReload] compare-and-set.
 */
object HotspotGatewayManager {

    /** Network callbacks coalesce: capability storms arrive as one check. */
    private const val DEBOUNCE_MS = 500L

    /**
     * How long a loss may last before the previous profile is restored: the retries of one probe (2/5/10 s) plus the
     * debounce may still bring the gateway back, and a verification inside the window cancels the restore entirely.
     */
    private const val LOSS_GRACE_MS = 10_000L

    private val debounceHandler = Handler(Looper.getMainLooper())

    /**
     * A dedicated looper for the loss restore so that [scheduleCheck]'s debounce cancellation can never drop a pending
     * restore: the two lanes (debounce vs. loss grace) live on different handlers and only cancel their own runnable.
     */
    private val lossHandler = Handler(Looper.getMainLooper())
    private val mutex = Mutex()

    /** Epoch-ms of the loss session in progress, 0 when none is armed ([lossRunnable] is the pending restore). */
    @Volatile
    private var lossSince = 0L

    /** Set once a "no previous profile" warning has been shown for the current loss session. */
    @Volatile
    private var lossWarned = false

    private val lossRunnable = Runnable {
        runOnDefaultDispatcher { finishLossSession() }
    }

    /** The coalesced network-event check; [scheduleCheck] removes only this, never [lossRunnable]. */
    private val debounceRunnable = Runnable {
        runOnDefaultDispatcher {
            checkAndUpdate(forceReload = false)
            withContext(Dispatchers.Main) {
                onGatewayChanged?.invoke()
            }
        }
    }

    /** The `:bg` service monitor's check job (launched by [startServiceMonitor]); cancelled on teardown. */
    @Volatile
    private var serviceMonitorJob: Job? = null

    /** The single trailing reload of a config change deferred by [HotspotGatewayPolicy.MIN_RELOAD_MS] (design §7.2). */
    @Volatile
    private var trailingReloadJob: Job? = null


    /**
     * Wi-Fi networks the callback has seen, minus any carrying VPN transport. The lookup re-reads the live
     * capabilities, so a network that turned into a VPN is never used from a stale record.
     */
    private val trackedNetworks = LinkedHashSet<Network>()
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private var isRegistered = false
    var onGatewayChanged: (() -> Unit)? = null

    fun init() {
        if (DataStore.hotspotGatewayEnabled) {
            cancelLossSession()
            runOnDefaultDispatcher {
                checkAndUpdate(forceReload = false)
            }
            registerCallback()
        } else {
            unregisterCallback()
            cancelLossSession()
            HotspotGatewayState.publish(HotspotGatewayStatus.DISABLED, gateway = "", port = 0)
        }
    }

    fun onSwitchChanged(enabled: Boolean) {
        if (enabled) {
            cancelLossSession()
            runOnDefaultDispatcher {
                checkAndUpdate(forceReload = true)
            }
            registerCallback()
        } else {
            unregisterCallback()
            cancelLossSession()
            cancelTrailingReload()
            // Switching the feature off restores the previous selection immediately (design 7.3); the DISABLED
            // record below is the one the settings screen keeps, so the restore publishes no status of its own.
            runOnDefaultDispatcher {
                applyRestore(HotspotGatewayProfiles.restorePrevious(DataStore.hotspotGatewayStopOnLoss), publish = false)
            }
            HotspotGatewayState.publish(HotspotGatewayStatus.DISABLED, gateway = "", port = 0)
        }
        onGatewayChanged?.invoke()
    }

    // ---------------------------------------------------------------------------- backup restore

    /**
     * The Hotspot Gateway side of a completed backup restore ([HotspotGatewayPolicy.restoreRequiresSessionReset]):
     * the pairing (`hotspot_gateway_user`/`pass`/`port`/`uot`) is device-local and survives; every field that names a
     * profile, a group or an applied config is reset so the managed profile is re-resolved only after a fresh probe
     * (the restore replaced the profiles table). A selection that pointed at the now-invalid managed profile is
     * cleared, the pending loss restore is dropped and the shared status record reset. Runs on a background dispatcher
     * (Room writes block the thread).
     */
    fun onBackupRestored() {
        val managedId = DataStore.hotspotGatewayProfileId
        cancelLossSession()
        cancelTrailingReload()
        DataStore.hotspotGatewayPrevSelected = HotspotGatewayPolicy.PREV_SELECTED_UNKNOWN
        DataStore.hotspotGatewayLast = ""
        DataStore.hotspotGatewayLastPort = 0
        DataStore.hotspotGatewayConfigFingerprint = ""
        DataStore.hotspotGatewayAppliedFingerprint = ""
        DataStore.hotspotGatewayLastReloadAt = 0L
        DataStore.hotspotGatewayProfileId = 0L
        DataStore.hotspotGatewayGroupId = 0L
        DataStore.selectedProxy = HotspotGatewayPolicy.restoredSelection(DataStore.selectedProxy, managedId)
        HotspotGatewayState.publish(HotspotGatewayStatus.DISABLED, gateway = "", port = 0)
        Logs.w("hotspot gateway state reset after backup restore")
    }

    // ---------------------------------------------------------------------------- service lifecycle (:bg)

    /**
     * Starts the Wi-Fi monitor for the `:bg` service lifetime ([io.nekohasekai.sagernet.bg.BaseService.preInit]).
     * Idempotent: a monitor whose check is still in flight is left alone, and the network callback is already guarded
     * by [registerCallback]. The main process keeps its own monitor (started by [init]) for the settings screen.
     */
    fun startServiceMonitor() {
        if (!DataStore.hotspotGatewayEnabled) return
        if (serviceMonitorJob?.isActive == true) return
        cancelLossSession()
        registerCallback()
        serviceMonitorJob = runOnDefaultDispatcher {
            checkAndUpdate(forceReload = false)
        }
    }

    /**
     * Releases the `:bg` service monitor ([io.nekohasekai.sagernet.bg.BaseService.killProcesses]): the in-flight check
     * is cancelled, the network callback unregistered and any pending loss restore dropped. Idempotent; a restart
     * re-arms it through [startServiceMonitor].
     */
    fun stopServiceMonitor() {
        serviceMonitorJob?.cancel()
        serviceMonitorJob = null
        unregisterCallback()
        cancelLossSession()
        cancelTrailingReload()
    }

    /**
     * The start-time check of the managed root profile (design §8): one anonymous probe (no retries, no port scan)
     * confirms a verified SOCKS5, then the separate RFC1929 [Socks5Authenticator] check proves the credential. The
     * profile on disk must match the verified gateway and pairing; a stale address is rewritten before the start.
     * On any failure - no pairing/gateway, no verified SOCKS5, a still-mismatched profile, an accepted random
     * credential, or a refused credential - [HotspotGatewayAuthException] is thrown, which
     * [io.nekohasekai.sagernet.bg.BaseService] turns into an EN/TR message through `failRunner`: the start never
     * silently falls back to the old profile.
     *
     * Returns null (the caller keeps its own profile, nothing is blocked) unless [profileId] is the managed root:
     * a chain hop, route, group front/landing or auto-selector member that merely *uses* the managed profile must
     * start normally.
     */
    suspend fun beforeCoreConnect(profileId: Long): ProxyEntity? {
        if (!DataStore.hotspotGatewayEnabled) return null
        if (profileId <= 0L || profileId != DataStore.hotspotGatewayProfileId) return null

        var fresh = ProfileManager.getProfile(profileId)
        val pairing = HotspotGatewayState.pairing()
        val candidate = getWifiGateway()
        // The start verifies with one quick probe; without a pairing or a gateway there is nothing to verify against,
        // and the start must not silently fall back to the old profile.
        if (pairing == null || candidate == null) {
            throw HotspotGatewayAuthException(Socks5AuthFailure.NOT_FOUND)
        }
        val (network, gateway) = candidate

        // 1) A single anonymous probe - no retries, no port scan: the start fails loud and fast.
        val probeResult = withContext(Dispatchers.IO) {
            Socks5Probe().probe(gateway, pairing.port, network.socketFactory)
        }
        val status = HotspotGatewayPolicy.statusFor(probeResult, pairing)
        HotspotGatewayState.publish(status, gateway = gateway, port = pairing.port)
        if (status != HotspotGatewayStatus.FOUND) {
            Logs.w("hotspot gateway start probe failed $gateway:${pairing.port} $status")
            throw HotspotGatewayAuthException(Socks5AuthFailure.NOT_FOUND)
        }

        // The profile on disk must already point at the verified gateway+pairing. If the gateway IP (or the pairing)
        // changed since the profile was written, rewrite it now - the core must never dial a stale address.
        if (!profileMatchesVerified(fresh, gateway, pairing)) {
            applyVerifiedGateway(gateway, pairing.port, pairing, reload = false, allowSwitch = false)
            val rewritten = ProfileManager.getProfile(profileId)
            if (rewritten == null || !profileMatchesVerified(rewritten, gateway, pairing)) {
                Logs.w("hotspot gateway profile still mismatches after rewrite $gateway:${pairing.port}")
                throw HotspotGatewayAuthException(Socks5AuthFailure.NOT_FOUND)
            }
            fresh = rewritten
        }

        // Without a credential there is no RFC1929 exchange to run (and nothing to reject): the anonymous probe above
        // already confirmed the no-auth SOCKS5.
        if (!HotspotGatewayPolicy.hasCredentials(pairing)) return fresh

        val fingerprint = HotspotGatewayPolicy.configFingerprint(
            gateway = gateway,
            port = pairing.port,
            username = pairing.user,
            password = pairing.pass,
            uot = pairing.uot,
        )
        // 2) Negative control: a random, certainly-wrong credential must be refused. A server that accepts it does
        // not enforce the configured credential - refuse it before the real check runs.
        val negative = withContext(Dispatchers.IO) {
            Socks5Authenticator(networkHandle = network.networkHandle).negativeControl(
                host = gateway,
                port = pairing.port,
                socketFactory = network.socketFactory,
            )
        }
        if (negative is Socks5AuthResult.Success) {
            throw HotspotGatewayAuthException(Socks5AuthFailure.CREDENTIALS_NOT_ENFORCED)
        }

        // 3) The second, credential-carrying check - on the very network the core will dial through.
        val result = withContext(Dispatchers.IO) {
            Socks5Authenticator(networkHandle = network.networkHandle).authenticate(
                host = gateway,
                port = pairing.port,
                username = pairing.user,
                password = pairing.pass,
                socketFactory = network.socketFactory,
            )
        }
        when (result) {
            is Socks5AuthResult.Success -> {
                // 4) The config this start applies is the config a later check must not reload again.
                HotspotGatewayProfiles.recordAppliedConfig(fingerprint)
                return fresh
            }

            is Socks5AuthResult.Failure -> {
                val status = when (result.reason) {
                    Socks5AuthFailure.AUTH_FAILED -> HotspotGatewayStatus.BAD_CREDENTIALS
                    Socks5AuthFailure.UNSUPPORTED_AUTH -> HotspotGatewayStatus.UNSUPPORTED_AUTH
                    Socks5AuthFailure.INVALID_CREDENTIALS -> HotspotGatewayStatus.AUTH_REQUIRED
                    // Timeout/IO/protocol are gateway problems: the record keeps its FOUND/no-SOCKS5 meaning.
                    else -> null
                }
                if (status != null) HotspotGatewayState.publish(status, gateway = gateway, port = pairing.port)
                Logs.w("hotspot gateway start check failed $gateway:${pairing.port} ${result.reason}")
                throw HotspotGatewayAuthException(result.reason)
            }
        }
    }

    /**
     * Whether [profile]'s SOCKS bean already points at the verified [gateway] and [pairing] ([HotspotGatewayPolicy.profileMatches]).
     * A null profile or a non-Socks outbound never matches (fail closed).
     */
    private fun profileMatchesVerified(profile: ProxyEntity?, gateway: String, pairing: HotspotPairing): Boolean {
        val socks = profile?.outbound as? Socks ?: return false
        return HotspotGatewayPolicy.profileMatches(
            server = socks.server,
            serverPort = socks.serverPort,
            username = socks.username,
            password = socks.password,
            uot = socks.uot,
            version = socks.version,
            gateway = gateway,
            port = pairing.port,
            pairingUsername = pairing.user,
            pairingPassword = pairing.pass,
            pairingUot = pairing.uot,
        )
    }

    // ---------------------------------------------------------------------------- loss grace / restore (OnLost)

    /** A gateway or verification is gone: the restore of the previous profile is armed for [LOSS_GRACE_MS]. */
    private fun markLost() {
        cancelTrailingReload()
        if (lossSince != 0L) return
        lossSince = System.currentTimeMillis()
        lossWarned = false
        lossHandler.removeCallbacks(lossRunnable)
        lossHandler.postDelayed(lossRunnable, LOSS_GRACE_MS)
    }

    /** The gateway is verified again (or the feature was re-armed): the pending restore is dropped. */
    private fun cancelLossSession() {
        lossSince = 0L
        lossWarned = false
        lossHandler.removeCallbacks(lossRunnable)
    }

    /** Drops a pending trailing reload (design §7.2). Deliberately separate from [cancelLossSession], which runs on
     *  verification and must not drop a legitimate deferred reload. */
    private fun cancelTrailingReload() {
        trailingReloadJob?.cancel()
        trailingReloadJob = null
    }

    /** The grace expired: restore once, on the default dispatcher (the transaction is disk work). */
    private suspend fun finishLossSession() {
        if (lossSince == 0L) return // cancelled by a verification or a disable in the meantime
        if (!DataStore.hotspotGatewayEnabled) return
        applyRestore(HotspotGatewayProfiles.restorePrevious(DataStore.hotspotGatewayStopOnLoss), publish = true)
        lossSince = 0L
    }

    /**
     * The one place a [RestoreResult] is acted on. Only [RestoreResult.Restored] changes the selection back; a
     * [RestoreResult.Stopped] clears it and stops the service; [RestoreResult.WarningOnly] warns (once per loss
     * session) and changes nothing at all.
     */
    private fun applyRestore(result: RestoreResult, publish: Boolean) {
        when (result) {
            is RestoreResult.Restored -> {
                if (DataStore.serviceState.started) broadcastSwitchProfile(result.previousId)
                if (publish) {
                    HotspotGatewayState.publish(HotspotGatewayStatus.REVERTED, gateway = "", port = 0)
                }
                Logs.d("hotspot gateway restored previous profile ${result.previousId}")
            }

            RestoreResult.Stopped -> {
                SagerNet.stopService()
                if (publish) {
                    HotspotGatewayState.publish(HotspotGatewayStatus.REVERT_FAILED, gateway = "", port = 0)
                }
                Logs.d("hotspot gateway stopped: no previous profile, stopOnLoss is on")
            }

            RestoreResult.WarningOnly -> {
                if (publish) {
                    HotspotGatewayState.publish(HotspotGatewayStatus.REVERT_FAILED, gateway = "", port = 0)
                }
                warnNoPreviousOnce()
            }

            // Nothing to restore (the takeover never happened), or the user chose another profile meanwhile.
            RestoreResult.NothingToRestore, RestoreResult.SelectionPreserved -> Unit
        }
    }

    /** At most one warning per loss session, on the warnings channel. */
    private fun warnNoPreviousOnce() {
        if (lossWarned) return
        lossWarned = true
        PlatformNotifications.hotspotNoPreviousProfile(SagerNet.application)
    }

    /** A running service switches in place (BaseService restarts it): the only switch a takeover may send. */
    private fun broadcastSwitchProfile(profileId: Long) {
        SagerNet.application.sendBroadcast(
            Intent(Action.SWITCH_PROFILE)
                .setPackage(SagerNet.application.packageName)
                .putExtra(Action.EXTRA_PROFILE_ID, profileId),
        )
    }

    private fun registerCallback() {
        if (isRegistered) return
        val cm = SagerNet.connectivity
        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .build()
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                if (!track(network, cm.getNetworkCapabilities(network))) return
                scheduleCheck()
            }

            override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) {
                if (!track(network, networkCapabilities)) return
                scheduleCheck()
            }

            override fun onLost(network: Network) {
                forget(network)
                scheduleCheck()
            }
        }
        try {
            cm.registerNetworkCallback(request, callback)
            networkCallback = callback
            isRegistered = true
        } catch (e: Throwable) {
            Logs.w(e)
        }
    }

    private fun unregisterCallback() {
        if (!isRegistered) return
        isRegistered = false
        debounceHandler.removeCallbacksAndMessages(null)
        networkCallback?.let {
            runCatching { SagerNet.connectivity.unregisterNetworkCallback(it) }
        }
        networkCallback = null
        synchronized(trackedNetworks) { trackedNetworks.clear() }
    }

    /**
     * Records [network] as a candidate and returns whether it may trigger a check: a network carrying VPN transport
     * is dropped from the tracked set and stays silent, it is never a gateway and never starts a probe.
     */
    private fun track(network: Network, capabilities: NetworkCapabilities?): Boolean {
        val vpn = capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true
        synchronized(trackedNetworks) {
            if (vpn) trackedNetworks.remove(network) else trackedNetworks.add(network)
        }
        return !vpn
    }

    private fun forget(network: Network) {
        synchronized(trackedNetworks) { trackedNetworks.remove(network) }
    }

    private fun scheduleCheck() {
        debounceHandler.removeCallbacks(debounceRunnable)
        debounceHandler.postDelayed(debounceRunnable, DEBOUNCE_MS)
    }

    /**
     * The Wi-Fi network to probe and its IPv4 default-route gateway, or null when there is none.
     *
     * The active network is tried first, then the networks the callback tracked; on API 31 and above `allNetworks`
     * is never consulted, on older releases it is only the last-resort fallback. A network with VPN transport is
     * rejected no matter what else it carries, and the returned [Network] is what the probe socket is bound to -
     * no `bind_interface` is written anywhere.
     */
    @Suppress("DEPRECATION") // allNetworks is only read behind the API < 31 guard below.
    fun getWifiGateway(): Pair<Network, String>? {
        val cm = SagerNet.connectivity
        val candidates = LinkedHashSet<Network>()
        cm.activeNetwork?.let { candidates.add(it) }
        synchronized(trackedNetworks) { candidates.addAll(trackedNetworks) }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            candidates.addAll(cm.allNetworks)
        }
        for (network in candidates) {
            val capabilities = cm.getNetworkCapabilities(network) ?: continue
            if (!HotspotGatewayPolicy.isGatewayNetwork(
                    hasWifiTransport = capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI),
                    hasVpnTransport = capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN),
                )
            ) {
                continue
            }
            val gateway = defaultRouteGateway(cm.getLinkProperties(network)) ?: continue
            return network to gateway
        }
        return null
    }

    /** The IPv4 gateway of the default route, the same selection the original per-network scan used. */
    private fun defaultRouteGateway(link: LinkProperties?): String? {
        if (link == null) return null
        for (route in link.routes) {
            val gateway = route.gateway
            if (gateway is Inet4Address && (
                    route.isDefaultRoute ||
                        route.destination?.address is Inet4Address && route.destination?.prefixLength == 0
                    )
            ) {
                return gateway.hostAddress
            }
        }
        return null
    }

    /**
     * Verifies the current gateway and keeps the managed profile in sync.
     *
     * Nothing but a [HotspotGatewayStatus.FOUND] result writes the profile, [DataStore.hotspotGatewayLast] or
     * [DataStore.hotspotGatewayLastPort]; a failed probe only updates the shared status record. When the paired
     * port does not verify, the bounded parallel scan of [HotspotPortDiscovery] looks for the port the gateway
     * actually serves, preferring the paired and last-verified port. With [reload] the running service is reloaded
     * when the managed profile is the one in use; with [allowSwitch] false the takeover still writes the selection
     * but never broadcasts `SWITCH_PROFILE` (the start-time path starts the profile itself).
     */
    suspend fun checkAndUpdate(forceReload: Boolean, reload: Boolean = true, allowSwitch: Boolean = true) = mutex.withLock {
        if (!DataStore.hotspotGatewayEnabled) return@withLock

        val candidate = getWifiGateway()
        if (candidate == null) {
            // Off Wi-Fi: the profile and the last gateway stay exactly as they are; the loss grace starts.
            HotspotGatewayState.publish(HotspotGatewayStatus.NO_GATEWAY, gateway = "", port = 0)
            Logs.w("hotspot gateway no gateway available")
            markLost()
            return@withLock
        }
        // The gateway is reachable again: any armed restore from an earlier loss is dropped right here, and the
        // probe below either confirms it (FOUND) or starts a fresh loss session of its own.
        cancelLossSession()
        val (network, gateway) = candidate
        val pairing = HotspotGatewayState.pairing()
        // A matching gateway is only a previously verified one when the pairing still fingerprints to what was
        // applied: the shared record is republished here (it may still say NO_GATEWAY after a Wi-Fi drop, or NOT_FOUND
        // from an earlier failed probe), but a config change on the same gateway must re-probe and re-apply instead.
        val currentFingerprint = if (pairing != null) {
            HotspotGatewayPolicy.configFingerprint(
                gateway = gateway,
                port = pairing.port,
                username = pairing.user,
                password = pairing.pass,
                uot = pairing.uot,
            )
        } else {
            ""
        }
        val revisit = HotspotGatewayPolicy.revisitStatus(
            currentGateway = gateway,
            lastGateway = DataStore.hotspotGatewayLast,
            lastPort = DataStore.hotspotGatewayLastPort,
            currentFingerprint = currentFingerprint,
            appliedFingerprint = DataStore.hotspotGatewayAppliedFingerprint,
            forceReload = forceReload,
        )
        if (revisit != null) {
            HotspotGatewayState.publish(revisit, gateway = gateway, port = DataStore.hotspotGatewayLastPort)
            return@withLock
        }

        if (pairing == null) {
            HotspotGatewayState.publish(HotspotGatewayStatus.PAIRING_REQUIRED, gateway = gateway, port = 0)
            return@withLock
        }

        HotspotGatewayState.publish(HotspotGatewayStatus.PROBING, gateway = gateway, port = pairing.port)
        val result = withContext(Dispatchers.IO) { probe(network, gateway, pairing.port) }

        // Settings may have moved while the probe ran: the shared record wins over the stale snapshot.
        if (!DataStore.hotspotGatewayEnabled) return@withLock
        val verified = HotspotGatewayState.pairing() ?: return@withLock

        if (HotspotGatewayPolicy.writesProfile(result, verified)) {
            applyVerifiedGateway(gateway, verified.port, verified, reload = reload, allowSwitch = allowSwitch)
            Logs.d("hotspot gateway verified $gateway:${verified.port}")
            HotspotGatewayState.publish(HotspotGatewayStatus.FOUND, gateway = gateway, port = verified.port)
            return@withLock
        }

        if (result is Socks5ProbeResult.Failure) {
            Logs.w("hotspot gateway probe failed $gateway:${verified.port} ${result.reason}")
        }
        val primaryStatus = HotspotGatewayPolicy.statusFor(result, verified)
        HotspotGatewayState.publish(primaryStatus, gateway = gateway, port = verified.port)

        // A paired port that reached a SOCKS5 server (02 without credentials) has a pairing problem, not a port
        // problem: no scan. Otherwise - refused, timed out, not SOCKS5, FF - the bounded parallel scan of design
        // §5.3 looks for the port the gateway actually serves, preferring the paired and last-verified port.
        if (primaryStatus != HotspotGatewayStatus.NOT_FOUND &&
            primaryStatus != HotspotGatewayStatus.UNSUPPORTED_AUTH
        ) {
            // The retries are spent and this pairing cannot be completed: a flap, counted from now.
            markLost()
            return@withLock
        }

        val socks5Probe = Socks5Probe()
        val discovery = withContext(Dispatchers.IO) {
            val preferred = listOfNotNull(
                verified.port.takeIf { HotspotGatewayPolicy.isValidPort(it) },
                DataStore.hotspotGatewayLastPort.takeIf {
                    HotspotGatewayPolicy.isValidPort(it) && it != verified.port
                },
            )
            HotspotPortDiscovery.discover(
                preferredPorts = preferred,
                candidates = HotspotPortDiscovery.CANDIDATE_PORTS,
                probe = { port -> socks5Probe.probe(gateway, port, network.socketFactory) },
            )
        }

        // The scan ran unverified for seconds: enabled and pairing are re-read before anything is written (design:
        // a new pairing generation beats the old async results, and its own queued check re-verifies the rest).
        if (!DataStore.hotspotGatewayEnabled) return@withLock
        val current = HotspotGatewayState.pairing() ?: return@withLock

        when (discovery) {
            is DiscoveryResult.Success -> {
                val reply = Socks5ProbeResult.Reply(discovery.method)
                if (!HotspotGatewayPolicy.writesProfile(reply, current)) {
                    // The port speaks SOCKS5 but the pairing cannot complete it (02 without credentials):
                    // publish and write nothing.
                    HotspotGatewayState.publish(
                        HotspotGatewayPolicy.statusFor(reply, current),
                        gateway = gateway,
                        port = discovery.port,
                    )
                    markLost()
                    return@withLock
                }
                applyVerifiedGateway(gateway, discovery.port, current, reload = reload, allowSwitch = allowSwitch)
                Logs.d("hotspot gateway discovered $gateway:${discovery.port}")
                HotspotGatewayState.publish(HotspotGatewayStatus.FOUND, gateway = gateway, port = discovery.port)
            }

            is DiscoveryResult.Failure -> {
                val status = if (discovery.reason == DiscoveryFailure.UNSUPPORTED_AUTH) {
                    HotspotGatewayStatus.UNSUPPORTED_AUTH
                } else {
                    HotspotGatewayStatus.NOT_FOUND
                }
                Logs.w("hotspot gateway discovery failed $gateway ${discovery.reason}")
                HotspotGatewayState.publish(status, gateway = gateway, port = verified.port)
                // The paired port and the whole candidate list are spent: a flap, counted from now.
                markLost()
            }
        }
    }

    /**
     * The one write path of a verified gateway (design §7.1/§7.2): the dedicated group is ensured and the managed
     * profile updated with [pairing]'s identity at [port], each in one transaction; the last-seen gateway/port are
     * stored, and the selection/reload transition runs through [HotspotGatewayProfiles.selectManaged] and
     * [HotspotGatewayProfiles.claimReload]. Runs on the IO dispatcher (Room transactions block the thread).
     */
    internal suspend fun applyVerifiedGateway(
        gateway: String,
        port: Int,
        pairing: HotspotPairing,
        reload: Boolean,
        allowSwitch: Boolean,
    ) {
        withContext(Dispatchers.IO) {
            // A port found by the scan becomes the persistent pairing port (design §5.3); on the paired-port path
            // [port] already is the pairing port and this write is a no-op.
            if (DataStore.hotspotGatewayPort != port) DataStore.hotspotGatewayPort = port

            val socks = Socks().apply {
                name = HotspotGatewayProfiles.MANAGED_NAME
                server = gateway
                serverPort = port
                username = pairing.user
                password = pairing.pass
                version = 5
                uot = pairing.uot
            }

            // The managed profile lives in the dedicated, fixed-named, non-subscription group. The group is ensured
            // and the profile created-or-updated, each in one transaction. Only the id this manager itself stored is
            // ever updated - a profile that merely shares the name is never adopted.
            val group = HotspotGatewayProfiles.ensureDedicatedGroup()
            val profile = ProfileManager.upsertNamed(
                groupId = group.id,
                preferredProfileId = DataStore.hotspotGatewayProfileId,
                outbound = socks,
            )
            DataStore.hotspotGatewayProfileId = profile.id

            // Only a verified probe reaches these two.
            DataStore.hotspotGatewayLast = gateway
            DataStore.hotspotGatewayLastPort = port

            val managedId = DataStore.hotspotGatewayProfileId
            val fingerprint = HotspotGatewayPolicy.configFingerprint(
                gateway = gateway,
                port = port,
                username = pairing.user,
                password = pairing.pass,
                uot = pairing.uot,
            )
            // The revisit fast-path compares the live pairing against this: only an unchanged config reuses the
            // verified record without a probe.
            DataStore.hotspotGatewayAppliedFingerprint = fingerprint

            // Automatic selection: the previous choice is stored once (never overwritten), then the managed profile
            // is selected. A running service is told through SWITCH_PROFILE only - that restart already carries the
            // new config, so no RELOAD may follow it.
            val selection = HotspotGatewayProfiles.selectManaged(
                managedId = managedId,
                // Auto-switch (SWITCH_PROFILE to a running service) is opt-in: default off.
                serviceRunning = allowSwitch && DataStore.serviceState.started && DataStore.hotspotGatewayAutoSwitch,
            )
            val switched = selection is SelectionResult.Selected && selection.broadcastSwitch
            if (switched) {
                broadcastSwitchProfile(managedId)
                HotspotGatewayProfiles.recordAppliedConfig(fingerprint)
            } else if (reload) {
                // Otherwise a reload happens exclusively through the claim: same fingerprint or inside the 5000 ms
                // floor yields no broadcast at all, and only while the running config uses the managed profile. A
                // change deferred by the floor is applied once, after it, by a single trailing reload.
                when (HotspotGatewayProfiles.claimReload(
                    managedId = managedId,
                    fingerprint = fingerprint,
                    nowMs = System.currentTimeMillis(),
                )) {
                    ReloadDecision.Reload -> SagerNet.reloadService()
                    ReloadDecision.MinInterval -> scheduleTrailingReload(managedId, fingerprint)
                    else -> Unit
                }
            }
        }
    }

    /**
     * The trailing check of design §7.2: a reload deferred by [HotspotGatewayPolicy.MIN_RELOAD_MS] is applied once,
     * after the floor. Each new deferral replaces the pending one, so a burst of checks collapses into one reload of
     * the last config; the claim re-runs at the end (and may lose to a newer one), so no stale reload is broadcast.
     */
    private fun scheduleTrailingReload(managedId: Long, fingerprint: String) {
        trailingReloadJob?.cancel()
        trailingReloadJob = runOnDefaultDispatcher {
            delay(HotspotGatewayPolicy.MIN_RELOAD_MS)
            // The feature may have been disabled or a loss session started while the floor elapsed.
            val enabled = DataStore.hotspotGatewayEnabled
            val inLoss = lossSince != 0L
            if (enabled && !inLoss && HotspotGatewayProfiles.claimReload(managedId, fingerprint, System.currentTimeMillis()) == ReloadDecision.Reload) {
                SagerNet.reloadService()
            }
        }
    }

    /**
     * One anonymous probe, repeated for transient failures until the four-attempt budget of
     * [HotspotGatewayPolicy.retryDelayAfterAttempt] is spent. The socket factory comes from [network], so the probe
     * runs on that network rather than on whatever the process default routes to.
     */
    private suspend fun probe(network: Network, gateway: String, port: Int): Socks5ProbeResult {
        val socketFactory = network.socketFactory
        val socks5Probe = Socks5Probe()
        var attempt = 1
        var result = socks5Probe.probe(gateway, port, socketFactory)
        while (HotspotGatewayPolicy.shouldRetry(result)) {
            val waitMs = HotspotGatewayPolicy.retryDelayAfterAttempt(attempt) ?: break
            delay(waitMs)
            attempt++
            result = socks5Probe.probe(gateway, port, socketFactory)
        }
        return result
    }
}
