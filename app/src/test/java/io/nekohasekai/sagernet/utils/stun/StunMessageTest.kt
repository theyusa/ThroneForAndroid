// Based on ccding/go-stun (Apache-2.0) as carried by NekoBoxPlus
package io.nekohasekai.sagernet.utils.stun

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress

class StunMessageTest {

    @Test
    fun rfc5769SampleRequestParsing() {
        // RFC 5769 Section 2.1 Sample Request
        val requestBytes = byteArrayOf(
            0x00.toByte(), 0x01.toByte(), 0x00.toByte(), 0x00.toByte(), // Type: Binding Request, Length: 0
            0x21.toByte(), 0x12.toByte(), 0xa4.toByte(), 0x42.toByte(), // Magic Cookie
            0xb7.toByte(), 0xe7.toByte(), 0xa7.toByte(), 0x01.toByte(),
            0xbc.toByte(), 0x34.toByte(), 0xd6.toByte(), 0x86.toByte(),
            0xfa.toByte(), 0x87.toByte(), 0xdf.toByte(), 0xae.toByte(), // Transaction ID (12 bytes)
        )

        val msg = StunMessage.decode(requestBytes)
        assertNotNull(msg)
        assertEquals(StunMessage.BINDING_REQUEST, msg!!.type)
        assertEquals(StunMessage.MAGIC_COOKIE, msg.magicCookie)
        assertEquals(0, msg.attributes.size)
        assertArrayEquals(requestBytes.copyOfRange(8, 20), msg.transactionId)
    }

    @Test
    fun rfc5769SampleIpv4Response() {
        // RFC 5769 Section 2.2: Sample IPv4 Response
        // Transaction ID: b7 e7 a7 01 bc 34 d6 86 fa 87 df ae
        // XOR-MAPPED-ADDRESS: 192.0.2.1:32853
        val responseBytes = byteArrayOf(
            0x01.toByte(), 0x01.toByte(), 0x00.toByte(), 0x0c.toByte(), // Type: 0x0101, Length: 12
            0x21.toByte(), 0x12.toByte(), 0xa4.toByte(), 0x42.toByte(), // Magic Cookie
            0xb7.toByte(), 0xe7.toByte(), 0xa7.toByte(), 0x01.toByte(),
            0xbc.toByte(), 0x34.toByte(), 0xd6.toByte(), 0x86.toByte(),
            0xfa.toByte(), 0x87.toByte(), 0xdf.toByte(), 0xae.toByte(), // Transaction ID
            // Attribute: XOR-MAPPED-ADDRESS (0x0020), Length: 8
            0x00.toByte(), 0x20.toByte(), 0x00.toByte(), 0x08.toByte(),
            0x00.toByte(), 0x01.toByte(), // Family: IPv4 (0x01)
            0xa1.toByte(), 0x47.toByte(), // Port: 32853 ^ 0x2112
            0xe1.toByte(), 0x12.toByte(), 0xa6.toByte(), 0x43.toByte(), // IPv4: 192.0.2.1 ^ 0x2112A442
        )

        val msg = StunMessage.decode(responseBytes)
        assertNotNull(msg)
        assertEquals(StunMessage.BINDING_RESPONSE, msg!!.type)

        val xorMapped = msg.getXorMappedAddress()
        assertNotNull(xorMapped)
        assertEquals("192.0.2.1", xorMapped!!.address.hostAddress)
        assertEquals(32853, xorMapped.port)
        assertFalse(xorMapped.isIpv6)
    }

