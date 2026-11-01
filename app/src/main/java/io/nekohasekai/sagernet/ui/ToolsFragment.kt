package io.nekohasekai.sagernet.ui

import android.content.Intent
import android.os.Bundle
import android.view.View
import com.google.android.material.tabs.TabLayout
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.database.backup.BackupEntry

class ToolsFragment : ToolbarFragment(R.layout.layout_tools) {

    private var selectedTabIndex: Int = 0

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        toolbar?.setTitle(R.string.menu_tools)

        val networkTabContent = view.findViewById<View>(R.id.tools_tab_network)
        val backupTabContent = view.findViewById<View>(R.id.tools_content)
        val tabLayout = view.findViewById<TabLayout>(R.id.tools_tab_layout)

        view.findViewById<View>(R.id.lan_sharing_card)?.setOnClickListener {
            startActivity(Intent(requireContext(), LanSharingActivity::class.java))
        }

        val pendingBackup = BackupEntry.takePending()
        if (childFragmentManager.findFragmentById(R.id.tools_content) == null) {
            childFragmentManager.beginTransaction()
                .replace(R.id.tools_content, BackupFragment.newInstance(pendingBackup))
                .commitAllowingStateLoss()
        }

        fun showTab(index: Int) {
            selectedTabIndex = index
            if (index == 1) {
                networkTabContent?.visibility = View.GONE
                backupTabContent?.visibility = View.VISIBLE
            } else {
                networkTabContent?.visibility = View.VISIBLE
                backupTabContent?.visibility = View.GONE
            }
        }

        val initialTab = when {
            pendingBackup != null -> 1
            savedInstanceState != null -> savedInstanceState.getInt(KEY_SELECTED_TAB, 0)
            else -> 0
        }

        tabLayout?.getTabAt(initialTab)?.select()
        showTab(initialTab)

        tabLayout?.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab) {
                showTab(tab.position)
            }

            override fun onTabUnselected(tab: TabLayout.Tab) = Unit
            override fun onTabReselected(tab: TabLayout.Tab) = Unit
        })
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt(KEY_SELECTED_TAB, selectedTabIndex)
    }

    companion object {
        private const val KEY_SELECTED_TAB = "tools_selected_tab"
    }

}
