package com.kiodl.android.transfer

import com.kiodl.android.data.remote.TransferRateLimitException
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TransferAdaptiveCoordinatorTest {
    @Test
    fun increasesTransferWorkersAfterTwoSuccessfulChunks() = runBlocking {
        val coordinator = TransferAdaptiveCoordinator()
        coordinator.run { Unit }
        coordinator.run { Unit }
        val active = AtomicInteger()
        val maximum = AtomicInteger()

        coroutineScope {
            List(2) {
                async {
                    coordinator.run {
                        val count = active.incrementAndGet()
                        maximum.accumulateAndGet(count, ::maxOf)
                        delay(20)
                        active.decrementAndGet()
                    }
                }
            }.awaitAll()
        }

        assertEquals(2, maximum.get())
    }

    @Test
    fun coalescesRateLimitsWithinOneCooldownEpisode() = runBlocking {
        val coordinator = TransferAdaptiveCoordinator()
        val first = coordinator.registerRateLimit(TransferRateLimitException(4_000))
        val second = coordinator.registerRateLimit(TransferRateLimitException(null))

        assertEquals(1, first.consecutiveRateLimits)
        assertEquals(1, second.consecutiveRateLimits)
        assertTrue(first.cooldownMillis in 3_500L..4_000L)
        assertTrue(second.cooldownMillis <= first.cooldownMillis)
    }
}
