// Based on ccding/go-stun (Apache-2.0) as carried by NekoBoxPlus
package io.nekohasekai.sagernet.utils.stun

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap

/**
 * Pure Kotlin STUN NAT Behavior Discovery engine implementing RFC 5389 and RFC 5780.
 * Operates without android.* and org.json dependencies for JVM testability.
 */
class StunNatTester(
    private val socketFactory: StunSocketFactory = DefaultStunSocketFactory(),
) {
    private val activeSockets = Collections.newSetFromMap(ConcurrentHashMap<DatagramSocket, Boolean>())
    @Volatile private var isCancelled = false

    fun cancel() {
        isCancelled = true
        activeSockets.forEach { socket ->
            runCatching { socket.close() }
        }
        activeSockets.clear()
    }

    suspend fun test(
        servers: List<String>,
        ipv6Mode: Int = 0,
    ): List<StunServerUiResult> = withContext(Dispatchers.IO) {
        isCancelled = false
        coroutineScope {
            servers.map { server ->
                async {
                    testServer(server, ipv6Mode)
                }
            }.map { it.await() }
        }
    }

    private fun testServer(address: String, ipv6Mode: Int): StunServerUiResult {
        val started = System.currentTimeMillis()
        var socket: DatagramSocket? = null

        val resultBuilder = StunServerUiResult(
            server = address,
            bindingSuccess = false,
            behaviorSupported = false,
            behaviorComplete = false,
            natType = StunProtocolCodes.NAT_UNKNOWN,
            mappingBehavior = StunProtocolCodes.BEHAVIOR_UNKNOWN,
            filteringBehavior = StunProtocolCodes.BEHAVIOR_UNKNOWN,
            externalAddress = "",
            externalPort = 0,
            ipFamily = 0,
            durationMilliseconds = 0,
            errorCode = "",
            errorMessage = "",
            warningCode = "",
        )

        try {
            if (isCancelled) {
                return resultBuilder.copy(
                    durationMilliseconds = System.currentTimeMillis() - started,
                    errorCode = "cancelled",
                    errorMessage = "Test cancelled",
                )
            }

            val target = resolveServer(address, ipv6Mode)
            socket = socketFactory.createDatagramSocket()
            activeSockets.add(socket)

            if (isCancelled) {
                socket.close()
                return resultBuilder.copy(
                    durationMilliseconds = System.currentTimeMillis() - started,
                    errorCode = "cancelled",
                    errorMessage = "Test cancelled",
                )
            }

            // Step 1: Binding Test 1 to primary target
            val resp1 = sendBindingRequest(socket, target, changeIp = false, changePort = false)
                ?: return resultBuilder.copy(
                    durationMilliseconds = System.currentTimeMillis() - started,
                    natType = StunProtocolCodes.NAT_BLOCKED,
                    errorCode = "timeout",
                    errorMessage = "Response timed out",
                )

            val mappedAddr = resp1.preferredMappedAddress()
                ?: return resultBuilder.copy(
                    durationMilliseconds = System.currentTimeMillis() - started,
                    natType = StunProtocolCodes.NAT_ERROR,
                    errorCode = "configuration",
                    errorMessage = "Server response has no mapped address",
                )

            val otherAddr = resp1.preferredOtherAddress()
            val warningCode = if (resp1.getResponseOrigin()?.let {
                    it.address != target.address || it.port != target.port
                } == true) "response_address_mismatch" else ""

            val localAddress = socket.localAddress
            val identical = (localAddress != null && !localAddress.isAnyLocalAddress &&
                mappedAddr.address == localAddress && mappedAddr.port == socket.localPort)

            val isUsableAlternate = otherAddr != null &&
                otherAddr.address != target.address &&
                otherAddr.port != target.port

            if (!isUsableAlternate) {
                return resultBuilder.copy(
                    bindingSuccess = true,
                    behaviorSupported = false,
                    behaviorComplete = false,
                    natType = StunProtocolCodes.NAT_UNKNOWN,
                    mappingBehavior = StunProtocolCodes.BEHAVIOR_UNKNOWN,
                    filteringBehavior = StunProtocolCodes.BEHAVIOR_UNKNOWN,
                    externalAddress = mappedAddr.address.hostAddress ?: "",
                    externalPort = mappedAddr.port,
                    ipFamily = if (mappedAddr.isIpv6) 2 else 1,
                    durationMilliseconds = System.currentTimeMillis() - started,
                    errorCode = "behavior_unsupported",
                    errorMessage = "Server does not support RFC 5780 behavior discovery",
                    warningCode = warningCode,
                )
            }

            val altTarget = InetSocketAddress(otherAddr.address, otherAddr.port)

            // Step 2: Filtering Test II -> Send to primary with Change IP + Change Port
            val resp4 = sendBindingRequest(socket, target, changeIp = true, changePort = true)
            val resp4Received = (resp4 != null)

            val filteringBehavior: Int
            if (resp4Received) {
                filteringBehavior = StunProtocolCodes.BEHAVIOR_ENDPOINT
            } else {
                // Step 3: Filtering Test III -> Send to primary with Change Port only
                val resp5 = sendBindingRequest(socket, target, changeIp = false, changePort = true)
                filteringBehavior = if (resp5 != null) {
                    StunProtocolCodes.BEHAVIOR_ADDRESS
                } else {
                    StunProtocolCodes.BEHAVIOR_ADDRESS_AND_PORT
                }
            }

            // Step 4: Mapping Behavior Tests
            val mappingBehavior: Int
            if (identical) {
                mappingBehavior = StunProtocolCodes.BEHAVIOR_ENDPOINT
            } else {
                // Mapping Test II -> Send to alternate IP with primary Port
                val altIpPrimaryPort = InetSocketAddress(altTarget.address, target.port)
                val respMap2 = sendBindingRequest(socket, altIpPrimaryPort, changeIp = false, changePort = false)
                val map2Addr = respMap2?.preferredMappedAddress()

                if (map2Addr != null && map2Addr.address == mappedAddr.address && map2Addr.port == mappedAddr.port) {
                    mappingBehavior = StunProtocolCodes.BEHAVIOR_ENDPOINT
                } else {
                    // Mapping Test III -> Send to alternate IP with alternate Port
                    val respMap3 = sendBindingRequest(socket, altTarget, changeIp = false, changePort = false)
                    val map3Addr = respMap3?.preferredMappedAddress()

                    mappingBehavior = if (map3Addr != null && map2Addr != null &&
                        map3Addr.address == map2Addr.address && map3Addr.port == map2Addr.port) {
                        StunProtocolCodes.BEHAVIOR_ADDRESS
                    } else {
                        StunProtocolCodes.BEHAVIOR_ADDRESS_AND_PORT
                    }
                }
            }

            // Step 5: Classic NAT Type Determination (RFC 3489)
            val natType: Int = if (identical) {
                if (resp4Received) StunProtocolCodes.NAT_NONE else StunProtocolCodes.NAT_SYMMETRIC_UDP_FIREWALL
            } else if (resp4Received) {
                StunProtocolCodes.NAT_FULL
            } else {
                // Send Test 1 to alternate IP and alternate Port
                val respAlt1 = sendBindingRequest(socket, altTarget, changeIp = false, changePort = false)
                val altMapped = respAlt1?.preferredMappedAddress()
                if (altMapped != null && altMapped.address == mappedAddr.address && altMapped.port == mappedAddr.port) {
                    // Send Test 3 (change port) to alternate target
                    val respAlt3 = sendBindingRequest(socket, altTarget, changeIp = false, changePort = true)
                    if (respAlt3 != null) StunProtocolCodes.NAT_RESTRICTED else StunProtocolCodes.NAT_PORT_RESTRICTED
                } else {
                    StunProtocolCodes.NAT_SYMMETRIC
                }
            }

            return resultBuilder.copy(
                bindingSuccess = true,
                behaviorSupported = true,
                behaviorComplete = true,
                natType = natType,
                mappingBehavior = mappingBehavior,
                filteringBehavior = filteringBehavior,
                externalAddress = mappedAddr.address.hostAddress ?: "",
                externalPort = mappedAddr.port,
                ipFamily = if (mappedAddr.isIpv6) 2 else 1,
                durationMilliseconds = System.currentTimeMillis() - started,
                warningCode = warningCode,
            )
        } catch (e: CancellationException) {
            return resultBuilder.copy(
                durationMilliseconds = System.currentTimeMillis() - started,
                errorCode = "cancelled",
                errorMessage = "Test cancelled",
            )
        } catch (e: UnknownHostException) {
            return resultBuilder.copy(
                durationMilliseconds = System.currentTimeMillis() - started,
                errorCode = "dns",
                errorMessage = e.message ?: "DNS resolution failed",
            )
        } catch (e: SocketTimeoutException) {
            return resultBuilder.copy(
                durationMilliseconds = System.currentTimeMillis() - started,
                errorCode = "timeout",
                errorMessage = "Timeout",
            )
        } catch (e: Exception) {
            if (isCancelled || socket?.isClosed == true) {
                return resultBuilder.copy(
                    durationMilliseconds = System.currentTimeMillis() - started,
                    errorCode = "cancelled",
                    errorMessage = "Test cancelled",
                )
            }
            return resultBuilder.copy(
                durationMilliseconds = System.currentTimeMillis() - started,
                errorCode = "network",
                errorMessage = e.message ?: "Network error",
            )
        } finally {
            socket?.let {
                activeSockets.remove(it)
                runCatching { it.close() }
            }
        }
    }

    private fun resolveServer(address: String, ipv6Mode: Int): InetSocketAddress {
        val (host, port) = parseHostPort(address)
        val addresses = InetAddress.getAllByName(host)
        val selected = selectAddress(addresses, ipv6Mode)
            ?: throw UnknownHostException("No address found matching IPv6 mode $ipv6Mode for $host")
        return InetSocketAddress(selected, port)
    }

    private fun parseHostPort(address: String): Pair<String, Int> {
        val trimmed = address.trim()
        val host: String
        val port: Int
        if (trimmed.startsWith("[")) {
            val endBracket = trimmed.indexOf(']')
            if (endBracket == -1 || !trimmed.substring(endBracket).startsWith(":")) {
                throw IllegalArgumentException("Invalid IPv6 host:port format: $address")
            }
            host = trimmed.substring(1, endBracket)
            port = trimmed.substring(endBracket + 2).toIntOrNull()
                ?: throw IllegalArgumentException("Invalid port in $address")
        } else {
            val lastColon = trimmed.lastIndexOf(':')
            if (lastColon == -1) {
                throw IllegalArgumentException("Missing port in $address")
            }
            host = trimmed.substring(0, lastColon)
            port = trimmed.substring(lastColon + 1).toIntOrNull()
                ?: throw IllegalArgumentException("Invalid port in $address")
        }
        if (port !in 1..65535) throw IllegalArgumentException("Port out of range: $port")
        return Pair(host, port)
    }

    private fun selectAddress(addresses: Array<InetAddress>, ipv6Mode: Int): InetAddress? {
        val v4 = addresses.firstOrNull { it is Inet4Address }
        val v6 = addresses.firstOrNull { it is Inet6Address }
        return when (ipv6Mode) {
            3 -> v6 // Only IPv6
            2 -> v6 ?: v4 // Prefer IPv6
            1 -> v4 ?: v6 // Enable IPv6, prefer IPv4
            else -> v4 ?: v6 // Disable IPv6, fallback to IPv4
        }
    }

    private fun sendBindingRequest(
        socket: DatagramSocket,
        target: InetSocketAddress,
        changeIp: Boolean,
        changePort: Boolean,
        maxRetries: Int = 3,
        initialTimeoutMs: Int = 500,
    ): StunMessage? {
        val req = StunMessage.createBindingRequest(
            changeIp = changeIp,
            changePort = changePort,
        )
        val reqBytes = req.encode()
        val sendPacket = DatagramPacket(reqBytes, reqBytes.size, target)

        val recvBuf = ByteArray(2048)
        val recvPacket = DatagramPacket(recvBuf, recvBuf.size)

        var timeout = initialTimeoutMs
        for (attempt in 0 until maxRetries) {
            if (isCancelled || socket.isClosed) return null
            try {
                socket.send(sendPacket)
                val deadline = System.currentTimeMillis() + timeout

                while (System.currentTimeMillis() < deadline) {
                    val remaining = (deadline - System.currentTimeMillis()).toInt().coerceAtLeast(1)
                    socket.soTimeout = remaining
                    socket.receive(recvPacket)

                    val response = StunMessage.decode(recvPacket.data.copyOf(recvPacket.length))
                    if (response != null && response.transactionId.contentEquals(req.transactionId)) {
                        return response
                    }
                }
            } catch (_: SocketTimeoutException) {
                // Exponential backoff
                timeout *= 2
            } catch (e: SocketException) {
                if (isCancelled || socket.isClosed) return null
                throw e
            } catch (e: IOException) {
                if (isCancelled || socket.isClosed) return null
                throw e
            }
        }
        return null
    }
}
