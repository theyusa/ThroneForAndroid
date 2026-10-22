package io.nekohasekai.sagernet.utils

import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Handler
import android.os.Looper
import io.nekohasekai.sagernet.SagerNet
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.database.GroupRepo
import io.nekohasekai.sagernet.database.ProfileManager
import io.nekohasekai.sagernet.ktx.Logs
import io.nekohasekai.sagernet.outbound.types.Socks
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import java.net.Inet4Address

object HotspotGatewayManager {
    private const val PROFILE_NAME = "Hotspot Gateway"
    private val debounceHandler = Handler(Looper.getMainLooper())
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private var isRegistered = false
    var onGatewayChanged: (() -> Unit)? = null

    fun init() {
        if (DataStore.hotspotGatewayEnabled) {
            checkAndUpdate(forceReload = false)
            registerCallback()
        } else {
            unregisterCallback()
        }
    }

    fun onSwitchChanged(enabled: Boolean) {
        if (enabled) {
            checkAndUpdate(forceReload = true)
            registerCallback()
        } else {
            unregisterCallback()
        }
        onGatewayChanged?.invoke()
    }

    fun beforeCoreConnect() {
        if (DataStore.hotspotGatewayEnabled) {
            checkAndUpdate(forceReload = false)
        }
    }

    private fun registerCallback() {
        if (isRegistered) return
        val cm = SagerNet.connectivity
        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .build()
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                scheduleCheck()
            }
            override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) {
                scheduleCheck()
            }
            override fun onLost(network: Network) {
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
    }

    private fun scheduleCheck() {
        debounceHandler.removeCallbacksAndMessages(null)
        debounceHandler.postDelayed({
            checkAndUpdate(forceReload = true)
            onGatewayChanged?.invoke()
        }, 500L)
    }

    fun getGatewayIp(): String? {
        val cm = SagerNet.connectivity
        val networks = cm.allNetworks
        for (net in networks) {
            val caps = cm.getNetworkCapabilities(net)
            if (caps != null && caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
                val link = cm.getLinkProperties(net)
                if (link != null) {
                    for (route in link.routes) {
                        val gateway = route.gateway
                        if (gateway is Inet4Address && (route.isDefaultRoute || route.destination?.address is Inet4Address && route.destination?.prefixLength == 0)) {
                            return gateway.hostAddress
                        }
                    }
                }
            }
        }
        SagerNet.connectivity.activeNetwork?.let { net ->
            val caps = cm.getNetworkCapabilities(net)
            if (caps != null && caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
                val link = cm.getLinkProperties(net)
                if (link != null) {
                    for (route in link.routes) {
                        val gateway = route.gateway
                        if (gateway is Inet4Address && (route.isDefaultRoute || route.destination?.address is Inet4Address && route.destination?.prefixLength == 0)) {
                            return gateway.hostAddress
                        }
                    }
                }
            }
        }
        return null
    }

    fun checkAndUpdate(forceReload: Boolean) {
        if (!DataStore.hotspotGatewayEnabled) return
        val gateway = getGatewayIp()
        val lastGateway = DataStore.hotspotGatewayLast

        if (gateway.isNullOrEmpty()) {
            // No Wi-Fi gateway (not on Wi-Fi): keep last profile unchanged, do not delete anything.
            return
        }

        if (gateway == lastGateway && !forceReload) {
            return
        }

        DataStore.hotspotGatewayLast = gateway

        runBlocking(Dispatchers.IO) {
            val profileId = DataStore.hotspotGatewayProfileId
            var profile = if (profileId > 0L) ProfileManager.getProfile(profileId) else null

            if (profile == null) {
                val groups = GroupRepo.all()
                for (group in groups) {
                    val members = ProfileManager.members(group.id)
                    val found = members.find { it.name == PROFILE_NAME }
                    if (found != null) {
                        profile = found
                        break
                    }
                }
            }

            val socks = Socks().apply {
                name = PROFILE_NAME
                server = gateway
                serverPort = DataStore.hotspotGatewayPort
                username = DataStore.hotspotGatewayUser
                password = DataStore.hotspotGatewayPass
                version = 5
                uot = DataStore.hotspotGatewayUot
            }

            if (profile == null) {
                val newProfile = ProfileManager.addProfile(socks)
                DataStore.hotspotGatewayProfileId = newProfile.id
            } else {
                profile.putOutbound(socks)
                ProfileManager.updateProfile(profile)
                DataStore.hotspotGatewayProfileId = profile.id
            }

            val runningProfileId = ProfileManager.runningProfileId()
            val managedId = DataStore.hotspotGatewayProfileId
            if (DataStore.serviceState.started && (runningProfileId == managedId || ProfileManager.runningUses(managedId))) {
                withContext(Dispatchers.Main) {
                    SagerNet.reloadService()
                }
            }
        }
    }
}