    @Test
    fun rfc5769SampleIpv6Response() {
        // RFC 5769 Section 2.3: Sample IPv6 Response
        // XOR-MAPPED-ADDRESS: [2001:db8:1234:5678:11:2233:4455:6677]:32853
        val responseBytes = byteArrayOf(
            0x01.toByte(), 0x01.toByte(), 0x00.toByte(), 0x18.toByte(), // Type: 0x0101, Length: 24
            0x21.toByte(), 0x12.toByte(), 0xa4.toByte(), 0x42.toByte(), // Magic Cookie
            0xb7.toByte(), 0xe7.toByte(), 0xa7.toByte(), 0x01.toByte(),
            0xbc.toByte(), 0x34.toByte(), 0xd6.toByte(), 0x86.toByte(),
            0xfa.toByte(), 0x87.toByte(), 0xdf.toByte(), 0xae.toByte(), // Transaction ID
            // Attribute: XOR-MAPPED-ADDRESS (0x0020), Length: 20
            0x00.toByte(), 0x20.toByte(), 0x00.toByte(), 0x14.toByte(),
            0x00.toByte(), 0x02.toByte(), // Family: IPv6 (0x02)
            0xa1.toByte(), 0x47.toByte(), // Port: 32853 ^ 0x2112
            0x01.toByte(), 0x13.toByte(), 0xa9.toByte(), 0xfa.toByte(),
            0xa5.toByte(), 0xd3.toByte(), 0xf1.toByte(), 0x79.toByte(),
            0xbc.toByte(), 0x25.toByte(), 0xf4.toByte(), 0xb5.toByte(),
            0xbe.toByte(), 0xd2.toByte(), 0xb9.toByte(), 0xd9.toByte(),
        )

        val msg = StunMessage.decode(responseBytes)
        assertNotNull(msg)
        assertEquals(StunMessage.BINDING_RESPONSE, msg!!.type)

        val xorMapped = msg.getXorMappedAddress()
        assertNotNull(xorMapped)
        assertEquals(InetAddress.getByName("2001:db8:1234:5678:11:2233:4455:6677"), xorMapped!!.address)
        assertEquals(32853, xorMapped.port)
        assertTrue(xorMapped.isIpv6)
    }

    @Test
    fun changeRequestEncodingAndDecoding() {
        val req = StunMessage.createBindingRequest(changeIp = true, changePort = true)
        val encoded = req.encode()

        val decoded = StunMessage.decode(encoded)
        assertNotNull(decoded)
        assertEquals(StunMessage.BINDING_REQUEST, decoded!!.type)
        assertEquals(1, decoded.attributes.size)

        val attr = decoded.attributes[0]
        assertTrue(attr is StunAttribute.ChangeRequest)
        val cr = attr as StunAttribute.ChangeRequest
        assertTrue(cr.changeIp)
        assertTrue(cr.changePort)
    }

    @Test
    fun roundTripEncodingDecodingWithAttributes() {
        val transId = StunMessage.generateTransactionId()
        val mapped = StunAttribute.MappedAddress(
            StunMessage.ATTR_XOR_MAPPED_ADDRESS,
            StunEndpoint(InetAddress.getByName("198.51.100.42"), 41234),
        )
        val other = StunAttribute.MappedAddress(
            StunMessage.ATTR_OTHER_ADDRESS,
            StunEndpoint(InetAddress.getByName("203.0.113.1"), 3478),
        )

        val msg = StunMessage(
            type = StunMessage.BINDING_RESPONSE,
            transactionId = transId,
            attributes = listOf(mapped, other),
        )

        val encoded = msg.encode()
        val decoded = StunMessage.decode(encoded)

        assertNotNull(decoded)
        assertEquals(StunMessage.BINDING_RESPONSE, decoded!!.type)
        assertEquals(InetAddress.getByName("198.51.100.42"), decoded.getXorMappedAddress()?.address)
        assertEquals(41234, decoded.getXorMappedAddress()?.port)
        assertEquals(InetAddress.getByName("203.0.113.1"), decoded.getOtherAddress()?.address)
        assertEquals(3478, decoded.getOtherAddress()?.port)
    }

    @Test
    fun malformedPacketsAreSafelyRejected() {
        // Too short (< 20 bytes)
        assertNull(StunMessage.decode(ByteArray(19)))

        // Non-zero first two bits
        val nonZeroFirstBits = ByteArray(20)
        nonZeroFirstBits[0] = 0x80.toByte()
        assertNull(StunMessage.decode(nonZeroFirstBits))

        // Length not multiple of 4
        val unalignedLen = ByteArray(21)
        unalignedLen[3] = 1
        assertNull(StunMessage.decode(unalignedLen))

        // Length mismatch
        val lengthMismatch = ByteArray(24)
        lengthMismatch[3] = 8 // Claimed 8 bytes, but only 4 bytes follow header
        assertNull(StunMessage.decode(lengthMismatch))
    }
}
