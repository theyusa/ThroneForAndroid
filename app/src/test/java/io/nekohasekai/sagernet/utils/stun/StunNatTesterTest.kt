// Based on ccding/go-stun (Apache-2.0) as carried by NekoBoxPlus
package io.nekohasekai.sagernet.utils.stun

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicBoolean

class StunNatTesterTest {

    @Test
    fun testEndpointIndependentFullCone() = runBlocking {
        val server = FakeStunServer(
            provideOtherAddress = true,
            respondToChangeBoth = true,
            respondToChangePort = true,
            simulateSymmetric = false,
        )
        try {
            server.start()
            val tester = StunNatTester()
            val results = tester.test(listOf(server.primaryEndpoint))

            assertEquals(1, results.size)
            val res = results[0]
            assertTrue(res.bindingSuccess)
            assertTrue(res.behaviorSupported)
            assertTrue(res.behaviorComplete)
            assertEquals(StunProtocolCodes.BEHAVIOR_ENDPOINT, res.mappingBehavior)
            assertEquals(StunProtocolCodes.BEHAVIOR_ENDPOINT, res.filteringBehavior)
            assertEquals(StunProtocolCodes.NAT_FULL, res.natType)

            val assessment = StunAssessmentPolicy.assess(results)
            assertEquals(StunAssessmentKind.FAVORABLE, assessment.kind)
        } finally {
            server.close()
        }
    }

    @Test
    fun testSymmetricNat() = runBlocking {
        val server = FakeStunServer(
            provideOtherAddress = true,
            respondToChangeBoth = false,
            respondToChangePort = false,
            simulateSymmetric = true,
        )
        try {
            server.start()
            val tester = StunNatTester()
            val results = tester.test(listOf(server.primaryEndpoint))

            assertEquals(1, results.size)
            val res = results[0]
            assertTrue(res.bindingSuccess)
            assertTrue(res.behaviorSupported)
            assertTrue(res.behaviorComplete)
            assertEquals(StunProtocolCodes.BEHAVIOR_ADDRESS_AND_PORT, res.mappingBehavior)
            assertEquals(StunProtocolCodes.BEHAVIOR_ADDRESS_AND_PORT, res.filteringBehavior)
            assertEquals(StunProtocolCodes.NAT_SYMMETRIC, res.natType)

            val assessment = StunAssessmentPolicy.assess(results)
            assertEquals(StunAssessmentKind.RESTRICTIVE, assessment.kind)
        } finally {
            server.close()
        }
    }

    @Test
    fun testBasicOnlyWhenNoOtherAddress() = runBlocking {
        val server = FakeStunServer(
            provideOtherAddress = false,
        )
        try {
            server.start()
            val tester = StunNatTester()
            val results = tester.test(listOf(server.primaryEndpoint))

            assertEquals(1, results.size)
            val res = results[0]
            assertTrue(res.bindingSuccess)
            assertFalse(res.behaviorSupported)
            assertFalse(res.behaviorComplete)
            assertEquals("behavior_unsupported", res.errorCode)

            val assessment = StunAssessmentPolicy.assess(results)
            assertEquals(StunAssessmentKind.BASIC_ONLY, assessment.kind)
        } finally {
            server.close()
        }
    }

    @Test
    fun testTimeoutFailsGracefully() = runBlocking {
        // Point to an unused loopback port that does not answer
        val unusedSocket = DatagramSocket(InetSocketAddress("127.0.0.1", 0))
        val port = unusedSocket.localPort
        unusedSocket.close()

        val tester = StunNatTester()
        val results = tester.test(listOf("127.0.0.1:$port"))

        assertEquals(1, results.size)
        val res = results[0]
        assertFalse(res.bindingSuccess)
        assertEquals("timeout", res.errorCode)
        assertEquals(StunProtocolCodes.NAT_BLOCKED, res.natType)

        val assessment = StunAssessmentPolicy.assess(results)
        assertEquals(StunAssessmentKind.FAILED, assessment.kind)
    }

    @Test
    fun testCancellation() = runBlocking {
        val server = FakeStunServer()
        try {
            server.start()
            val tester = StunNatTester()
            tester.cancel() // Cancel upfront
            val results = tester.test(listOf(server.primaryEndpoint))

            assertEquals(1, results.size)
            assertEquals("cancelled", results[0].errorCode)

            val assessment = StunAssessmentPolicy.assess(results)
            assertEquals(StunAssessmentKind.CANCELLED, assessment.kind)
        } finally {
            server.close()
        }
    }

