package io.nekohasekai.sagernet.utils

import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.InputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import javax.net.SocketFactory

/**
 * The start-time RFC1929 check against fake servers.
 *
 * Every scenario also proves *what* was written: the greeting is always exactly [GREETING], a username-password
 * packet appears only after the server selected method `02`, and a credential the wire cannot carry (empty or over
 * 255 UTF-8 bytes) is refused before a socket even exists. Successes are cached for 60 s per
 * network+gateway+port+fingerprint; the key never holds a raw secret.
 */
class Socks5AuthenticatorTest {

    private companion object {
        /** The one greeting: version 5, method selection, offering no-auth and username-password. */
        val GREETING = byteArrayOf(0x05, 0x02, 0x00, 0x02)
        const val LOOPBACK = "127.0.0.1"
        const val USER = "alice"
        const val PASS = "s3cr3t-pass"
        const val PORT = 2080
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
                // The client may close first; the assertions live in the tests.
            }
        }
        return server.localPort to done
    }

    /**
     * Accepts connections until [ServerSocket.close], handing each to [handle]; returns the port and how many
     * connections actually arrived (the proof that a cache hit or a rejected credential never touched the wire).
     */
    private fun acceptLoop(handle: (Socket) -> Unit): Pair<Int, AtomicInteger> {
        val server = ServerSocket(0, 50, InetAddress.getLoopbackAddress())
        listening += server
        val connections = AtomicInteger()
        executor.execute {
            while (true) {
                val socket = try {
                    server.accept()
                } catch (_: Throwable) {
                    return@execute // closed in tearDown
                }
                try {
                    connections.incrementAndGet()
                    socket.use(handle)
                } catch (_: Throwable) {
                    // Assertions live in the tests.
                }
            }
        }
        return server.localPort to connections
    }

    /** Reads exactly [count] bytes, or as many as arrive before EOF. */
    private fun readBytes(socket: Socket, count: Int): ByteArray {
        socket.soTimeout = 2000
        val input: InputStream = socket.getInputStream()
        val collected = ByteArray(count)
        var offset = 0
        while (offset < count) {
            val read = try {
                input.read(collected, offset, count - offset)
            } catch (_: SocketTimeoutException) {
                break
            }
            if (read < 0) break
            offset += read
        }
        return collected.copyOf(offset)
    }

    /** Everything after the last expected byte until the client closes its side. */
    private fun readRemainder(socket: Socket): ByteArray {
        socket.soTimeout = 2000
        val input: InputStream = socket.getInputStream()
        val collected = java.io.ByteArrayOutputStream()
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

    private fun bytes(vararg values: Int) = ByteArray(values.size) { values[it].toByte() }

    /** A cache with a clock the test drives; no shared state between tests. */
    private fun cacheAt(now: () -> Long = { 0L }) = Socks5AuthCache(nowMs = now)

    private fun authenticator(cache: Socks5AuthCache = cacheAt(), networkHandle: Long = 1L) =
        Socks5Authenticator(networkHandle = networkHandle, cache = cache)

    /** A running username-password exchange: the port to dial, its handler completion, and the two byte captures. */
    private class UserPassExchange(
        val port: Int,
        val done: Future<*>,
        val greeting: AtomicReference<ByteArray>,
        val authPacket: AtomicReference<ByteArray>,
    )

    /**
     * The full username-password exchange: greeting, `05 02`, then the RFC1929 packet, answered with
     * [authStatus]. Records the greeting and the credential packet separately.
     */
    private fun userPassServer(authStatus: Int): UserPassExchange {
        val greeting = AtomicReference<ByteArray>()
        val authPacket = AtomicReference<ByteArray>()
        val (port, done) = acceptOnce { socket ->
            greeting.set(readBytes(socket, GREETING.size))
            socket.getOutputStream().apply {
                write(bytes(0x05, 0x02))
                flush()
            }
            authPacket.set(readBytes(socket, 3 + USER.toByteArray().size + PASS.toByteArray().size))
            socket.getOutputStream().apply {
                write(bytes(0x01, authStatus))
                flush()
            }
            readRemainder(socket)
        }
        // No await here: the client has not dialled yet, so the handler is still blocked in accept() and an
        // await would always time out. The callers await after authenticate() has driven the exchange.
        return UserPassExchange(port, done, greeting, authPacket)
    }

    private fun expectedAuthPacket() = bytes(
        0x01,
        USER.toByteArray().size,
        *USER.toByteArray().map { it.toInt() and 0xFF }.toIntArray(),
        PASS.toByteArray().size,
        *PASS.toByteArray().map { it.toInt() and 0xFF }.toIntArray(),
    )

    // ----------------------------------------------------------------------------------------------------- success

    @Test
    fun noAuthReplyNeedsNoCredentialPacket() {
        val received = AtomicReference<ByteArray>()
        val (port, done) = acceptOnce { socket ->
            val greeting = readBytes(socket, GREETING.size)
            socket.getOutputStream().apply {
                write(bytes(0x05, 0x00))
                flush()
            }
            // Everything the client sends after the greeting: there must be nothing.
            received.set(greeting + readRemainder(socket))
        }

        val result = authenticator().authenticate(LOOPBACK, port, USER, PASS, SocketFactory.getDefault())
        done.get(5, TimeUnit.SECONDS)

        assertEquals(Socks5AuthResult.Success(0x00), result)
        // `05 00`: no RFC1929 packet at all, the credential never leaves the process.
        assertArrayEquals(GREETING, received.get())
    }

    @Test
    fun userPassReplyIsCompletedWithRfc1929() {
        val exchange = userPassServer(authStatus = 0x00)

        val result = authenticator().authenticate(LOOPBACK, exchange.port, USER, PASS, SocketFactory.getDefault())
        exchange.done.get(5, TimeUnit.SECONDS)

        assertEquals(Socks5AuthResult.Success(0x02), result)
        assertArrayEquals(GREETING, exchange.greeting.get())
        // 01 | ulen | user | plen | pass, verbatim, exactly once.
        assertArrayEquals(expectedAuthPacket(), exchange.authPacket.get())
    }

    @Test
    fun aSuccessfulExchangeIsCachedAndSkipsTheSocketForSixtySeconds() {
        var now = 0L
        val cache = cacheAt { now }
        val (port, connections) = acceptLoop { socket ->
            readBytes(socket, GREETING.size)
            socket.getOutputStream().apply {
                write(bytes(0x05, 0x02))
                flush()
            }
            readBytes(socket, 3 + USER.toByteArray().size + PASS.toByteArray().size)
            socket.getOutputStream().apply {
                write(bytes(0x01, 0x00))
                flush()
            }
            readRemainder(socket)
        }
        val auth = authenticator(cache = cache, networkHandle = 42L)

        assertEquals(
            Socks5AuthResult.Success(0x02),
            auth.authenticate(LOOPBACK, port, USER, PASS, SocketFactory.getDefault()),
        )
        assertEquals(1, connections.get())

        // Inside the window the cache answers; the server sees no second connection.
        now = 59_999L
        assertEquals(
            Socks5AuthResult.Success(0x02),
            auth.authenticate(LOOPBACK, port, USER, PASS, SocketFactory.getDefault()),
        )
        assertEquals(1, connections.get())

        // At exactly 60 s the entry is stale: the socket is used again.
        now = 60_000L
        assertEquals(
            Socks5AuthResult.Success(0x02),
            auth.authenticate(LOOPBACK, port, USER, PASS, SocketFactory.getDefault()),
        )
        assertEquals(2, connections.get())
    }

    // ---------------------------------------------------------------------------------------------------- failures

    @Test
    fun rejectedCredentialsAreAuthFailed() {
        // RFC1929: status 00 is the only success; every other status byte is a refusal.
        for (status in listOf(0x01, 0x02, 0xFE)) {
            val exchange = userPassServer(authStatus = status)
            val result = authenticator().authenticate(LOOPBACK, exchange.port, USER, PASS, SocketFactory.getDefault())
            exchange.done.get(5, TimeUnit.SECONDS)
            assertEquals(
                "status 0x${status.toString(16)}",
                Socks5AuthResult.Failure(Socks5AuthFailure.AUTH_FAILED),
                result,
            )
            // The refusal still shows a well-formed exchange happened.
            assertArrayEquals(GREETING, exchange.greeting.get())
            assertArrayEquals(expectedAuthPacket(), exchange.authPacket.get())
        }
    }

    @Test
    fun aFailedAuthIsNeverCached() {
        val cache = cacheAt()
        val (port, connections) = acceptLoop { socket ->
            readBytes(socket, GREETING.size)
            socket.getOutputStream().apply {
                write(bytes(0x05, 0x02))
                flush()
            }
            readBytes(socket, 3 + USER.toByteArray().size + PASS.toByteArray().size)
            socket.getOutputStream().apply {
                write(bytes(0x01, 0x01))
                flush()
            }
            readRemainder(socket)
        }
        val auth = authenticator(cache = cache)

        assertEquals(
            Socks5AuthResult.Failure(Socks5AuthFailure.AUTH_FAILED),
            auth.authenticate(LOOPBACK, port, USER, PASS, SocketFactory.getDefault()),
        )
        assertEquals(
            Socks5AuthResult.Failure(Socks5AuthFailure.AUTH_FAILED),
            auth.authenticate(LOOPBACK, port, USER, PASS, SocketFactory.getDefault()),
        )
        // Two attempts, two connections, and no success in the cache.
        assertEquals(2, connections.get())
        assertNull(
            cache.methodIfFresh(
                Socks5AuthCache.key(1L, LOOPBACK, port, Socks5AuthCache.credentialFingerprint(USER, PASS)),
            ),
        )
    }

    @Test
    fun noAcceptableMethodsIsUnsupportedAuth() {
        val received = AtomicReference<ByteArray>()
        val (port, done) = acceptOnce { socket ->
            val greeting = readBytes(socket, GREETING.size)
            socket.getOutputStream().apply {
                write(bytes(0x05, 0xFF))
                flush()
            }
            received.set(greeting + readRemainder(socket))
        }

        val result = authenticator().authenticate(LOOPBACK, port, USER, PASS, SocketFactory.getDefault())
        done.get(5, TimeUnit.SECONDS)

        assertEquals(Socks5AuthResult.Failure(Socks5AuthFailure.UNSUPPORTED_AUTH), result)
        // FF is a refusal of the greeting, never a wrong password: no credential was offered.
        assertArrayEquals(GREETING, received.get())
    }

    @Test
    fun aMethodTheGreetingNeverOfferedIsAProtocolFailure() {
        // The greeting offered 00 and 02 only, so `01` (GSSAPI) is not a valid selection.
        val (port, _) = acceptOnce { socket ->
            readBytes(socket, GREETING.size)
            socket.getOutputStream().apply {
                write(bytes(0x05, 0x01))
                flush()
            }
            readRemainder(socket)
        }
        assertEquals(
            Socks5AuthResult.Failure(Socks5AuthFailure.PROTOCOL),
            authenticator().authenticate(LOOPBACK, port, USER, PASS, SocketFactory.getDefault()),
        )
    }

    @Test
    fun wrongVersionIsNotSocks5() {
        val (port, _) = acceptOnce { socket ->
            readBytes(socket, GREETING.size)
            socket.getOutputStream().apply {
                write(bytes(0x04, 0x00))
                flush()
            }
            readRemainder(socket)
        }
        assertEquals(
            Socks5AuthResult.Failure(Socks5AuthFailure.NOT_SOCKS5),
            authenticator().authenticate(LOOPBACK, port, USER, PASS, SocketFactory.getDefault()),
        )
    }

    @Test
    fun closeRightAfterTheGreetingIsAProtocolFailure() {
        val (port, _) = acceptOnce { socket -> readBytes(socket, GREETING.size) }
        assertEquals(
            Socks5AuthResult.Failure(Socks5AuthFailure.PROTOCOL),
            authenticator().authenticate(LOOPBACK, port, USER, PASS, SocketFactory.getDefault()),
        )
    }

    @Test
    fun truncatedReplyIsAProtocolFailure() {
        // The server answers `05` only and ends the connection: a truncated reply, not a silent one.
        val (port, _) = acceptOnce { socket ->
            readBytes(socket, GREETING.size)
            socket.getOutputStream().apply {
                write(bytes(0x05))
                flush()
            }
        }
        assertEquals(
            Socks5AuthResult.Failure(Socks5AuthFailure.PROTOCOL),
            authenticator().authenticate(LOOPBACK, port, USER, PASS, SocketFactory.getDefault()),
        )
    }

    @Test
    fun silentServerTimesOut() {
        val (port, _) = acceptOnce { socket ->
            readBytes(socket, GREETING.size)
            readRemainder(socket) // never answers: the client's read timeout must fire.
        }
        assertEquals(
            Socks5AuthResult.Failure(Socks5AuthFailure.TIMEOUT),
            authenticator().authenticate(LOOPBACK, port, USER, PASS, SocketFactory.getDefault()),
        )
    }

    @Test
    fun closedPortIsAnIoFailure() {
        val reservation = ServerSocket(0, 1, InetAddress.getLoopbackAddress())
        val port = reservation.localPort
        reservation.close()
        assertEquals(
            Socks5AuthResult.Failure(Socks5AuthFailure.IO),
            authenticator().authenticate(LOOPBACK, port, USER, PASS, SocketFactory.getDefault()),
        )
    }

    // ----------------------------------------------------------------------------------------------- credentials

    @Test
    fun oversizeUtf8CredentialsFailBeforeWrite() {
        // 128 × "é" = 256 UTF-8 bytes: one byte more than RFC1929's single-byte length can carry.
        val oversizeUser = "é".repeat(128)
        assertEquals(256, oversizeUser.toByteArray().size)

        val server = ServerSocket(0, 1, InetAddress.getLoopbackAddress())
        listening += server
        val port = server.localPort
        server.soTimeout = 200

        val result = authenticator().authenticate(LOOPBACK, port, oversizeUser, PASS, SocketFactory.getDefault())

        assertEquals(Socks5AuthResult.Failure(Socks5AuthFailure.INVALID_CREDENTIALS), result)
        // Rejected before any socket work: the server never saw a connection, let alone a greeting.
        assertThrows(SocketTimeoutException::class.java) { server.accept() }
    }

    @Test
    fun anEmptyOrOversizePasswordIsRejectedBeforeWrite() {
        val server = ServerSocket(0, 1, InetAddress.getLoopbackAddress())
        listening += server
        val port = server.localPort
        server.soTimeout = 200

        assertEquals(
            Socks5AuthResult.Failure(Socks5AuthFailure.INVALID_CREDENTIALS),
            authenticator().authenticate(LOOPBACK, port, USER, "", SocketFactory.getDefault()),
        )
        assertEquals(
            Socks5AuthResult.Failure(Socks5AuthFailure.INVALID_CREDENTIALS),
            authenticator().authenticate(
                LOOPBACK, port, USER, "あ".repeat(86), SocketFactory.getDefault(), // 258 UTF-8 bytes
            ),
        )
        assertThrows(SocketTimeoutException::class.java) { server.accept() }
    }

    @Test
    fun credentialsOfExactly255Utf8BytesAreAccepted() {
        // The boundary the wire can carry: 255 bytes in, 1-byte length stays correct.
        val user255 = "a".repeat(255)
        assertEquals(255, user255.toByteArray().size)

        val greeting = AtomicReference<ByteArray>()
        val (port, done) = acceptOnce { socket ->
            greeting.set(readBytes(socket, GREETING.size))
            socket.getOutputStream().apply {
                write(bytes(0x05, 0x02))
                flush()
            }
            readBytes(socket, 3 + user255.toByteArray().size + PASS.toByteArray().size)
            socket.getOutputStream().apply {
                write(bytes(0x01, 0x00))
                flush()
            }
            readRemainder(socket)
        }

        val result = Socks5Authenticator(networkHandle = 1L, cache = cacheAt())
            .authenticate(LOOPBACK, port, user255, PASS, SocketFactory.getDefault())
        done.get(5, TimeUnit.SECONDS)

        assertEquals(Socks5AuthResult.Success(0x02), result)
        assertArrayEquals(GREETING, greeting.get())
    }

    // ------------------------------------------------------------------------------------------------------ cache

    @Test
    fun theSuccessCacheIsFreshFor59999msAndStaleAt60000ms() {
        var now = 0L
        val cache = cacheAt { now }
        val key = Socks5AuthCache.key(7L, "192.168.137.1", PORT, Socks5AuthCache.credentialFingerprint(USER, PASS))

        assertNull(cache.methodIfFresh(key))
        cache.remember(key, 0x02)

        now = 59_999L
        assertEquals(0x02, cache.methodIfFresh(key))

        now = 60_000L
        assertNull(cache.methodIfFresh(key))
    }

    @Test
    fun theCacheKeyNeverCarriesTheRawCredential() {
        val fingerprint = Socks5AuthCache.credentialFingerprint(USER, PASS)
        val key = Socks5AuthCache.key(7L, "192.168.137.1", PORT, fingerprint)

        assertFalse("username leaked into the key", key.contains(USER))
        assertFalse("password leaked into the key", key.contains(PASS))
        // The fingerprint is a digest: deterministic for the same pair, different for any change.
        assertEquals(fingerprint, Socks5AuthCache.credentialFingerprint(USER, PASS))
        assertNotEquals(fingerprint, Socks5AuthCache.credentialFingerprint(USER, "$PASS!"))
        assertNotEquals(fingerprint, Socks5AuthCache.credentialFingerprint("bob", PASS))

        // The key still separates the parts a pairing change must not alias.
        assertNotEquals(
            Socks5AuthCache.key(7L, "192.168.137.1", PORT, fingerprint),
            Socks5AuthCache.key(7L, "192.168.137.1", 1080, fingerprint),
        )
        assertNotEquals(
            Socks5AuthCache.key(7L, "192.168.137.1", PORT, fingerprint),
            Socks5AuthCache.key(8L, "192.168.137.1", PORT, fingerprint),
        )
    }

    @Test
    fun aCacheHitReturnsTheStoredMethod() {
        val cache = cacheAt()
        val key = Socks5AuthCache.key(7L, "192.168.137.1", PORT, Socks5AuthCache.credentialFingerprint(USER, PASS))
        cache.remember(key, 0x00)
        assertEquals(0x00, cache.methodIfFresh(key))
        cache.clear()
        assertNull(cache.methodIfFresh(key))
    }

    // ----------------------------------------------------------------------------------------------- negative control

    /** Reads a dynamic-length RFC1929 username/password request; returns (username, password) as Strings. */
    private fun readRfc1929Credentials(socket: Socket): Pair<String, String> {
        val input = socket.getInputStream()
        fun readFully(n: Int): ByteArray {
            val buf = ByteArray(n)
            var off = 0
            while (off < n) {
                val r = input.read(buf, off, n - off)
                if (r < 0) break
                off += r
            }
            return buf
        }
        input.read() // version (0x01)
        val userLen = input.read()
        val user = readFully(userLen)
        val passLen = input.read()
        val pass = readFully(passLen)
        return String(user, Charsets.UTF_8) to String(pass, Charsets.UTF_8)
    }

    @Test
    fun negativeControlAcceptsAServerThatRefusesNothing() {
        val (port, done) = acceptOnce { socket ->
            readBytes(socket, GREETING.size)
            socket.getOutputStream().apply { write(bytes(0x05, 0x02)); flush() }
            readRfc1929Credentials(socket)
            socket.getOutputStream().apply { write(bytes(0x01, 0x00)); flush() }
            readRemainder(socket)
        }
        val result = authenticator().negativeControl(LOOPBACK, port, SocketFactory.getDefault())
        done.get(5, TimeUnit.SECONDS)
        assertEquals(Socks5AuthResult.Success(0x02), result)
    }

    @Test
    fun negativeControlRefusesAServerThatOnlyKnowsOneCredential() {
        val (port, done) = acceptOnce { socket ->
            readBytes(socket, GREETING.size)
            socket.getOutputStream().apply { write(bytes(0x05, 0x02)); flush() }
            readRfc1929Credentials(socket)
            socket.getOutputStream().apply { write(bytes(0x01, 0x01)); flush() }
            readRemainder(socket)
        }
        val result = authenticator().negativeControl(LOOPBACK, port, SocketFactory.getDefault())
        done.get(5, TimeUnit.SECONDS)
        assertEquals(Socks5AuthResult.Failure(Socks5AuthFailure.AUTH_FAILED), result)
    }

    @Test
    fun negativeControlReportsANoAuthServerAsNotEnforcingCredentials() {
        val received = AtomicReference<ByteArray>()
        val (port, done) = acceptOnce { socket ->
            val greeting = readBytes(socket, GREETING.size)
            socket.getOutputStream().apply { write(bytes(0x05, 0x00)); flush() }
            received.set(greeting + readRemainder(socket))
        }
        val result = authenticator().negativeControl(LOOPBACK, port, SocketFactory.getDefault())
        done.get(5, TimeUnit.SECONDS)
        // A no-auth server (method 0x00) succeeds without a credential: the caller maps any Success to
        // CREDENTIALS_NOT_ENFORCED, and no credential ever left the process.
        assertEquals(Socks5AuthResult.Success(0x00), result)
        assertArrayEquals(GREETING, received.get())
    }

    @Test
    fun negativeControlSendsADifferentRandomCredentialEachCall() {
        val firstUser = AtomicReference<String>()
        val firstPass = AtomicReference<String>()
        val secondUser = AtomicReference<String>()
        val secondPass = AtomicReference<String>()

        val auth = authenticator()

        val (port1, done1) = acceptOnce { socket ->
            readBytes(socket, GREETING.size)
            socket.getOutputStream().apply { write(bytes(0x05, 0x02)); flush() }
            val (u, p) = readRfc1929Credentials(socket)
            firstUser.set(u)
            firstPass.set(p)
            socket.getOutputStream().apply { write(bytes(0x01, 0x01)); flush() }
            readRemainder(socket)
        }
        auth.negativeControl(LOOPBACK, port1, SocketFactory.getDefault())
        done1.get(5, TimeUnit.SECONDS)

        val (port2, done2) = acceptOnce { socket ->
            readBytes(socket, GREETING.size)
            socket.getOutputStream().apply { write(bytes(0x05, 0x02)); flush() }
            val (u, p) = readRfc1929Credentials(socket)
            secondUser.set(u)
            secondPass.set(p)
            socket.getOutputStream().apply { write(bytes(0x01, 0x01)); flush() }
            readRemainder(socket)
        }
        auth.negativeControl(LOOPBACK, port2, SocketFactory.getDefault())
        done2.get(5, TimeUnit.SECONDS)

        // The random credential is 8 bytes of user (16 hex chars) and 16 bytes of password (32 hex chars).
        assertEquals(16, firstUser.get().length)
        assertEquals(32, firstPass.get().length)
        assertNotEquals(firstUser.get(), secondUser.get())
        assertNotEquals(firstPass.get(), secondPass.get())
    }
}
