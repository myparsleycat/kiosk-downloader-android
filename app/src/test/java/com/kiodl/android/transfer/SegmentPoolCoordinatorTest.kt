package com.kiodl.android.transfer

import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

class SegmentPoolCoordinatorTest {
    @Test
    fun boundsConcurrentWorkAcrossCallers() = runBlocking {
        val pool = ResizableSegmentPermitPool()
        val active = AtomicInteger()
        val maximum = AtomicInteger()

        coroutineScope {
            List(12) {
                async {
                    pool.withPermit(3) {
                        val count = active.incrementAndGet()
                        maximum.accumulateAndGet(count, ::maxOf)
                        delay(10)
                        active.decrementAndGet()
                    }
                }
            }.awaitAll()
        }

        assertEquals(3, maximum.get())
    }

    @Test
    fun capsUploadWorkersAtDesktopLimit() = runBlocking {
        val coordinator = SegmentPoolCoordinator()
        val active = AtomicInteger()
        val maximum = AtomicInteger()

        coroutineScope {
            List(16) {
                async {
                    coordinator.withUploadPermit(64) {
                        val count = active.incrementAndGet()
                        maximum.accumulateAndGet(count, ::maxOf)
                        delay(10)
                        active.decrementAndGet()
                    }
                }
            }.awaitAll()
        }

        assertEquals(8, maximum.get())
    }
}
