package io.nekohasekai.sagernet.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.net.ConnectivityManager
import android.os.Bundle
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.widget.Toolbar
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.databinding.ActivityNetworkInterfacesBinding
import io.nekohasekai.sagernet.databinding.ItemNetworkInterfaceBinding
import io.nekohasekai.sagernet.utils.HotspotKind
import io.nekohasekai.sagernet.utils.InterfaceKind
import io.nekohasekai.sagernet.utils.NetworkInterfaceInfo
import io.nekohasekai.sagernet.utils.NetworkInterfacesRepository
import io.nekohasekai.sagernet.utils.NetworkInterfacesSnapshot
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.Job
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.DiffUtil
import android.net.NetworkRequest
import android.net.NetworkCapabilities
import android.net.Network
import android.net.LinkProperties

class NetworkInterfacesActivity : ThemedActivity() {

    private lateinit var binding: ActivityNetworkInterfacesBinding
    private val repository by lazy {
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        NetworkInterfacesRepository(cm)
    }
    private val interfacesAdapter = InterfacesAdapter()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityNetworkInterfacesBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val toolbar = findViewById<Toolbar>(R.id.toolbar)
        setSupportActionBar(toolbar)
        supportActionBar?.apply {
            setTitle(R.string.network_interfaces_title)
            setDisplayHomeAsUpEnabled(true)
            setHomeAsUpIndicator(R.drawable.baseline_arrow_back_24)
        }

        binding.recyclerInterfaces.apply {
            layoutManager = LinearLayoutManager(this@NetworkInterfacesActivity)
            adapter = interfacesAdapter
        }

        // onStart triggers live monitoring and initial refresh

    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.network_interfaces_menu, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            android.R.id.home -> {
                finish()
                true
            }
            R.id.action_refresh -> {
                refresh()
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    private var refreshJob: Job? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    override fun onStart() {
        super.onStart()
        requestRefresh()
        registerLiveMonitoring()
    }

    override fun onStop() {
        super.onStop()
        unregisterLiveMonitoring()
    }

    private fun registerLiveMonitoring() {
        if (networkCallback != null) return
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                requestRefresh(debounceMs = 300)
            }

            override fun onLost(network: Network) {
                requestRefresh(debounceMs = 300)
            }

            override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) {
                requestRefresh(debounceMs = 300)
            }

