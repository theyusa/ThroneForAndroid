package io.nekohasekai.sagernet.ui

import android.content.Intent
import android.os.Bundle
import android.view.View
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.fragment.app.Fragment
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.databinding.LayoutNetworkBinding
import io.nekohasekai.sagernet.ktx.dp2px

class NetworkFragment : Fragment(R.layout.layout_network) {

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val binding = LayoutNetworkBinding.bind(view)
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { v, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            v.updatePadding(bottom = bars.bottom + dp2px(64))
            insets
        }

        binding.lanSharingCard.setOnClickListener {
            val ctx = context ?: return@setOnClickListener
            startActivity(Intent(ctx, LanSharingActivity::class.java))
        }

        binding.dnsLeakCard.setOnClickListener {
            val ctx = context ?: return@setOnClickListener
            startActivity(Intent(ctx, DnsLeakActivity::class.java))
        }

        binding.natDiscoveryCard.setOnClickListener {
            val ctx = context ?: return@setOnClickListener
            startActivity(Intent(ctx, StunActivity::class.java))
        }

        binding.cellularNetworkCard.setOnClickListener {
            val ctx = context ?: return@setOnClickListener
            startActivity(Intent(ctx, CellularNetworkActivity::class.java))
        }

        binding.networkInterfacesCard.setOnClickListener {
            val ctx = context ?: return@setOnClickListener
            startActivity(Intent(ctx, NetworkInterfacesActivity::class.java))
        }
    }
}
