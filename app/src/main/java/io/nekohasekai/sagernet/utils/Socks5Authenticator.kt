package io.nekohasekai.sagernet.utils

import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.security.MessageDigest
import java.security.SecureRandom
import javax.net.SocketFactory

/** Why one start-time check did not produce a [Socks5AuthResult.Success]. */
enum class Socks5AuthFailure {

    /** RFC1929 answered with a status other than `00`: the username or the password was refused. */
    AUTH_FAILED,

    /** The server selected `FF`: neither no-auth nor username-password is acceptable to it. */
    UNSUPPORTED_AUTH,

    /** Not a SOCKS5 server at all: HTTP, SOCKS4 or another version byte. */
    NOT_SOCKS5,

    /** SOCKS5-shaped but malformed: an EOF, a truncated reply, or a method the greeting never offered. */
    PROTOCOL,

    /** No reply within the read timeout. */
    TIMEOUT,

    /** Connect or read failed: refused, unreachable, reset. */
    IO,

    /** The credential cannot be carried by RFC1929 (empty, or over 255 UTF-8 bytes); no socket was touched. */
    INVALID_CREDENTIALS,

    /** The server accepted a random, certainly-wrong credential: it does not enforce the configured one (not our X). */
    CREDENTIALS_NOT_ENFORCED,

    /** The start-time single probe found no verified SOCKS5 at the gateway (no pairing/gateway, or a non-FOUND result). */
    NOT_FOUND,
}

/** The classified outcome of one authentication; neither branch carries a credential back to the caller. */
sealed class Socks5AuthResult {

    /** The server settled on [method]: `00` needed no credential, `02` was completed with RFC1929. */
    data class Success(val method: Int) : Socks5AuthResult()

    data class Failure(val reason: Socks5AuthFailure) : Socks5AuthResult()
}

/**
 * The managed profile's start-time check was refused. [BaseService] turns [reason] into an EN/TR message through
 * `failRunner`; a protocol, timeout or IO problem is deliberately never worded as a wrong password.
 */
class HotspotGatewayAuthException(@JvmField val reason: Socks5AuthFailure) :
    Exception("hotspot gateway start check: $reason")

/**
 * The 60-second success cache, valid only inside the process that recorded the entry (`:bg` when the service runs).
 *
 * The key is network handle + gateway + port + a SHA-256 credential fingerprint: a changed pairing, network or
 * credential produces a different key, and a raw secret never reaches the map, the key or a log. Failures are never
 * remembered, so a fixed password is re-checked on the next start.
 */
class Socks5AuthCache(
    private val nowMs: () -> Long = { System.currentTimeMillis() },
    @JvmField val ttlMs: Long = TTL_MS,
) {

    private class Entry(@JvmField val method: Int, @JvmField val at: Long)

    private val entries = HashMap<String, Entry>()

    /** The method of a success recorded less than [ttlMs] ago, or null when there is none (or the clock moved back). */
    @Synchronized
    fun methodIfFresh(key: String): Int? {
        val entry = entries[key] ?: return null
        val age = nowMs() - entry.at
        if (age < 0L || age >= ttlMs) {
            entries.remove(key)
            return null
        }
        return entry.method
    }

    @Synchronized
    fun remember(key: String, method: Int) {
        entries[key] = Entry(method, nowMs())
    }

    @Synchronized
    fun clear() {
        entries.clear()
    }

    companion object {

        /** Auth successes are reusable for one minute (design §14). */
        const val TTL_MS = 60_000L

        /** network handle + gateway + port + credential fingerprint; no raw secret, ever. */
        fun key(networkHandle: Long, gateway: String, port: Int, credentialFingerprint: String): String =
            "$networkHandle|$gateway|$port|$credentialFingerprint"

        /** SHA-256 over `username \n password`, hex: deterministic, and reversible by nobody. */
        fun credentialFingerprint(username: String, password: String): String =
            sha256Hex("$username\n$password")
    }
}

/** The greeting bytes: version 5, method selection, offering no-auth and username-password. Never `05 01 02`. */
private val GREETING = byteArrayOf(0x05, 0x02, 0x00, 0x02)
private const val SOCKS5_VERSION = 0x05
private const val METHOD_NO_AUTH = 0x00
private const val METHOD_USER_PASS = 0x02
private const val METHOD_NO_ACCEPTABLE = 0xFF
private const val AUTH_VERSION = 0x01
private const val AUTH_OK = 0x00

/** SHA-256 of [text] as lowercase hex; the local digest helper (see also SubscriptionReconcile.contentKey). */
private fun sha256Hex(text: String): String {
    val bytes = MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8))
    val sb = StringBuilder(bytes.size * 2)
    for (b in bytes) {
        val v = b.toInt() and 0xFF
        sb.append("0123456789abcdef"[v shr 4]).append("0123456789abcdef"[v and 0xF])
    }
    return sb.toString()
}

/** A fresh random lowercase-hex string - the certainly-wrong credential for [Socks5Authenticator.negativeControl]. */
private fun randomHex(byteCount: Int): String {
    val bytes = ByteArray(byteCount).also { SecureRandom().nextBytes(it) }
    val sb = StringBuilder(bytes.size * 2)
    for (b in bytes) {
        val v = b.toInt() and 0xFF
        sb.append("0123456789abcdef"[v shr 4]).append("0123456789abcdef"[v and 0xF])
    }
    return sb.toString()
}

