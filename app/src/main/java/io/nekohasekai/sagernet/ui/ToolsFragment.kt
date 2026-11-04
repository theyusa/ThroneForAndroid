package io.nekohasekai.sagernet.ui

import android.os.Bundle
import android.view.View
import androidx.fragment.app.Fragment
import androidx.viewpager2.adapter.FragmentStateAdapter
import com.google.android.material.tabs.TabLayoutMediator
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.database.backup.BackupEntry
import io.nekohasekai.sagernet.databinding.LayoutToolsBinding

class ToolsFragment : ToolbarFragment(R.layout.layout_tools) {

    companion object {
        const val ARG_TAB = "initial_tab"
        const val TAB_NETWORK = 0
        const val TAB_BACKUP = 1

        fun newInstance(tab: Int = TAB_NETWORK): ToolsFragment {
            return ToolsFragment().apply {
                arguments = Bundle().apply {
                    putInt(ARG_TAB, tab)
                }
            }
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        toolbar?.setTitle(R.string.menu_tools)

        val pendingBackup = BackupEntry.takePending()
        val tools = listOf(
            NetworkFragment(),
            BackupFragment.newInstance(pendingBackup),
        )

        val binding = LayoutToolsBinding.bind(view)
        binding.toolsPager.adapter = ToolsAdapter(tools)

        TabLayoutMediator(binding.toolsTab, binding.toolsPager) { tab, position ->
            tab.text = when (position) {
                0 -> getString(R.string.tools_tab_network)
                1 -> getString(R.string.tools_tab_backup)
                else -> null
            }
            tab.view.setOnLongClickListener { true }
        }.attach()

        val initialTab = arguments?.getInt(ARG_TAB, -1) ?: -1
        if (pendingBackup != null || initialTab == TAB_BACKUP) {
            binding.toolsPager.setCurrentItem(TAB_BACKUP, false)
        } else if (initialTab in 0 until tools.size) {
            binding.toolsPager.setCurrentItem(initialTab, false)
        }
    }

    inner class ToolsAdapter(private val tools: List<Fragment>) : FragmentStateAdapter(this) {
        override fun getItemCount(): Int = tools.size
        override fun createFragment(position: Int): Fragment = tools[position]
    }
}
