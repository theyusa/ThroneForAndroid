// Based on ccding/go-stun (Apache-2.0) as carried by NekoBoxPlus
package io.nekohasekai.sagernet.utils.stun

import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.security.SecureRandom
import java.util.zip.CRC32

/**
 * Endpoint discovered via STUN: an IP address and UDP port.
 */
data class StunEndpoint(
    val address: InetAddress,
    val port: Int,
) {
    val isIpv6: Boolean get() = address is Inet6Address

    override fun toString(): String =
        if (isIpv6) "[${address.hostAddress}]:$port" else "${address.hostAddress}:$port"
}

/**
 * Represents an attribute inside a STUN message (RFC 5389 / RFC 5780 / RFC 3489).
 */
sealed class StunAttribute(val type: Int) {
    class MappedAddress(
        type: Int,
        val endpoint: StunEndpoint,
    ) : StunAttribute(type)

    class ChangeRequest(
        val changeIp: Boolean,
        val changePort: Boolean,
    ) : StunAttribute(StunMessage.ATTR_CHANGE_REQUEST)

    class ErrorCode(
        val code: Int,
        val reason: String,
    ) : StunAttribute(StunMessage.ATTR_ERROR_CODE)

    class Raw(
        type: Int,
        val data: ByteArray,
    ) : StunAttribute(type)
}

/**
 * Pure Kotlin STUN message encoder / decoder conforming to RFC 5389, RFC 5780, and RFC 3489.
 * Does not depend on any android.* or org.json APIs.
 */
