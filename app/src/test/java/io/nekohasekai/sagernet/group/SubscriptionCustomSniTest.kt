package io.nekohasekai.sagernet.group

import io.nekohasekai.sagernet.database.SubscriptionOptions
import io.nekohasekai.sagernet.outbound.types.Trojan
import org.junit.Assert.assertEquals
import org.junit.Test

class SubscriptionCustomSniTest {

    @Test
    fun serializationPreservesCustomSni() {
        val options = SubscriptionOptions(
            customSni = "my.custom.sni.example.com",
            nameInclude = "VIP",
        )
        val json = options.toJson()
        assertEquals("my.custom.sni.example.com", json.string("custom_sni"))

        val restored = SubscriptionOptions.fromJson(json)
        assertEquals("my.custom.sni.example.com", restored.customSni)
        assertEquals("VIP", restored.nameInclude)
    }

    @Test
    fun applyCustomSniOverridesTlsServerName() {
        val trojan = Trojan().apply {
            server = "original.server.com"
            serverPort = 443
            tls.enabled = true
            tls.server_name = "original.sni.com"
        }

        SubscriptionRefresh.applyCustomSni(listOf(trojan), "bypass.zerorating.net")
        assertEquals("bypass.zerorating.net", trojan.tls.server_name)
    }

    @Test
    fun applyCustomSniIgnoresBlank() {
        val trojan = Trojan().apply {
            tls.enabled = true
            tls.server_name = "original.sni.com"
        }

        SubscriptionRefresh.applyCustomSni(listOf(trojan), "")
        assertEquals("original.sni.com", trojan.tls.server_name)

        SubscriptionRefresh.applyCustomSni(listOf(trojan), "   ")
        assertEquals("original.sni.com", trojan.tls.server_name)
    }
}