            override fun onLinkPropertiesChanged(network: Network, linkProperties: LinkProperties) {
                requestRefresh(debounceMs = 300)
            }
        }
        networkCallback = callback
        runCatching {
            val request = NetworkRequest.Builder().build()
            cm.registerNetworkCallback(request, callback)
        }
    }

    private fun unregisterLiveMonitoring() {
        val callback = networkCallback ?: return
        networkCallback = null
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return
        runCatching {
            cm.unregisterNetworkCallback(callback)
        }
    }

    private fun requestRefresh(debounceMs: Long = 0L) {
        binding.progressBarLoading.visibility = View.VISIBLE
        refreshJob?.cancel()
        refreshJob = lifecycleScope.launch {
            if (debounceMs > 0) delay(debounceMs)
            val snapshot = withContext(Dispatchers.IO) {
                repository.collect()
            }
            renderSnapshot(snapshot)
        }
    }

    private fun refresh() {
        requestRefresh(debounceMs = 0L)
    }

    private fun renderSnapshot(snapshot: NetworkInterfacesSnapshot) {
        binding.progressBarLoading.visibility = View.GONE
        binding.cardPartialWarning.visibility = if (snapshot.partial) View.VISIBLE else View.GONE
        binding.textEmpty.visibility = if (snapshot.interfaces.isEmpty()) View.VISIBLE else View.GONE
        interfacesAdapter.submitList(snapshot.interfaces)
    }

    private fun copyToClipboard(label: String, value: String) {
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        cm?.setPrimaryClip(ClipData.newPlainText(label, value))
        Toast.makeText(this, R.string.network_interfaces_copied, Toast.LENGTH_SHORT).show()
    }

    private inner class InterfacesAdapter : ListAdapter<NetworkInterfaceInfo, InterfaceViewHolder>(
        object : DiffUtil.ItemCallback<NetworkInterfaceInfo>() {
            override fun areItemsTheSame(oldItem: NetworkInterfaceInfo, newItem: NetworkInterfaceInfo): Boolean =
                oldItem.name == newItem.name

            override fun areContentsTheSame(oldItem: NetworkInterfaceInfo, newItem: NetworkInterfaceInfo): Boolean =
                oldItem == newItem
        },
    ) {
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): InterfaceViewHolder {
            val itemBinding = ItemNetworkInterfaceBinding.inflate(layoutInflater, parent, false)
            return InterfaceViewHolder(itemBinding)
        }

        override fun onBindViewHolder(holder: InterfaceViewHolder, position: Int) {
            holder.bind(getItem(position))
        }
    }

    private inner class InterfaceViewHolder(
        private val itemBinding: ItemNetworkInterfaceBinding,
    ) : RecyclerView.ViewHolder(itemBinding.root) {

        fun bind(info: NetworkInterfaceInfo) {
            itemBinding.textInterfaceName.text = info.name

            // Kind badge
            itemBinding.badgeKind.text = when (info.kind) {
                InterfaceKind.WIFI -> getString(R.string.network_interfaces_wifi)
                InterfaceKind.CELLULAR -> getString(R.string.network_interfaces_cellular)
                InterfaceKind.ETHERNET -> getString(R.string.network_interfaces_ethernet)
                InterfaceKind.VPN -> getString(R.string.network_interfaces_vpn)
                InterfaceKind.OTHER -> getString(R.string.network_interfaces_other)
            }

            // Up / Down badge
            itemBinding.badgeStatus.text = when (info.up) {
                true -> getString(R.string.network_interfaces_up)
                false -> getString(R.string.network_interfaces_down)
                null -> getString(R.string.network_interfaces_unavailable)
            }

            // Hotspot badge
            when (info.hotspot) {
                HotspotKind.CONFIRMED -> {
                    itemBinding.badgeHotspot.visibility = View.VISIBLE
                    itemBinding.badgeHotspot.text = getString(R.string.network_interfaces_tethered)
                }
                HotspotKind.POSSIBLE -> {
                    itemBinding.badgeHotspot.visibility = View.VISIBLE
                    itemBinding.badgeHotspot.text = getString(R.string.network_interfaces_possible_hotspot)
                }
                HotspotKind.NONE -> {
                    itemBinding.badgeHotspot.visibility = View.GONE
                }
            }

            // Meta line: MTU, MAC, Index, Flags
            val unavailable = getString(R.string.network_interfaces_unavailable)
            val flagsList = buildList {
                if (info.loopback) add(getString(R.string.network_interfaces_loopback))
                if (info.pointToPoint == true) add(getString(R.string.network_interfaces_point_to_point))
                if (info.multicast == true) add(getString(R.string.network_interfaces_multicast))
                if (info.virtual == true) add(getString(R.string.network_interfaces_virtual))
            }
            val flagsStr = flagsList.joinToString(" · ").ifBlank { getString(R.string.network_interfaces_none) }

            val metaParts = mutableListOf<String>()
            info.index?.let { metaParts += "${getString(R.string.network_interfaces_index)}: $it" }
            metaParts += "${getString(R.string.network_interfaces_mtu)}: ${info.mtu ?: unavailable}"
            metaParts += "${getString(R.string.network_interfaces_mac)}: ${info.mac ?: unavailable}"
            metaParts += "${getString(R.string.network_interfaces_flags)}: $flagsStr"
            itemBinding.textInterfaceMeta.text = metaParts.joinToString("  |  ")

            // Addresses
            itemBinding.layoutAddresses.removeAllViews()
            if (info.addresses.isEmpty()) {
                val noneView = TextView(this@NetworkInterfacesActivity).apply {
                    text = unavailable
                    setTextAppearance(androidx.appcompat.R.style.TextAppearance_AppCompat_Body2)
                    setTextColor(getColor(R.color.material_grey_600))
                }
                itemBinding.layoutAddresses.addView(noneView)
            } else {
                val inflater = LayoutInflater.from(this@NetworkInterfacesActivity)
                info.addresses.forEach { ip ->
                    val addrLine = buildString {
                        append(ip.address)
                        ip.prefixLength?.let { append("/$it") }
                        ip.netmask?.let { append(" (netmask: $it)") }
                        ip.broadcast?.let { append(" [bcast: $it]") }
                    }

                    val rowView = TextView(this@NetworkInterfacesActivity).apply {
                        text = addrLine
                        setTextAppearance(androidx.appcompat.R.style.TextAppearance_AppCompat_Body2)
                        setPadding(0, 4, 0, 4)
                        isClickable = true
                        isFocusable = true
                        setOnLongClickListener {
                            copyToClipboard(ip.address, ip.address)
                            true
                        }
                    }
                    itemBinding.layoutAddresses.addView(rowView)
                }
            }

            // Routes
            if (info.routes.isNotEmpty()) {
                itemBinding.layoutRoutesSection.visibility = View.VISIBLE
                itemBinding.textRoutes.text = info.routes.joinToString("\n") { route ->
                    val dest = route.destination.let {
                        if (it.prefixLength != null) "${it.address}/${it.prefixLength}" else it.address
                    }
                    if (route.gateway != null) "$dest  ➔  ${route.gateway}" else dest
                }
            } else {
                itemBinding.layoutRoutesSection.visibility = View.GONE
            }

            // DNS
            if (info.dns.isNotEmpty()) {
                itemBinding.layoutDnsSection.visibility = View.VISIBLE
                itemBinding.textDns.text = info.dns.joinToString(", ")
            } else {
                itemBinding.layoutDnsSection.visibility = View.GONE
            }
        }
    }
}
