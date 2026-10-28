package io.nekohasekai.sagernet.utils

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Why a scan ended without a verified port ([DiscoveryResult.Failure]). */
enum class DiscoveryFailure {

    /** No candidate port answered the greeting with `00` or `02`. */
    NOT_FOUND,

    /** No port answered `00`/`02`, but at least one answered `FF` (no acceptable method). */
    UNSUPPORTED_AUTH,
}

/** The outcome of one bounded parallel scan of the gateway's ports. */
sealed class DiscoveryResult {

    /**
     * The gateway spoke SOCKS5 at [port] and picked [method]: `00` no-auth or `02` username-password. Both are a
     * verification - the pairing (and whether `02` can be completed with credentials) stays the manager's decision.
     */
    data class Success(
        @JvmField val port: Int,
        @JvmField val method: Int,
    ) : DiscoveryResult()

    data class Failure(@JvmField val reason: DiscoveryFailure) : DiscoveryResult()
}

/**
 * The bounded parallel port discovery of the hotspot gateway (design §5.3).
 *
 * [discover] is the only entry point. It probes the preferred ports first (the paired and the last-verified one,
 * passed by the caller), then the fixed candidate list, with at most [CONCURRENCY] probes in flight. The probe is
 * the caller's `suspend (Int) -> Socks5ProbeResult` lambda - the discovery itself is protocol-free, never touches a
 * socket, never sends and never logs a credential - and it is called at most once per candidate: invalid ports are
 * dropped before any call and duplicates collapse to one. A `00`/`02` reply wins and cancels the remaining probes
 * (structured concurrency); an `FF` reply only marks the port unusable and the scan continues; any failure class
 * keeps the scan going. No success anywhere ends as [DiscoveryFailure.UNSUPPORTED_AUTH] when some port answered
 * `FF`, and as [DiscoveryFailure.NOT_FOUND] otherwise.
 *
 * The whole scan runs inside the caller's coroutine: when the caller is cancelled (a newer check superseded this
 * one, the service monitor stopped), the in-flight probes are cancelled with it and no result is ever delivered -
 * a stale generation cannot reach the manager's write path.
 */
object HotspotPortDiscovery {

    /** At most four probes are in flight; further ports wait for a free worker. */
    const val CONCURRENCY = 4

    /** The fixed candidate ports tried after the preferred ones (design §5.3 order 2). */
    val CANDIDATE_PORTS = listOf(2080, 1080, 7890, 7891, 8080, 8888, 10808, 10809)

    private const val MIN_PORT = 1
    private const val MAX_PORT = 65535

    private const val METHOD_NO_AUTH = 0x00
    private const val METHOD_USER_PASS = 0x02
    private const val METHOD_NO_ACCEPTABLE = 0xFF

    /**
     * Scans [preferredPorts] first (in input order) and then [candidates], returning the first `00`/`02` reply.
     * [probe] is invoked at most once per planned port and may be invoked concurrently - it must be safe for that.
     */
    suspend fun discover(
        preferredPorts: List<Int>,
        candidates: List<Int>,
        probe: suspend (Int) -> Socks5ProbeResult,
    ): DiscoveryResult = coroutineScope {
        val plan = plan(preferredPorts, candidates)
        if (plan.isEmpty()) return@coroutineScope DiscoveryResult.Failure(DiscoveryFailure.NOT_FOUND)

        val remaining = Channel<Int>(Channel.UNLIMITED)
        launch {
            for (port in plan) remaining.send(port)
            remaining.close()
        }

        val scopeJob = coroutineContext[Job]!!
        val guard = Mutex()
        var success: DiscoveryResult.Success? = null
        var unsupportedAuth = false

        val workers = List(minOf(CONCURRENCY, plan.size)) {
            launch {
                for (port in remaining) {
                    val result = try {
                        probe(port)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Throwable) {
                        // An unexpected error in the probe is one failed port, never a failed scan.
                        Socks5ProbeResult.Failure(Socks5ProbeFailure.IO)
                    }
                    when (result) {
                        is Socks5ProbeResult.Reply -> when (result.method) {
                            METHOD_NO_AUTH, METHOD_USER_PASS -> {
                                // The first verified port wins; its result is recorded, the rest of the scan is
                                // cancelled with the scope's children (this worker included) and the loop ends.
                                guard.withLock {
                                    if (success == null) success = DiscoveryResult.Success(port, result.method)
                                }
                                scopeJob.cancelChildren()
                                return@launch
                            }

                            // `FF` refuses every offered method on this port only: mark it and keep scanning.
                            METHOD_NO_ACCEPTABLE -> guard.withLock { unsupportedAuth = true }

                            else -> Unit // A method the greeting never offered; the probe classified it already.
                        }

                        is Socks5ProbeResult.Failure -> Unit // Keep scanning.
                    }
                }
            }
        }
        workers.joinAll()

        success?.let { return@coroutineScope it }
        return@coroutineScope if (unsupportedAuth) {
            DiscoveryResult.Failure(DiscoveryFailure.UNSUPPORTED_AUTH)
        } else {
            DiscoveryResult.Failure(DiscoveryFailure.NOT_FOUND)
        }
    }

    /**
     * The scan order: [preferredPorts] in input order, then [candidates] not seen yet. Ports outside 1..65535 are
     * dropped without a probe call, and a port listed twice is probed exactly once.
     */
    private fun plan(preferredPorts: List<Int>, candidates: List<Int>): List<Int> =
        (preferredPorts + candidates).filter { it in MIN_PORT..MAX_PORT }.distinct()
}
