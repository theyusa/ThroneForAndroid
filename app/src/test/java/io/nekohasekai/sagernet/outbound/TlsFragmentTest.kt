package io.nekohasekai.sagernet.outbound

import io.nekohasekai.sagernet.outbound.common.Tls
import io.nekohasekai.sagernet.outbound.types.Vless
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TlsFragmentTest {

    @Test
    fun defaultTlsEmitsFragmentAndRecordFragment() {
        val vless = Vless().apply {
            server = "185.157.217.124"
            serverPort = 443
            uuid = "a7c60bce-79c0-4442-9369-7a652b36c625"
            tls.enabled = true
            tls.server_name = "cdn.openai.com"
        }

        val ctx = BuildContext.DEFAULT
        assertTrue("Default fragmentDefaultOn should be true", ctx.fragmentDefaultOn)

        val built = vless.build(ctx)
        assertTrue(built.ok)
        val tlsObj = built.json.obj("tls")

        assertTrue("fragment must be true", tlsObj.bool("fragment"))
        assertTrue("record_fragment must be true", tlsObj.bool("record_fragment"))
        assertEquals("20ms", tlsObj.string("fragment_fallback_delay"))
    }

    @Test
    fun explicitFragmentOffSuppressesFragment() {
        val vless = Vless().apply {
            server = "1.2.3.4"
            serverPort = 443
            uuid = "a7c60bce-79c0-4442-9369-7a652b36c625"
            tls.enabled = true
            tls.server_name = "example.com"
            tls.fragment = false
            tls.fragment_unspecified = false
        }

        val ctx = BuildContext.DEFAULT
        val built = vless.build(ctx)
        assertTrue(built.ok)
        val tlsObj = built.json.obj("tls")

        assertFalse(tlsObj.contains("fragment"))
        assertFalse(tlsObj.contains("record_fragment"))
    }

    @Test
    fun explicitFragmentOnEmitsEvenWhenGlobalOff() {
        val vless = Vless().apply {
            server = "1.2.3.4"
            serverPort = 443
            uuid = "a7c60bce-79c0-4442-9369-7a652b36c625"
            tls.enabled = true
            tls.server_name = "example.com"
            tls.fragment = true
            tls.fragment_unspecified = false
            tls.fragment_fallback_delay = "50ms"
        }

        val ctx = BuildContext(fragmentDefaultOn = false)
        val built = vless.build(ctx)
        assertTrue(built.ok)
        val tlsObj = built.json.obj("tls")

        assertTrue(tlsObj.bool("fragment"))
        assertTrue(tlsObj.bool("record_fragment"))
        assertEquals("50ms", tlsObj.string("fragment_fallback_delay"))
    }

    @Test
    fun globalOffSuppressesUnspecifiedFragment() {
        val vless = Vless().apply {
            server = "1.2.3.4"
            serverPort = 443
            uuid = "a7c60bce-79c0-4442-9369-7a652b36c625"
            tls.enabled = true
            tls.server_name = "example.com"
        }

        val ctx = BuildContext(fragmentDefaultOn = false)
        val built = vless.build(ctx)
        assertTrue(built.ok)
        val tlsObj = built.json.obj("tls")

        assertFalse(tlsObj.contains("fragment"))
        assertFalse(tlsObj.contains("record_fragment"))
    }
}