    private class FakeStunServer(
        private val provideOtherAddress: Boolean = true,
        private val respondToChangeBoth: Boolean = true,
        private val respondToChangePort: Boolean = true,
        private val simulateSymmetric: Boolean = false,
    ) {
        val ip1 = InetAddress.getByName("127.0.0.1")
        val ip2 = InetAddress.getByName("127.0.0.2")

        lateinit var socket1A: DatagramSocket
        lateinit var socket2A: DatagramSocket
        lateinit var socket2B: DatagramSocket
        lateinit var socket1B: DatagramSocket

        private val running = AtomicBoolean(true)
        private val threads = mutableListOf<Thread>()

        val primaryEndpoint: String get() = "127.0.0.1:${socket1A.localPort}"

        fun start() {
            socket1A = DatagramSocket(InetSocketAddress(ip1, 0))
            val portA = socket1A.localPort

            socket2A = DatagramSocket(InetSocketAddress(ip2, portA))

            socket2B = DatagramSocket(InetSocketAddress(ip2, 0))
            val portB = socket2B.localPort

            socket1B = DatagramSocket(InetSocketAddress(ip1, portB))

            startListener(socket1A, ip1, portA, isPrimary = true)
            startListener(socket2A, ip2, portA, isPrimary = false)
            startListener(socket2B, ip2, portB, isPrimary = false)
            startListener(socket1B, ip1, portB, isPrimary = false)
        }

        private fun startListener(socket: DatagramSocket, localIp: InetAddress, localPort: Int, isPrimary: Boolean) {
            val t = Thread {
                val buf = ByteArray(2048)
                val packet = DatagramPacket(buf, buf.size)
                while (running.get() && !socket.isClosed) {
                    try {
                        socket.receive(packet)
                        val req = StunMessage.decode(packet.data.copyOf(packet.length)) ?: continue
                        val clientAddr = packet.address
                        val clientPort = packet.port

                        val cr = req.attributes.filterIsInstance<StunAttribute.ChangeRequest>().firstOrNull()
                        val changeIp = cr?.changeIp == true
                        val changePort = cr?.changePort == true

                        if (changeIp && changePort) {
                            if (!respondToChangeBoth) continue
                            // Respond from alternate socket 2B
                            sendResponse(socket2B, req.transactionId, clientAddr, clientPort, localIp, localPort, isAlternateQuery = false)
                        } else if (changePort) {
                            if (!respondToChangePort) continue
                            // Respond from socket 1B
                            sendResponse(socket1B, req.transactionId, clientAddr, clientPort, localIp, localPort, isAlternateQuery = false)
                        } else {
                            val isAlt = !isPrimary
                            sendResponse(socket, req.transactionId, clientAddr, clientPort, localIp, localPort, isAlternateQuery = isAlt)
                        }
                    } catch (_: Exception) {
                        break
                    }
                }
            }
            t.isDaemon = true
            t.start()
            threads.add(t)
        }

        private fun sendResponse(
            sendSocket: DatagramSocket,
            transId: ByteArray,
            clientAddr: InetAddress,
            clientPort: Int,
            respOriginIp: InetAddress,
            respOriginPort: Int,
            isAlternateQuery: Boolean,
        ) {
            val mappedPort = if (simulateSymmetric && isAlternateQuery) clientPort + 100 else clientPort
            val mapped = StunAttribute.MappedAddress(
                StunMessage.ATTR_XOR_MAPPED_ADDRESS,
                StunEndpoint(clientAddr, mappedPort),
            )
            val origin = StunAttribute.MappedAddress(
                StunMessage.ATTR_RESPONSE_ORIGIN,
                StunEndpoint(respOriginIp, respOriginPort),
            )

            val attrs = mutableListOf<StunAttribute>(mapped, origin)
            if (provideOtherAddress) {
                val other = StunAttribute.MappedAddress(
                    StunMessage.ATTR_OTHER_ADDRESS,
                    StunEndpoint(ip2, socket2B.localPort),
                )
                attrs.add(other)
            }

            val resp = StunMessage(
                type = StunMessage.BINDING_RESPONSE,
                transactionId = transId,
                attributes = attrs,
            )
            val bytes = resp.encode()
            sendSocket.send(DatagramPacket(bytes, bytes.size, clientAddr, clientPort))
        }

        fun close() {
            running.set(false)
            listOf(socket1A, socket2A, socket2B, socket1B).forEach {
                runCatching { it.close() }
            }
            threads.forEach {
                runCatching { it.interrupt() }
            }
        }
    }
}