data class StunMessage(
    val type: Int,
    val transactionId: ByteArray,
    val attributes: List<StunAttribute> = emptyList(),
    val magicCookie: Int = MAGIC_COOKIE,
) {
    companion object {
        const val MAGIC_COOKIE = 0x2112A442
        const val FINGERPRINT_XOR = 0x5354554e

        // STUN Message Types
        const val BINDING_REQUEST = 0x0001
        const val BINDING_RESPONSE = 0x0101
        const val BINDING_ERROR_RESPONSE = 0x0111

        // STUN Attribute Types
        const val ATTR_MAPPED_ADDRESS = 0x0001
        const val ATTR_RESPONSE_ADDRESS = 0x0002
        const val ATTR_CHANGE_REQUEST = 0x0003
        const val ATTR_SOURCE_ADDRESS = 0x0004
        const val ATTR_CHANGED_ADDRESS = 0x0005
        const val ATTR_ERROR_CODE = 0x0009
        const val ATTR_XOR_MAPPED_ADDRESS = 0x0020
        const val ATTR_XOR_MAPPED_ADDRESS_EXP = 0x8020
        const val ATTR_SOFTWARE = 0x8022
        const val ATTR_FINGERPRINT = 0x8028
        const val ATTR_RESPONSE_ORIGIN = 0x802b
        const val ATTR_OTHER_ADDRESS = 0x802c

        private val secureRandom = SecureRandom()

        fun generateTransactionId(): ByteArray {
            val bytes = ByteArray(12)
            secureRandom.nextBytes(bytes)
            return bytes
        }

        fun createBindingRequest(
            transactionId: ByteArray = generateTransactionId(),
            changeIp: Boolean = false,
            changePort: Boolean = false,
        ): StunMessage {
            val attrs = mutableListOf<StunAttribute>()
            if (changeIp || changePort) {
                attrs.add(StunAttribute.ChangeRequest(changeIp, changePort))
            }
            return StunMessage(
                type = BINDING_REQUEST,
                transactionId = transactionId,
                attributes = attrs,
            )
        }

        /**
         * Safely decodes a raw STUN packet. Returns null if the packet is malformed,
         * too short, has invalid framing, or failed fingerprint checks.
         */
        fun decode(data: ByteArray): StunMessage? {
            if (data.size < 20 || data.size > 0xFFFF + 20) return null

            // First 2 bits of STUN message must be 00
            if ((data[0].toInt() and 0xC0) != 0) return null

            val type = readUint16(data, 0)
            val messageLength = readUint16(data, 2)

            // Length must be a multiple of 4 and match the remaining packet size exactly
            if (messageLength % 4 != 0 || messageLength != data.size - 20) return null

            val cookie = readUint32(data, 4).toInt()
            val transactionId = data.copyOfRange(8, 20)

            val attributes = mutableListOf<StunAttribute>()
            var pos = 20
            val endPos = data.size

            while (pos < endPos) {
                if (endPos - pos < 4) return null // Truncated attribute header

                val attrType = readUint16(data, pos)
                val attrLength = readUint16(data, pos + 2)
                val paddedLength = (attrLength + 3) and 3.inv()

                val valueStart = pos + 4
                val valueEnd = valueStart + attrLength
                val nextPos = valueStart + paddedLength

                if (valueEnd > endPos || nextPos > endPos) return null // Out-of-bounds

                if (attrType == ATTR_FINGERPRINT) {
                    if (attrLength != 4) return null
                    val gotCrc = readUint32(data, valueStart)
                    val crc = CRC32()
                    crc.update(data, 0, pos)
                    val expectedCrc = (crc.value xor FINGERPRINT_XOR.toLong()) and 0xFFFFFFFFL
                    if (gotCrc != expectedCrc) return null
                }

                val attr = parseAttribute(attrType, data, valueStart, attrLength, cookie, transactionId)
                if (attr != null) {
                    attributes.add(attr)
                }
                pos = nextPos
            }

            return StunMessage(
                type = type,
                transactionId = transactionId,
                attributes = attributes,
                magicCookie = cookie,
            )
        }

        private fun parseAttribute(
            type: Int,
            data: ByteArray,
            offset: Int,
            length: Int,
            cookie: Int,
            transactionId: ByteArray,
        ): StunAttribute? {
            return when (type) {
                ATTR_MAPPED_ADDRESS, ATTR_RESPONSE_ADDRESS, ATTR_SOURCE_ADDRESS,
                ATTR_CHANGED_ADDRESS, ATTR_RESPONSE_ORIGIN, ATTR_OTHER_ADDRESS -> {
                    parseMappedAddress(type, data, offset, length)
                }
                ATTR_XOR_MAPPED_ADDRESS, ATTR_XOR_MAPPED_ADDRESS_EXP -> {
                    parseXorMappedAddress(type, data, offset, length, cookie, transactionId)
                }
                ATTR_CHANGE_REQUEST -> {
                    if (length < 4) return null
                    val flags = readUint32(data, offset).toInt()
                    val changeIp = (flags and 0x04) != 0
                    val changePort = (flags and 0x02) != 0
                    StunAttribute.ChangeRequest(changeIp, changePort)
                }
                ATTR_ERROR_CODE -> {
                    if (length < 4) return null
                    val clazz = data[offset + 2].toInt() and 0x07
                    val number = data[offset + 3].toInt() and 0xFF
                    val code = clazz * 100 + number
                    val reason = if (length > 4) {
                        String(data, offset + 4, length - 4, Charsets.UTF_8)
                    } else ""
                    StunAttribute.ErrorCode(code, reason)
                }
                else -> {
                    val rawData = data.copyOfRange(offset, offset + length)
                    StunAttribute.Raw(type, rawData)
                }
            }
        }

        private fun parseMappedAddress(type: Int, data: ByteArray, offset: Int, length: Int): StunAttribute? {
            if (length < 8) return null
            val family = data[offset + 1].toInt() and 0xFF
            val port = readUint16(data, offset + 2)
            val address = when (family) {
                0x01 -> { // IPv4
                    if (length < 8) return null
                    val ipBytes = data.copyOfRange(offset + 4, offset + 8)
                    runCatching { InetAddress.getByAddress(ipBytes) }.getOrNull() ?: return null
                }
                0x02 -> { // IPv6
                    if (length < 20) return null
                    val ipBytes = data.copyOfRange(offset + 4, offset + 20)
                    runCatching { InetAddress.getByAddress(ipBytes) }.getOrNull() ?: return null
                }
                else -> return null
            }
            return StunAttribute.MappedAddress(type, StunEndpoint(address, port))
        }

        private fun parseXorMappedAddress(
            type: Int,
            data: ByteArray,
            offset: Int,
            length: Int,
            cookie: Int,
            transactionId: ByteArray,
        ): StunAttribute? {
            if (length < 8) return null
            val family = data[offset + 1].toInt() and 0xFF
            val xorPort = readUint16(data, offset + 2)
            val port = xorPort xor (cookie ushr 16)

            val address = when (family) {
                0x01 -> { // IPv4
                    if (length < 8) return null
                    val rawIpInt = readUint32(data, offset + 4)
                    val ipInt = rawIpInt xor (cookie.toLong() and 0xFFFFFFFFL)
                    val ipBytes = ByteArray(4) { idx -> ((ipInt ushr ((3 - idx) * 8)) and 0xFF).toByte() }
                    runCatching { InetAddress.getByAddress(ipBytes) }.getOrNull() ?: return null
                }
                0x02 -> { // IPv6
                    if (length < 20) return null
                    val xorBytes = data.copyOfRange(offset + 4, offset + 20)
                    val ipBytes = ByteArray(16)
                    val cookieBytes = ByteArray(4) { idx -> ((cookie ushr ((3 - idx) * 8)) and 0xFF).toByte() }
                    for (i in 0 until 4) {
                        ipBytes[i] = (xorBytes[i].toInt() xor cookieBytes[i].toInt()).toByte()
                    }
                    for (i in 0 until 12) {
                        ipBytes[4 + i] = (xorBytes[4 + i].toInt() xor transactionId[i].toInt()).toByte()
                    }
                    runCatching { InetAddress.getByAddress(ipBytes) }.getOrNull() ?: return null
                }
                else -> return null
            }
            return StunAttribute.MappedAddress(type, StunEndpoint(address, port))
        }

        private fun readUint16(data: ByteArray, offset: Int): Int =
            ((data[offset].toInt() and 0xFF) shl 8) or (data[offset + 1].toInt() and 0xFF)

        private fun readUint32(data: ByteArray, offset: Int): Long =
            (((data[offset].toLong() and 0xFF) shl 24) or
                ((data[offset + 1].toLong() and 0xFF) shl 16) or
                ((data[offset + 2].toLong() and 0xFF) shl 8) or
                (data[offset + 3].toLong() and 0xFF)) and 0xFFFFFFFFL

        private fun writeUint16(data: ByteArray, offset: Int, value: Int) {
            data[offset] = ((value ushr 8) and 0xFF).toByte()
            data[offset + 1] = (value and 0xFF).toByte()
        }

        private fun writeUint32(data: ByteArray, offset: Int, value: Long) {
            data[offset] = ((value ushr 24) and 0xFF).toByte()
            data[offset + 1] = ((value ushr 16) and 0xFF).toByte()
            data[offset + 2] = ((value ushr 8) and 0xFF).toByte()
            data[offset + 3] = (value and 0xFF).toByte()
        }
    }

    /**
     * Encodes this STUN message into a byte array suitable for UDP transmission.
     */
    fun encode(): ByteArray {
        val attrPayloads = mutableListOf<ByteArray>()
        var totalAttrsLength = 0

        for (attr in attributes) {
            val payload = encodeAttribute(attr)
            attrPayloads.add(payload)
            totalAttrsLength += payload.size
        }

        val result = ByteArray(20 + totalAttrsLength)
        writeUint16(result, 0, type)
        writeUint16(result, 2, totalAttrsLength)
        writeUint32(result, 4, magicCookie.toLong() and 0xFFFFFFFFL)
        System.arraycopy(transactionId, 0, result, 8, minOf(12, transactionId.size))

        var pos = 20
        for (payload in attrPayloads) {
            System.arraycopy(payload, 0, result, pos, payload.size)
            pos += payload.size
        }

        return result
    }

    private fun encodeAttribute(attr: StunAttribute): ByteArray {
        val valueBytes = when (attr) {
            is StunAttribute.ChangeRequest -> {
                val buf = ByteArray(4)
                val flags = (if (attr.changeIp) 4 else 0) or (if (attr.changePort) 2 else 0)
                writeUint32(buf, 0, flags.toLong())
                buf
            }
            is StunAttribute.MappedAddress -> {
                encodeMappedAddress(attr.type, attr.endpoint)
            }
            is StunAttribute.ErrorCode -> {
                val reasonBytes = attr.reason.toByteArray(Charsets.UTF_8)
                val buf = ByteArray(4 + reasonBytes.size)
                buf[2] = ((attr.code / 100) and 0x07).toByte()
                buf[3] = (attr.code % 100).toByte()
                System.arraycopy(reasonBytes, 0, buf, 4, reasonBytes.size)
                buf
            }
            is StunAttribute.Raw -> attr.data
        }

        val paddedLength = (valueBytes.size + 3) and 3.inv()
        val totalLength = 4 + paddedLength
        val out = ByteArray(totalLength)
        writeUint16(out, 0, attr.type)
        writeUint16(out, 2, valueBytes.size)
        System.arraycopy(valueBytes, 0, out, 4, valueBytes.size)
        return out
    }

    private fun encodeMappedAddress(type: Int, endpoint: StunEndpoint): ByteArray {
        val isXor = (type == ATTR_XOR_MAPPED_ADDRESS || type == ATTR_XOR_MAPPED_ADDRESS_EXP)
        val ip = endpoint.address
        val isV6 = ip is Inet6Address
        val valLen = if (isV6) 20 else 8
        val buf = ByteArray(valLen)

        buf[0] = 0 // reserved
        buf[1] = if (isV6) 2 else 1 // family

        val portVal = if (isXor) endpoint.port xor (magicCookie ushr 16) else endpoint.port
        writeUint16(buf, 2, portVal)

        if (!isV6) {
            val ipBytes = (ip as Inet4Address).address
            if (isXor) {
                val cookieBytes = ByteArray(4) { idx -> ((magicCookie ushr ((3 - idx) * 8)) and 0xFF).toByte() }
                for (i in 0 until 4) {
                    buf[4 + i] = (ipBytes[i].toInt() xor cookieBytes[i].toInt()).toByte()
                }
            } else {
                System.arraycopy(ipBytes, 0, buf, 4, 4)
            }
        } else {
            val ipBytes = (ip as Inet6Address).address
            if (isXor) {
                val cookieBytes = ByteArray(4) { idx -> ((magicCookie ushr ((3 - idx) * 8)) and 0xFF).toByte() }
                for (i in 0 until 4) {
                    buf[4 + i] = (ipBytes[i].toInt() xor cookieBytes[i].toInt()).toByte()
                }
                for (i in 0 until 12) {
                    buf[8 + i] = (ipBytes[4 + i].toInt() xor transactionId[i].toInt()).toByte()
                }
            } else {
                System.arraycopy(ipBytes, 0, buf, 4, 16)
            }
        }
        return buf
    }

    fun getXorMappedAddress(): StunEndpoint? =
        attributes.filterIsInstance<StunAttribute.MappedAddress>()
            .firstOrNull { it.type == ATTR_XOR_MAPPED_ADDRESS || it.type == ATTR_XOR_MAPPED_ADDRESS_EXP }
            ?.endpoint

    fun getMappedAddress(): StunEndpoint? =
        attributes.filterIsInstance<StunAttribute.MappedAddress>()
            .firstOrNull { it.type == ATTR_MAPPED_ADDRESS }
            ?.endpoint

    fun getOtherAddress(): StunEndpoint? =
        attributes.filterIsInstance<StunAttribute.MappedAddress>()
            .firstOrNull { it.type == ATTR_OTHER_ADDRESS }
            ?.endpoint

    fun getChangedAddress(): StunEndpoint? =
        attributes.filterIsInstance<StunAttribute.MappedAddress>()
            .firstOrNull { it.type == ATTR_CHANGED_ADDRESS }
            ?.endpoint

    fun getResponseOrigin(): StunEndpoint? =
        attributes.filterIsInstance<StunAttribute.MappedAddress>()
            .firstOrNull { it.type == ATTR_RESPONSE_ORIGIN }
            ?.endpoint

    /**
     * Prefers XOR-MAPPED-ADDRESS over legacy MAPPED-ADDRESS as mandated by RFC 5389.
     */
    fun preferredMappedAddress(): StunEndpoint? =
        getXorMappedAddress() ?: getMappedAddress()

    /**
     * Prefers RFC 5780 OTHER-ADDRESS over RFC 3489 CHANGED-ADDRESS.
     */
    fun preferredOtherAddress(): StunEndpoint? =
        getOtherAddress() ?: getChangedAddress()
}
