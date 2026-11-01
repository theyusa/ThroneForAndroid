package io.nekohasekai.sagernet.ui

import android.content.Intent
import android.os.Bundle
import android.view.View
import androidx.core.view.isVisible
import com.google.android.material.tabs.TabLayout
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.database.backup.BackupEntry

class ToolsFragment : ToolbarFragment(R.layout.layout_tools) {

    private var selectedTabIndex: Int = 0

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        toolbar?.setTitle(R.string.menu_tools)

        view.findViewById<View>(R.id.lan_sharing_card)?.setOnClickListener {
            startActivity(Intent(requireContext(), LanSharingActivity::class.java))
        }

        val pendingBackup = BackupEntry.takePending()
        if (childFragmentManager.findFragmentById(R.id.tools_content) == null) {
            childFragmentManager.beginTransaction()
                .replace(R.id.tools_content, BackupFragment.newInstance(pendingBackup))
                .commitAllowingStateLoss()
        }

        val tabLayout = view.findViewById<TabLayout>(R.id.tools_tab_layout)
        val networkContainer = view.findViewById<View>(R.id.tools_tab_network)
        val backupContainer = view.findViewById<View>(R.id.tools_content)

        if (tabLayout != null && tabLayout.tabCount == 0) {
            tabLayout.addTab(tabLayout.newTab().setText(R.string.tools_tab_network))
            tabLayout.addTab(tabLayout.newTab().setText(R.string.tools_tab_backup))
        }

        fun selectTab(index: Int) {
            selectedTabIndex = index.coerceIn(0, 1)
            networkContainer?.isVisible = selectedTabIndex == 0
            backupContainer?.isVisible = selectedTabIndex == 1
            if (tabLayout != null && tabLayout.selectedTabPosition != selectedTabIndex) {
                tabLayout.getTabAt(selectedTabIndex)?.select()
            }
        }

        val initialTab = when {
            pendingBackup != null -> 1
            savedInstanceState != null -> savedInstanceState.getInt(KEY_SELECTED_TAB, 0)
            else -> 0
        }
        selectTab(initialTab)

        tabLayout?.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab) {
                selectTab(tab.position)
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