/**
 * The second, separate RFC1929 check that runs only when the managed profile is being started (design §5.2) - unlike
 * [Socks5Probe], this one may carry a credential, and only after the server selected method `02`.
 *
 * Flow: validate the credential lengths (1..255 UTF-8 bytes each) *before* any socket exists, answer the process-wide
 * success cache, then connect with the caller's [SocketFactory] (Android passes `Network.socketFactory`) using the
 * same 1500 ms timeouts as the probe. The greeting is again exactly `05 02 00 02`:
 *
 * - `05 00` -> success without writing a single credential byte;
 * - `05 02` -> RFC1929 (`01 ulen user plen pass`), `01 00` succeeds, any other status is [Socks5AuthFailure.AUTH_FAILED];
 * - `05 FF` -> [Socks5AuthFailure.UNSUPPORTED_AUTH]; another method -> [Socks5AuthFailure.PROTOCOL].
 *
 * Protocol, timeout and IO problems stay their own class: they are never reported as a wrong password. The only
 * mutable state is the [Socks5AuthCache], which lives in this process only.
 */
class Socks5Authenticator(
    private val networkHandle: Long = 0L,
    private val connectTimeoutMs: Int = 1500,
    private val readTimeoutMs: Int = 1500,
    private val cache: Socks5AuthCache = SHARED_CACHE,
) {

    fun authenticate(
        host: String,
        port: Int,
        username: String,
        password: String,
        socketFactory: SocketFactory,
    ): Socks5AuthResult {
        // RFC1929 lengths are single bytes: reject anything the wire cannot carry before a socket is created.
        val userBytes = username.toByteArray(Charsets.UTF_8)
        val passBytes = password.toByteArray(Charsets.UTF_8)
        if (userBytes.isEmpty() || userBytes.size > 255) {
            return Socks5AuthResult.Failure(Socks5AuthFailure.INVALID_CREDENTIALS)
        }
        if (passBytes.isEmpty() || passBytes.size > 255) {
            return Socks5AuthResult.Failure(Socks5AuthFailure.INVALID_CREDENTIALS)
        }

        val key = Socks5AuthCache.key(
            networkHandle = networkHandle,
            gateway = host,
            port = port,
            credentialFingerprint = Socks5AuthCache.credentialFingerprint(username, password),
        )
        cache.methodIfFresh(key)?.let { return Socks5AuthResult.Success(it) }

        val socket = try {
            socketFactory.createSocket().apply {
                connect(InetSocketAddress(host, port), connectTimeoutMs)
                soTimeout = readTimeoutMs
            }
        } catch (e: SocketTimeoutException) {
            return Socks5AuthResult.Failure(Socks5AuthFailure.TIMEOUT)
        } catch (e: IllegalArgumentException) {
            return Socks5AuthResult.Failure(Socks5AuthFailure.PROTOCOL)
        } catch (e: IOException) {
            return Socks5AuthResult.Failure(Socks5AuthFailure.IO)
        }

        return socket.use { s ->
            try {
                val input = s.getInputStream()
                s.getOutputStream().apply {
                    write(GREETING)
                    flush()
                }
                val version = input.read()
                if (version < 0) {
                    return@use Socks5AuthResult.Failure(Socks5AuthFailure.PROTOCOL)
                }
                if (version != SOCKS5_VERSION) {
                    return@use Socks5AuthResult.Failure(Socks5AuthFailure.NOT_SOCKS5)
                }
                val method = input.read()
                if (method < 0) {
                    return@use Socks5AuthResult.Failure(Socks5AuthFailure.PROTOCOL)
                }
                when (method) {
                    METHOD_NO_AUTH -> {
                        // No credential is written: the server does not want one.
                        cache.remember(key, method)
                        Socks5AuthResult.Success(method)
                    }

                    METHOD_USER_PASS -> {
                        // RFC1929: version, username length + bytes, password length + bytes - verbatim, no trim.
                        s.getOutputStream().apply {
                            write(AUTH_VERSION)
                            write(userBytes.size)
                            write(userBytes)
                            write(passBytes.size)
                            write(passBytes)
                            flush()
                        }
                        val authVersion = input.read()
                        if (authVersion < 0 || authVersion != AUTH_VERSION) {
                            Socks5AuthResult.Failure(Socks5AuthFailure.PROTOCOL)
                        } else {
                            val status = input.read()
                            when {
                                status < 0 -> Socks5AuthResult.Failure(Socks5AuthFailure.PROTOCOL)
                                status != AUTH_OK -> Socks5AuthResult.Failure(Socks5AuthFailure.AUTH_FAILED)
                                else -> {
                                    cache.remember(key, method)
                                    Socks5AuthResult.Success(method)
                                }
                            }
                        }
                    }

                    METHOD_NO_ACCEPTABLE -> Socks5AuthResult.Failure(Socks5AuthFailure.UNSUPPORTED_AUTH)

                    // A method the greeting never offered (00 and 02 only).
                    else -> Socks5AuthResult.Failure(Socks5AuthFailure.PROTOCOL)
                }
            } catch (e: SocketTimeoutException) {
                Socks5AuthResult.Failure(Socks5AuthFailure.TIMEOUT)
            } catch (e: IOException) {
                Socks5AuthResult.Failure(Socks5AuthFailure.IO)
            }
        }
    }

    /**
     * Negative control for [authenticate]: a fresh, certainly-wrong random credential must be refused with
     * [Socks5AuthFailure.AUTH_FAILED]. A server that instead accepts it ([Socks5AuthResult.Success]) does not enforce
     * the configured credential, so the caller refuses it as "not our X".
     */
    fun negativeControl(host: String, port: Int, socketFactory: SocketFactory): Socks5AuthResult =
        authenticate(
            host = host,
            port = port,
            username = randomHex(8),
            password = randomHex(16),
            socketFactory = socketFactory,
        )
}

/** The success cache shared by every [Socks5Authenticator] of this process; JVM tests pass their own instance. */
private val SHARED_CACHE = Socks5AuthCache()
