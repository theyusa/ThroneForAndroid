package io.nekohasekai.sagernet.ui

import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import android.text.format.Formatter
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.CheckBox
import android.widget.EditText
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.widget.Toolbar
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import io.nekohasekai.sagernet.Action
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.aidl.ISagerNetService
import io.nekohasekai.sagernet.bg.BaseService
import io.nekohasekai.sagernet.bg.SagerConnection
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.database.SettingsRegistry
import io.nekohasekai.sagernet.databinding.ActivityLanSharingBinding
import io.nekohasekai.sagernet.ktx.getColorAttr
import io.nekohasekai.sagernet.ktx.needReload
import io.nekohasekai.sagernet.ktx.runOnDefaultDispatcher
import io.nekohasekai.sagernet.utils.HotspotGatewayManager
import io.nekohasekai.sagernet.utils.HotspotGatewaySnapshot
import io.nekohasekai.sagernet.utils.HotspotGatewayState
import io.nekohasekai.sagernet.utils.HotspotGatewayStatus
import io.nekohasekai.sagernet.utils.LanClientDevice
import io.nekohasekai.sagernet.utils.LanSharingLogics
import io.nekohasekai.sagernet.utils.LanSharingNetwork
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class LanSharingActivity : ThemedActivity(), SagerConnection.Callback {

    private lateinit var binding: ActivityLanSharingBinding
    private val connection = SagerConnection(SagerConnection.CONNECTION_ID_MAIN_ACTIVITY_BACKGROUND)
    private val clientHistory = ConcurrentHashMap<String, LanClientDevice>()
    private var statusReceiver: BroadcastReceiver? = null
    private var suppressShareSwitchCallback = false
    private var suppressGatewaySwitchCallback = false

    private var detectedHotspotIp: String? = null
    private var detectedWifiIp: String? = null
    private var hotspotActive: Boolean = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityLanSharingBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val toolbar = findViewById<Toolbar>(R.id.toolbar)
        setSupportActionBar(toolbar)
        supportActionBar?.apply {
            setTitle(R.string.lan_sharing)
            setDisplayHomeAsUpEnabled(true)
            setHomeAsUpIndicator(R.drawable.ic_navigation_close)
        }

        setupViews()
        registerStatusUpdates()
        connection.connect(this, this)
        refreshNetworkAndState()
        startPollingLoop()
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.lan_sharing_menu, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            android.R.id.home -> {
                finish()
                true
            }
            R.id.action_refresh -> {
                refreshNetworkAndState()
                lifecycleScope.launch { pollClientDevicesOnce() }
                true
            }
            R.id.action_config -> {
                showHotspotDialog { saved ->
                    if (saved) {
                        refreshNetworkAndState()
                        if (DataStore.hotspotGatewayEnabled) {
                            runOnDefaultDispatcher {
                                HotspotGatewayManager.checkAndUpdate(forceReload = true)
                            }
                        }
                    }
                }
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    override fun stateChanged(state: BaseService.State, profileName: String?, msg: String?) {
        refreshNetworkAndState()
        lifecycleScope.launch { pollClientDevicesOnce() }
    }

    override fun onServiceConnected(service: ISagerNetService) {
        refreshNetworkAndState()
        lifecycleScope.launch { pollClientDevicesOnce() }
    }

    private fun setupViews() {
        setShareSwitchCheckedSilent(DataStore.allowLanAccess)
        setGatewaySwitchCheckedSilent(DataStore.hotspotGatewayEnabled)

        // Share (X): the switch is the real LAN inbound switch; turning it writes inbound_address to the LAN
        // or loopback address and reloads the service (InboundSettingsFragment allowLan listener,
        // NetworkSettingsScreens.kt:74-78).
        binding.switchLanSharing.setOnCheckedChangeListener { _, isChecked ->
            if (suppressShareSwitchCallback) return@setOnCheckedChangeListener
            DataStore.inboundAddress =
                if (isChecked) SettingsRegistry.LAN_ADDRESS else SettingsRegistry.LOOPBACK_ADDRESS
            if (isChecked) {
                // The live client list reads the Clash API /connections endpoint: make sure it is on after the reload.
                val clashDecision = LanSharingLogics.ensureClashApiEnabledPort(DataStore.coreBoxClashApi)
                if (clashDecision.changed) {
                    DataStore.coreBoxClashApi = clashDecision.newRawPort
                }
            }
            needReload()
            refreshNetworkAndState()
        }

        binding.rowSwitchLanSharing.setOnClickListener {
            binding.switchLanSharing.isChecked = !DataStore.allowLanAccess
        }

        // Auto connect (Y): the Wi-Fi gateway auto-connect feature, its dialog and its status.
        binding.switchGatewaySwitch.setOnCheckedChangeListener { _, isChecked ->
            if (suppressGatewaySwitchCallback) return@setOnCheckedChangeListener
            if (isChecked) {
                showHotspotDialog { saved ->
                    if (saved) {
                        DataStore.hotspotGatewayEnabled = true
                        setGatewaySwitchCheckedSilent(true)
                        HotspotGatewayManager.onSwitchChanged(true)
                    } else {
                        DataStore.hotspotGatewayEnabled = false
                        setGatewaySwitchCheckedSilent(false)
                    }
                    refreshNetworkAndState()
                }
            } else {
                DataStore.hotspotGatewayEnabled = false
                HotspotGatewayManager.onSwitchChanged(false)
                refreshNetworkAndState()
            }
        }

        binding.rowGatewaySwitch.setOnClickListener {
            binding.switchGatewaySwitch.isChecked = !DataStore.hotspotGatewayEnabled
        }
        binding.btnGatewayConfigure.setOnClickListener {
            showHotspotDialog { saved ->
                if (saved) {
                    if (DataStore.hotspotGatewayEnabled) {
                        runOnDefaultDispatcher {
                            HotspotGatewayManager.checkAndUpdate(forceReload = true)
                        }
                    }
                    refreshNetworkAndState()
                }
            }
        }

        binding.btnCopyHotspotIp.setOnClickListener {
            val ip = currentHotspotHostIp()
            copyToClipboard(ip, getString(R.string.lan_sharing_copied_ip, ip))
        }
        binding.btnCopyHotspotPort.setOnClickListener {
            val port = currentConfiguredPort()
            copyToClipboard(port.toString(), getString(R.string.lan_sharing_copied_port, port))
        }
        binding.btnCopyHotspotLink.setOnClickListener {
            val link = buildSocksLinkForHost(currentHotspotHostIp())
            copyToClipboard(link, getString(R.string.lan_sharing_copied_link))
        }
        binding.btnShareHotspotLink.setOnClickListener {
            shareSocksLink(buildSocksLinkForHost(currentHotspotHostIp()))
        }

        binding.btnCopyWifiIp.setOnClickListener {
            val ip = detectedWifiIp
            if (!ip.isNullOrBlank()) {
                copyToClipboard(ip, getString(R.string.lan_sharing_copied_ip, ip))
            }
        }
        binding.btnCopyWifiPort.setOnClickListener {
            val port = currentConfiguredPort()
            copyToClipboard(port.toString(), getString(R.string.lan_sharing_copied_port, port))
        }
        binding.btnCopyWifiLink.setOnClickListener {
            val ip = detectedWifiIp
            if (!ip.isNullOrBlank()) {
                val link = buildSocksLinkForHost(ip)
                copyToClipboard(link, getString(R.string.lan_sharing_copied_link))
            }
        }
        binding.btnShareWifiLink.setOnClickListener {
            val ip = detectedWifiIp
            if (!ip.isNullOrBlank()) {
                shareSocksLink(buildSocksLinkForHost(ip))
            }
        }

        binding.btnRefreshDevices.setOnClickListener {
            refreshNetworkAndState()
            lifecycleScope.launch { pollClientDevicesOnce() }
        }
    }

    private fun setShareSwitchCheckedSilent(checked: Boolean) {
        suppressShareSwitchCallback = true
        binding.switchLanSharing.isChecked = checked
        suppressShareSwitchCallback = false
    }

    private fun setGatewaySwitchCheckedSilent(checked: Boolean) {
        suppressGatewaySwitchCallback = true
        binding.switchGatewaySwitch.isChecked = checked
        suppressGatewaySwitchCallback = false
    }

    private fun registerStatusUpdates() {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                renderUiState()
            }
        }
        runCatching {
            ContextCompat.registerReceiver(
                this,
                receiver,
                IntentFilter(Action.HOTSPOT_GATEWAY_STATUS),
                ContextCompat.RECEIVER_NOT_EXPORTED,
            )
        }.onSuccess { statusReceiver = receiver }

        HotspotGatewayManager.onGatewayChanged = {
            runOnUiThread { renderUiState() }
        }
    }

    private fun refreshNetworkAndState() {
        lifecycleScope.launch {
            val net = withContext(Dispatchers.IO) {
                Triple(
                    LanSharingNetwork.isHotspotActive(this@LanSharingActivity),
                    LanSharingNetwork.findHotspotIp(this@LanSharingActivity),
                    LanSharingNetwork.findWifiIp(this@LanSharingActivity),
                )
            }
            hotspotActive = net.first
            detectedHotspotIp = net.second
            detectedWifiIp = net.third
            renderUiState()
        }
    }

    /** The Share (X) inbound port: the mixed inbound's SOCKS port. */
    private fun currentConfiguredPort(): Int = DataStore.inboundSocksPort

    private fun renderUiState() {
        val shareOn = DataStore.allowLanAccess
        val enabled = DataStore.hotspotGatewayEnabled
        setShareSwitchCheckedSilent(shareOn)
        setGatewaySwitchCheckedSilent(enabled)

        val snapshot = HotspotGatewayState.snapshot()
        binding.textSwitchSummary.text =
            getString(if (shareOn) R.string.lan_sharing_x_on else R.string.lan_sharing_x_off)
        // The Hotspot Gateway status line belongs to the Auto Connect (Y) card only; it never
        // bleeds into the Share (X) section's texts.
        binding.textGatewaySummary.text = statusSummaryText(enabled, snapshot)

        val primaryColor = getColorAttr(R.attr.primaryOrTextPrimary)
        val secondaryColor = getColorAttr(android.R.attr.textColorSecondary)

        val port = currentConfiguredPort()
        val hasAuth = DataStore.inboundAuth
        val authShort = if (hasAuth) {
            getString(R.string.lan_sharing_auth_enabled, DataStore.inboundUser.ifBlank { "***" })
        } else {
            getString(R.string.lan_sharing_auth_none)
        }

        if (shareOn) {
            binding.textHeroTitle.text = getString(R.string.lan_sharing_active)
            binding.badgeServiceStatus.text = getString(R.string.lan_sharing_status_running)
            binding.badgeServiceStatus.setTextColor(primaryColor)
            binding.imgHeroStatus.setColorFilter(primaryColor)
        } else {
            binding.textHeroTitle.text = getString(R.string.lan_sharing_inactive)
            binding.badgeServiceStatus.text = getString(R.string.lan_sharing_status_stopped)
            binding.badgeServiceStatus.setTextColor(secondaryColor)
            binding.imgHeroStatus.setColorFilter(secondaryColor)
        }
        binding.textHeroSubtitle.text = getString(R.string.lan_sharing_status_sub, port, authShort)
        // X warns when the shared inbound carries no RFC1929 credential.
        binding.textShareWarn.visibility = if (shareOn && !hasAuth) View.VISIBLE else View.GONE
        binding.textAuthStatus.text = authShort

        // Hotspot card
        val gatewayOrHotspotIp = snapshot.gateway.takeIf { it.isNotBlank() } ?: detectedHotspotIp
        val hotspotIp = gatewayOrHotspotIp ?: "192.168.43.1"
        binding.textHotspotIp.text = hotspotIp
        binding.textHotspotPort.text = port.toString()
        binding.textHotspotSocksLink.text = buildSocksLinkForHost(hotspotIp)
        if (hotspotActive || !gatewayOrHotspotIp.isNullOrBlank()) {
            binding.badgeHotspotStatus.text = getString(R.string.lan_sharing_hotspot_detected)
            binding.badgeHotspotStatus.setTextColor(primaryColor)
            binding.textHotspotDesc.text = getString(R.string.lan_sharing_hotspot_desc)
        } else {
            binding.badgeHotspotStatus.text = getString(R.string.lan_sharing_hotspot_not_active)
            binding.badgeHotspotStatus.setTextColor(secondaryColor)
            binding.textHotspotDesc.text = getString(R.string.lan_sharing_hotspot_hint_off)
        }

        // Wi-Fi card
        val wifiIp = detectedWifiIp
        binding.textWifiPort.text = port.toString()
        if (!wifiIp.isNullOrBlank()) {
            binding.textWifiIp.text = wifiIp
            binding.textWifiSocksLink.text = buildSocksLinkForHost(wifiIp)
            binding.badgeWifiStatus.text = getString(R.string.lan_sharing_wifi_detected)
            binding.badgeWifiStatus.setTextColor(primaryColor)
            binding.textWifiDesc.text = getString(R.string.lan_sharing_wifi_desc)
            binding.btnCopyWifiIp.isEnabled = true
            binding.btnCopyWifiLink.isEnabled = true
            binding.btnShareWifiLink.isEnabled = true
        } else {
            binding.textWifiIp.text = getString(R.string.lan_sharing_not_detected)
            binding.textWifiSocksLink.text = buildSocksLinkForHost("0.0.0.0")
            binding.badgeWifiStatus.text = getString(R.string.lan_sharing_wifi_not_connected)
            binding.badgeWifiStatus.setTextColor(secondaryColor)
            binding.textWifiDesc.text = getString(R.string.lan_sharing_wifi_hint_off)
            binding.btnCopyWifiIp.isEnabled = false
            binding.btnCopyWifiLink.isEnabled = false
            binding.btnShareWifiLink.isEnabled = false
        }

        // Auth row: display only. The X credential is configured in Settings › Basic › Inbound,
        // not through the Hotspot Gateway dialog.
        binding.btnAuthAction.visibility = View.GONE
    }

    private fun statusSummaryText(enabled: Boolean, snapshot: HotspotGatewaySnapshot): String {
        val baseSum = getString(R.string.hotspot_gateway_sum)
        val kind = LanSharingLogics.statusSummaryKind(enabled, snapshot)
        if (kind == "DISABLED") return baseSum
        val statusText = when (snapshot.status) {
            HotspotGatewayStatus.NO_GATEWAY -> getString(R.string.hotspot_gateway_status_no_gateway)
            HotspotGatewayStatus.PROBING -> getString(R.string.hotspot_gateway_status_probing)
            HotspotGatewayStatus.FOUND ->
                getString(R.string.hotspot_gateway_status_found, snapshot.gateway, snapshot.port)
            HotspotGatewayStatus.NOT_FOUND -> getString(R.string.hotspot_gateway_status_not_found)
            HotspotGatewayStatus.PAIRING_REQUIRED -> getString(R.string.hotspot_gateway_status_pairing_required)
            HotspotGatewayStatus.AUTH_REQUIRED -> getString(R.string.hotspot_gateway_status_auth_required)
            HotspotGatewayStatus.UNSUPPORTED_AUTH -> getString(R.string.hotspot_gateway_status_unsupported_auth)
            else -> baseSum
        }
        return if (statusText == baseSum) baseSum else "$baseSum — $statusText"
    }

    private fun showHotspotDialog(onSave: (Boolean) -> Unit) {
        val dialogView = layoutInflater.inflate(R.layout.layout_hotspot_gateway_dialog, null)
        val editPort = dialogView.findViewById<EditText>(R.id.edit_port)
        val editUser = dialogView.findViewById<EditText>(R.id.edit_user)
        val editPass = dialogView.findViewById<EditText>(R.id.edit_pass)
        val checkUot = dialogView.findViewById<CheckBox>(R.id.check_uot)
        val checkStopOnLoss = dialogView.findViewById<CheckBox>(R.id.check_stop_on_loss)
        val checkAutoSwitch = dialogView.findViewById<CheckBox>(R.id.check_auto_switch)

        editPort.setText(DataStore.hotspotGatewayPort.toString())
        editUser.setText(DataStore.hotspotGatewayUser)
        editPass.setText(DataStore.hotspotGatewayPass)
        checkUot.isChecked = DataStore.hotspotGatewayUot
        checkStopOnLoss.isChecked = DataStore.hotspotGatewayStopOnLoss
        checkAutoSwitch.isChecked = DataStore.hotspotGatewayAutoSwitch

        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.hotspot_gateway)
            .setView(dialogView)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val portStr = editPort.text.toString().trim()
                val port = LanSharingLogics.validateDialogPort(portStr)
                if (port == null) {
                    Toast.makeText(this, getString(R.string.invalid_port, portStr), Toast.LENGTH_SHORT).show()
                    onSave(false)
                    return@setPositiveButton
                }
                DataStore.hotspotGatewayPort = port
                DataStore.hotspotGatewayUser = editUser.text.toString().trim()
                // Never trim the password: a leading or trailing space may be part of the credential.
                DataStore.hotspotGatewayPass = editPass.text.toString()
                DataStore.hotspotGatewayUot = checkUot.isChecked
                DataStore.hotspotGatewayStopOnLoss = checkStopOnLoss.isChecked
                DataStore.hotspotGatewayAutoSwitch = checkAutoSwitch.isChecked
                onSave(true)
            }
            .setNegativeButton(android.R.string.cancel) { _, _ ->
                onSave(false)
            }
            .setOnCancelListener {
                onSave(false)
            }
            .show()
    }

    private fun startPollingLoop() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.RESUMED) {
                while (isActive) {
                    pollClientDevicesOnce()
                    delay(2000L)
                }
            }
        }
    }

    private suspend fun pollClientDevicesOnce() {
        val now = System.currentTimeMillis()
        val (merged, clashReachable) = withContext(Dispatchers.IO) {
            val arp = LanSharingNetwork.readArp()
            val body = LanSharingNetwork.fetchConnections(
                url = LanSharingNetwork.clashUrl(),
                secret = DataStore.coreBoxClashApiSecret,
            )
            val clashList = if (body != null) LanSharingLogics.parseConnections(body) else emptyList()
            val hotspotIp = detectedHotspotIp ?: HotspotGatewayState.snapshot().gateway.takeIf { it.isNotBlank() }
            val result = LanSharingLogics.mergeDevices(
                history = clientHistory.toMap(),
                arpIps = arp,
                clash = clashList,
                hotspotIp = hotspotIp,
                nowMs = now,
            )
            clientHistory.clear()
            result.forEach { clientHistory[it.ip] = it }
            result to (body != null)
        }
        renderDeviceList(merged, clashReachable)
    }

    private fun renderDeviceList(devices: List<LanClientDevice>, clashReachable: Boolean) {
        val activeCount = devices.count { it.activeConnections > 0 }
        binding.badgeDevicesCount.text = getString(R.string.lan_sharing_devices_count, activeCount.coerceAtLeast(devices.size))
        binding.textDevicesCount.text = getString(R.string.lan_sharing_devices_count, devices.size)

        binding.layoutDevicesList.removeAllViews()
        if (devices.isEmpty()) {
            binding.layoutNoDevices.visibility = View.VISIBLE
            binding.textNoDevicesHint.text = if (!DataStore.serviceState.connected || !clashReachable) {
                getString(R.string.lan_sharing_not_connected)
            } else {
                getString(R.string.lan_sharing_no_devices_hint)
            }
            return
        }

        binding.layoutNoDevices.visibility = View.GONE
        for (device in devices) {
            val row = layoutInflater.inflate(R.layout.item_lan_client_device, binding.layoutDevicesList, false)
            val ivIcon = row.findViewById<ImageView>(R.id.iv_device_icon)
            val dotStatus = row.findViewById<View>(R.id.dot_device_status)
            val textIp = row.findViewById<TextView>(R.id.text_device_ip)
            val badgeType = row.findViewById<TextView>(R.id.badge_device_type)
            val textDetail = row.findViewById<TextView>(R.id.text_device_status_detail)
            val textTraffic = row.findViewById<TextView>(R.id.text_device_traffic)

            textIp.text = device.ip
            badgeType.text = getString(
                if (device.isHotspot) R.string.lan_sharing_device_hotspot else R.string.lan_sharing_device_wifi
            )
            ivIcon.setImageResource(
                if (device.isHotspot) R.drawable.ic_communication_phonelink_ring else R.drawable.ic_hardware_router
            )
            dotStatus.setBackgroundResource(
                if (device.activeConnections > 0) R.drawable.bg_status_dot_green else R.drawable.bg_badge_soft
            )

            val statusLabel = if (device.activeConnections > 0) {
                getString(R.string.lan_sharing_device_active, device.activeConnections)
            } else {
                getString(R.string.lan_sharing_device_idle)
            }
            val hostPart = device.lastHost?.takeIf { it.isNotBlank() }?.let {
                " · ${getString(R.string.lan_sharing_device_recent, it)}"
            }.orEmpty()
            textDetail.text = "$statusLabel$hostPart"

            val upStr = Formatter.formatFileSize(this, device.uploadBytes)
            val downStr = Formatter.formatFileSize(this, device.downloadBytes)
            textTraffic.text = "▲ $upStr\n▼ $downStr"

            row.setOnClickListener {
                copyToClipboard(device.ip, getString(R.string.lan_sharing_copied_ip, device.ip))
            }
            binding.layoutDevicesList.addView(row)
        }
    }

    private fun currentHotspotHostIp(): String {
        val snap = HotspotGatewayState.snapshot()
        return snap.gateway.takeIf { it.isNotBlank() } ?: detectedHotspotIp ?: "192.168.43.1"
    }

    private fun buildSocksLinkForHost(host: String): String {
        // Share (X) links mirror the real inbound: its port and, when the inbound auth is on, its
        // username/password (no UoT parameter on the mixed inbound).
        return LanSharingLogics.buildSocks5ShareLink(
            host = host,
            port = DataStore.inboundSocksPort,
            username = if (DataStore.inboundAuth) DataStore.inboundUser else "",
            password = if (DataStore.inboundAuth) DataStore.inboundPass else "",
            uot = false,
            remark = getString(R.string.lan_sharing),
        )
    }

    private fun shareSocksLink(link: String) {
        if (link.isBlank()) return
        val sendIntent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, link)
        }
        startActivity(Intent.createChooser(sendIntent, getString(R.string.lan_sharing_share_link_btn)))
    }

    private fun copyToClipboard(text: String, message: String) {
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        cm?.setPrimaryClip(ClipData.newPlainText("LAN Sharing", text))
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    override fun onDestroy() {
        statusReceiver?.let { runCatching { unregisterReceiver(it) } }
        statusReceiver = null
        HotspotGatewayManager.onGatewayChanged = null
        connection.disconnect(this)
        super.onDestroy()
    }
}
