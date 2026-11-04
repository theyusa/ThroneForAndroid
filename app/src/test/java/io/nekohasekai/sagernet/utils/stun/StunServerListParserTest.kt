// Based on ccding/go-stun (Apache-2.0) as carried by NekoBoxPlus
package io.nekohasekai.sagernet.utils.stun

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StunServerListParserTest {

    @Test
    fun customServersAreTrimmedAndDeduplicated() {
        val parsed = StunServerListParser.parse(
            """
            stun.example.com:3478

              STUN.EXAMPLE.COM:3478
            [2001:db8::1]:3478
            """.trimIndent(),
        )

        assertTrue(parsed is StunServerParseResult.Valid)
        assertEquals(
            listOf("stun.example.com:3478", "[2001:db8::1]:3478"),
            (parsed as StunServerParseResult.Valid).servers,
        )
    }

    @Test
    fun customServersRejectInvalidFormatPortAndSize() {
        assertInvalidReason("missing-port", StunServerParseResult.Reason.FORMAT)
        assertInvalidReason("example.com:0", StunServerParseResult.Reason.PORT)
        assertInvalidReason("example.com:70000", StunServerParseResult.Reason.PORT)
        assertInvalidReason("", StunServerParseResult.Reason.EMPTY)
        val tooMany = (1..9).joinToString("\n") { "server$it.example:3478" }
        assertInvalidReason(tooMany, StunServerParseResult.Reason.TOO_MANY)
    }

    private fun assertInvalidReason(value: String, reason: StunServerParseResult.Reason) {
        val result = StunServerListParser.parse(value)
        assertTrue(result is StunServerParseResult.Invalid)
        assertEquals(reason, (result as StunServerParseResult.Invalid).reason)
    }
}
