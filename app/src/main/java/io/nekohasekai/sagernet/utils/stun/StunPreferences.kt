// Based on ccding/go-stun (Apache-2.0) as carried by NekoBoxPlus
package io.nekohasekai.sagernet.utils.stun

import android.content.Context
import io.nekohasekai.sagernet.SagerNet

object StunPreferences {
    private const val PREFS_NAME = "stun_preferences"
    private const val KEY_PRESET = "preset"
    private const val KEY_CUSTOM_SERVERS = "custom_servers"
    private const val KEY_PHYSICAL_NETWORK = "test_physical_network"

    private val prefs by lazy {
        SagerNet.application.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    var preset: StunPreset
        get() = StunPreset.fromValue(prefs.getString(KEY_PRESET, StunPreset.BALANCED.value) ?: StunPreset.BALANCED.value)
        set(value) = prefs.edit().putString(KEY_PRESET, value.value).apply()

    var customServers: String
        get() = prefs.getString(KEY_CUSTOM_SERVERS, "") ?: ""
        set(value) = prefs.edit().putString(KEY_CUSTOM_SERVERS, value).apply()

    var testPhysicalNetwork: Boolean
        get() = prefs.getBoolean(KEY_PHYSICAL_NETWORK, false)
        set(value) = prefs.edit().putBoolean(KEY_PHYSICAL_NETWORK, value).apply()
}
