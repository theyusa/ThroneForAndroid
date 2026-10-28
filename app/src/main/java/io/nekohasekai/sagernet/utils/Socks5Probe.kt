package io.nekohasekai.sagernet.utils

import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import javax.net.SocketFactory

/** Why a [Socks5Probe] run did not produce a [Socks5ProbeResult.Reply]. */
enum class Socks5ProbeFailure {
    /** The peer did not speak SOCKS5 at all (HTTP, SOCKS4, a wrong version byte). */
    NOT_SOCKS5,

    /** SOCKS5-shaped but malformed: an empty or one-byte reply, an EOF, or a method never offered. */
    PROTOCOL,

    /** No reply within [Socks5Probe]'s read timeout. */
    TIMEOUT,

    /** Connect or read failed: refused, unreachable, reset. */
    IO,
}

/** The classified outcome of one probe; neither branch carries a credential. */
sealed class Socks5ProbeResult {

    /** The server selected [method]: `00` no-auth, `02` username-password, `FF` no acceptable method. */
    data class Reply(val method: Int) : Socks5ProbeResult()

    data class Failure(val reason: Socks5ProbeFailure) : Socks5ProbeResult()
}

/**
 * Anonymous SOCKS5 discovery: does a gateway speak SOCKS5 at all, and which method would it pick?
 *
 * The socket comes from the caller as a [SocketFactory] (Android passes `Network.socketFactory`, the JVM tests a fake
 * server), is connected with the connect timeout, then read with the read timeout and closed. Exactly one greeting is
 * written: the four bytes `05 02 00 02` (version 5, method selection, offering no-auth and username-password). No
 * branch of this class ever writes a username or a password; a `02` reply only reports what the server would require
 * and leaves the decision to the caller. Reply classification (profile write, pairing, retries) is
 * [HotspotGatewayPolicy]'s job.
 */
class Socks5Probe(
    private val connectTimeoutMs: Int = 1500,
    private val readTimeoutMs: Int = 1500,
) {

    fun probe(host: String, port: Int, socketFactory: SocketFactory): Socks5ProbeResult {
        val socket = try {
            socketFactory.createSocket().apply {
                connect(InetSocketAddress(host, port), connectTimeoutMs)
                soTimeout = readTimeoutMs
            }
        } catch (e: SocketTimeoutException) {
            return Socks5ProbeResult.Failure(Socks5ProbeFailure.TIMEOUT)
        } catch (e: IllegalArgumentException) {
            // An unusable host/port never becomes a connection attempt.
            return Socks5ProbeResult.Failure(Socks5ProbeFailure.PROTOCOL)
        } catch (e: IOException) {
            return Socks5ProbeResult.Failure(Socks5ProbeFailure.IO)
        }

        return socket.use { s ->
            try {
                s.getOutputStream().apply {
                    write(GREETING)
                    flush()
                }
                val input = s.getInputStream()
                val version = input.read()
                if (version < 0) {
                    // Closed before answering: not a SOCKS5 server.
                    return@use Socks5ProbeResult.Failure(Socks5ProbeFailure.PROTOCOL)
                }
                if (version != SOCKS5_VERSION) {
                    return@use Socks5ProbeResult.Failure(Socks5ProbeFailure.NOT_SOCKS5)
                }
                val method = input.read()
                if (method < 0) {
                    // Version only: the reply is truncated.
                    return@use Socks5ProbeResult.Failure(Socks5ProbeFailure.PROTOCOL)
                }
                when (method) {
                    METHOD_NO_AUTH, METHOD_USER_PASS, METHOD_NO_ACCEPTABLE ->
                        Socks5ProbeResult.Reply(method)

                    // A method the greeting never offered (it offered 00 and 02).
                    else -> Socks5ProbeResult.Failure(Socks5ProbeFailure.PROTOCOL)
                }
            } catch (e: SocketTimeoutException) {
                Socks5ProbeResult.Failure(Socks5ProbeFailure.TIMEOUT)
            } catch (e: IOException) {
                Socks5ProbeResult.Failure(Socks5ProbeFailure.IO)
            }
        }
    }

    private companion object {
        /** `05 02 00 02`: version, method selection, methods 00 and 02. The only bytes this class writes. */
        val GREETING = byteArrayOf(0x05, 0x02, 0x00, 0x02)
        const val SOCKS5_VERSION = 0x05
        const val METHOD_NO_AUTH = 0x00
        const val METHOD_USER_PASS = 0x02
        const val METHOD_NO_ACCEPTABLE = 0xFF
    }
}
