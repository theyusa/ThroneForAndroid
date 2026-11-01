package io.nekohasekai.sagernet.utils

import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import javax.net.SocketFactory

/**
 * The anonymous discovery probe against fake servers. Every scenario also proves that the probe sent exactly the
 * [GREETING] and nothing else: no username, no password, no credential of any kind, not even after the server picked
 * username-password authentication.
 */
class Socks5ProbeTest {

    private companion object {
        /** The one greeting: version 5, method selection, offering no-auth and username-password. */
        val GREETING = byteArrayOf(0x05, 0x02, 0x00, 0x02)
        const val LOOPBACK = "127.0.0.1"
    }

    private val executor = Executors.newCachedThreadPool()
    private val listening = mutableListOf<ServerSocket>()

    @After
    fun tearDown() {
        listening.forEach { runCatching { it.close() } }
        executor.shutdownNow()
    }

    // ------------------------------------------------------------------------------------------------ fake servers

    /** Accepts exactly one connection and hands it to [handle]; returns the port and the completion of the handling. */
    private fun acceptOnce(handle: (Socket) -> Unit): Pair<Int, Future<*>> {
        val server = ServerSocket(0, 1, InetAddress.getLoopbackAddress())
        listening += server
        val done = executor.submit {
            try {
                server.accept().use { socket -> handle(socket) }
            } catch (_: Throwable) {
                // The probe may close first; the assertions live in the tests.
            }
        }
        return server.localPort to done
    }

    /** The greeting has to arrive in full before any reply is produced. */
    private fun readGreeting(socket: Socket): ByteArray {
        socket.soTimeout = 2000
        val input: InputStream = socket.getInputStream()
        val collected = ByteArrayOutputStream()
        val buffer = ByteArray(GREETING.size)
        while (collected.size() < GREETING.size) {
            val read = input.read(buffer, 0, GREETING.size - collected.size())
            if (read < 0) break
            collected.write(buffer, 0, read)
        }
        return collected.toByteArray()
    }

    /** Everything after the greeting until the probe closes its side; must stay empty for an anonymous probe. */
    private fun readRemainder(socket: Socket): ByteArray {
        val input: InputStream = socket.getInputStream()
        val collected = ByteArrayOutputStream()
        val buffer = ByteArray(256)
        while (true) {
            val read = try {
                input.read(buffer)
            } catch (_: SocketTimeoutException) {
                break
            }
            if (read < 0) break
            collected.write(buffer, 0, read)
        }
        return collected.toByteArray()
    }

    /**
     * Serves [reply] (null = close right after the greeting), records everything the probe sent and asserts it is
     * exactly the greeting. With [truncated] the server ends the connection right after [reply], so a one-byte answer
     * is a genuine truncation (EOF) rather than a silent socket that would only ever time out.
     */
    private fun probeAgainst(reply: ByteArray?, truncated: Boolean = false): Socks5ProbeResult {
        val recorded = AtomicReference<ByteArray>()
        val (port, done) = acceptOnce { socket ->
            val greeting = readGreeting(socket)
            recorded.set(greeting)
            if (reply != null) {
                runCatching {
                    socket.getOutputStream().apply {
                        write(reply)
                        flush()
                    }
                }
                // Closing here delivers the bytes first (TCP), then FIN: the probe reads the reply, then EOF.
                if (!truncated) recorded.set(greeting + readRemainder(socket))
            }
        }
        val result = Socks5Probe().probe(LOOPBACK, port, SocketFactory.getDefault())
        done.get(5, TimeUnit.SECONDS)
        val sent = recorded.get()
        assertNotNull("the fake server never received the greeting", sent)
        assertArrayEquals(GREETING, sent)
        return result
    }

    private fun bytes(vararg values: Int) = ByteArray(values.size) { values[it].toByte() }

    // ------------------------------------------------------------------------------------------------------ replies

    @Test
    fun noAuthReplyIsClassifiedAsMethodZero() {
        assertEquals(Socks5ProbeResult.Reply(0x00), probeAgainst(bytes(0x05, 0x00)))
    }

    @Test
    fun userPassReplyIsClassifiedAsMethodTwo() {
        // The server demands credentials here; the probe must still send the greeting only (proven above) and must
        // not follow up with a username-password packet.
        assertEquals(Socks5ProbeResult.Reply(0x02), probeAgainst(bytes(0x05, 0x02)))
    }

    @Test
    fun noAcceptableMethodsIsKeptAsReplyFF() {
        assertEquals(Socks5ProbeResult.Reply(0xFF), probeAgainst(bytes(0x05, 0xFF)))
    }

    @Test
    fun greetingIsExactlyTheAnonymousMethodSelection() {
        // A dedicated proof of the wire bytes: 05 02 00 02, nothing else, against a server that accepts no-auth.
        probeAgainst(bytes(0x05, 0x00))
    }

    // ---------------------------------------------------------------------------------------------------- failures

    @Test
    fun httpResponseIsNotSocks5() {
        val response = "HTTP/1.1 200 OK\r\nContent-Length: 0\r\n\r\n".toByteArray()
        assertEquals(Socks5ProbeResult.Failure(Socks5ProbeFailure.NOT_SOCKS5), probeAgainst(response))
    }

    @Test
    fun socks4ResponseIsNotSocks5() {
        // SOCKS4 reply: version 0x00, command 0x5A (request granted).
        assertEquals(
            Socks5ProbeResult.Failure(Socks5ProbeFailure.NOT_SOCKS5),
            probeAgainst(bytes(0x00, 0x5A, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00)),
        )
    }

    @Test
    fun closeRightAfterTheGreetingIsAProtocolFailure() {
        assertEquals(Socks5ProbeResult.Failure(Socks5ProbeFailure.PROTOCOL), probeAgainst(null))
    }

    @Test
    fun singleByteReplyIsAProtocolFailure() {
        // The server answers with 05 only and then ends the connection: a truncated reply, not a silent one.
        assertEquals(Socks5ProbeResult.Failure(Socks5ProbeFailure.PROTOCOL), probeAgainst(bytes(0x05), truncated = true))
    }

    @Test
    fun methodNeverOfferedIsAProtocolFailure() {
        // The greeting offered 00 and 02 only.
        assertEquals(Socks5ProbeResult.Failure(Socks5ProbeFailure.PROTOCOL), probeAgainst(bytes(0x05, 0x03)))
    }

    @Test
    fun silentServerTimesOut() {
        val (port, _) = acceptOnce { socket ->
            readGreeting(socket)
            // No reply: the probe's own read timeout must fire, then its close releases this read.
            readRemainder(socket)
        }
        val result = Socks5Probe().probe(LOOPBACK, port, SocketFactory.getDefault())
        assertEquals(Socks5ProbeResult.Failure(Socks5ProbeFailure.TIMEOUT), result)
    }

    @Test
    fun closedPortIsAnIoFailure() {
        val reservation = ServerSocket(0, 1, InetAddress.getLoopbackAddress())
        val port = reservation.localPort
        reservation.close()
        val result = Socks5Probe().probe(LOOPBACK, port, SocketFactory.getDefault())
        assertEquals(Socks5ProbeResult.Failure(Socks5ProbeFailure.IO), result)
    }
}
