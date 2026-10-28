package io.nekohasekai.sagernet.utils

import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

/**
 * The bounded parallel port discovery on the JVM: preferred ports first, duplicates collapsed, invalid ports never
 * probed, at most four probes in flight, `00`/`02` as the success, `FF` as a marked-but-continued port, and the
 * stale-generation rule - a superseded scan is cancelled and can never deliver a result.
 */
class HotspotPortDiscoveryTest {

    @Test
    fun preferredPortsComeFirstAndDuplicatesAndInvalidPortsCollapse() = runBlocking {
        val probed = mutableListOf<Int>()
        val result = HotspotPortDiscovery.discover(
            preferredPorts = listOf(8080, 2080, 65536, 0, 2080, -1),
            candidates = listOf(1080, 8080, 7891),
        ) { port ->
            probed += port
            delay(5) // Let the workers actually overlap: the order must survive that.
            Socks5ProbeResult.Reply(0xFF)
        }

        // 8080 before the candidates; 2080 probed once; 65536, 0 and -1 dropped without a probe call.
        assertEquals(listOf(8080, 2080, 1080, 7891), probed)
        assertEquals(DiscoveryResult.Failure(DiscoveryFailure.UNSUPPORTED_AUTH), result)
    }

    @Test
    fun aSuccessCancelsTheOtherProbes() = runBlocking {
        val started = mutableListOf<Int>()
        val cancelled = mutableListOf<Int>()
        val result = HotspotPortDiscovery.discover(
            preferredPorts = listOf(2080, 1080),
            candidates = listOf(7890, 8080, 8888),
        ) { port ->
            started += port
            if (port == 1080) {
                Socks5ProbeResult.Reply(0x02)
            } else {
                try {
                    awaitCancellation()
                } finally {
                    cancelled += port
                }
            }
        }

        // The second preferred port answered 02: that is the answer, and the ports waiting behind it (8888) are
        // never probed while the ones already in flight (2080) are cancelled - the finally ran for them.
        assertEquals(DiscoveryResult.Success(1080, 0x02), result)
        assertEquals(listOf(2080, 1080), started)
        assertTrue(2080 in cancelled)
    }

    @Test
    fun atMostFourProbesRunAtTheSameTime() = runBlocking {
        val active = AtomicInteger()
        val maxActive = AtomicInteger()
        val result = HotspotPortDiscovery.discover(
            preferredPorts = emptyList(),
            candidates = listOf(2080, 1080, 7890, 7891, 8080, 8888, 10808, 10809),
        ) { port ->
            val current = active.incrementAndGet()
            maxActive.accumulateAndGet(current) { before, now -> maxOf(before, now) }
            try {
                delay(20) // Every probe overlaps with the others: the counter sees the real peak.
            } finally {
                active.decrementAndGet()
            }
            Socks5ProbeResult.Reply(0xFF)
        }

        // Eight ports, four workers: every worker holds a probe at the same time, but never a fifth one.
        assertEquals(DiscoveryResult.Failure(DiscoveryFailure.UNSUPPORTED_AUTH), result)
        assertEquals(HotspotPortDiscovery.CONCURRENCY, maxActive.get())
    }

    @Test
    fun everyPortFailingYieldsNotFound() = runBlocking {
        val result = HotspotPortDiscovery.discover(
            preferredPorts = listOf(2080),
            candidates = listOf(1080, 7890),
        ) { Socks5ProbeResult.Failure(Socks5ProbeFailure.TIMEOUT) }

        assertEquals(DiscoveryResult.Failure(DiscoveryFailure.NOT_FOUND), result)
    }

    @Test
    fun unsupportedAuthIsOnlyForAScanWhereNoPortAnsweredButOneRefused() = runBlocking {
        // Mixed failure classes plus one FF: the FF wins the classification.
        val withFF = HotspotPortDiscovery.discover(
            preferredPorts = emptyList(),
            candidates = listOf(2080, 1080, 7890),
        ) { port ->
            if (port == 7890) Socks5ProbeResult.Reply(0xFF) else Socks5ProbeResult.Failure(Socks5ProbeFailure.IO)
        }
        assertEquals(DiscoveryResult.Failure(DiscoveryFailure.UNSUPPORTED_AUTH), withFF)

        // The same scan without the FF: plain not-found.
        val withoutFF = HotspotPortDiscovery.discover(
            preferredPorts = emptyList(),
            candidates = listOf(2080, 1080, 7890),
        ) { Socks5ProbeResult.Failure(Socks5ProbeFailure.IO) }
        assertEquals(DiscoveryResult.Failure(DiscoveryFailure.NOT_FOUND), withoutFF)
    }

    @Test
    fun aPortThatAnswersFFDoesNotStopTheScan() = runBlocking {
        // 2080 refuses every offered method; the next port speaks no-auth and must still be found.
        val result = HotspotPortDiscovery.discover(
            preferredPorts = listOf(2080),
            candidates = listOf(1080),
        ) { port ->
            if (port == 2080) Socks5ProbeResult.Reply(0xFF) else Socks5ProbeResult.Reply(0x00)
        }

        assertEquals(DiscoveryResult.Success(1080, 0x00), result)
    }

    @Test
    fun anEmptyOrAllInvalidPlanFailsWithoutCallingTheProbe() = runBlocking {
        var called = false
        val result = HotspotPortDiscovery.discover(
            preferredPorts = listOf(0, 65536, -1),
            candidates = emptyList(),
        ) { port ->
            called = true
            Socks5ProbeResult.Reply(port)
        }

        assertFalse(called)
        assertEquals(DiscoveryResult.Failure(DiscoveryFailure.NOT_FOUND), result)
    }

    @Test
    fun aStaleGenerationResultIsNeverDelivered() = runBlocking {
        var written: DiscoveryResult? = null
        val check = launch {
            // The manager cancels a superseded check job (a new pairing generation, the service monitor stopping);
            // with structured concurrency the scan dies with it, so its result can never reach the write path.
            written = HotspotPortDiscovery.discover(
                preferredPorts = emptyList(),
                candidates = listOf(2080, 1080),
            ) { awaitCancellation() }
        }
        // The newer generation supersedes the old one before it answered anything.
        check.cancelAndJoin()

        assertNull(written)
    }
}
