package com.kiodl.android.transfer

import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

private const val MAX_UPLOAD_IN_FLIGHT_SEGMENTS = 8

@Singleton
class SegmentPoolCoordinator @Inject constructor() {
    private val downloads = ResizableSegmentPermitPool()
    private val uploads = ResizableSegmentPermitPool()

    suspend fun <T> withDownloadPermit(poolSize: Int, block: suspend () -> T): T =
        downloads.withPermit(poolSize, block)

    suspend fun <T> withUploadPermit(poolSize: Int, block: suspend () -> T): T =
        uploads.withPermit(minOf(poolSize, MAX_UPLOAD_IN_FLIGHT_SEGMENTS), block)
}

internal class ResizableSegmentPermitPool {
    private val mutex = Mutex()
    private val waiters = ArrayDeque<CompletableDeferred<Unit>>()
    private var limit = 1
    private var inUse = 0

    suspend fun <T> withPermit(maxPermits: Int, block: suspend () -> T): T {
        acquire(maxPermits.coerceAtLeast(1))
        return try {
            block()
        } finally {
            release()
        }
    }

    private suspend fun acquire(maxPermits: Int) {
        val waiter = CompletableDeferred<Unit>()
        val acquired = mutex.withLock {
            limit = maxPermits
            if (waiters.isEmpty() && inUse < limit) {
                inUse += 1
                true
            } else {
                waiters.addLast(waiter)
                grantWaiters()
                false
            }
        }
        if (acquired) return
        try {
            waiter.await()
        } catch (error: Throwable) {
            mutex.withLock {
                if (!waiters.remove(waiter) && waiter.isCompleted) {
                    inUse -= 1
                    grantWaiters()
                }
            }
            throw error
        }
    }

    private suspend fun release() {
        mutex.withLock {
            check(inUse > 0)
            inUse -= 1
            grantWaiters()
        }
    }

    private fun grantWaiters() {
        while (inUse < limit && waiters.isNotEmpty()) {
            val waiter = waiters.removeFirst()
            if (waiter.complete(Unit)) inUse += 1
        }
    }
}
