package com.kiodl.android.transfer

import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

@Singleton
class TransferBandwidthCoordinator @Inject constructor() {
    private val download = SuspendingLimiter()
    private val upload = BlockingLimiter()

    suspend fun consumeDownload(bytes: Int, limitMiBps: Int) = download.consume(bytes, limitMiBps)

    fun consumeUpload(bytes: Int, limitMiBps: Int) = upload.consume(bytes, limitMiBps)
}

private class SuspendingLimiter {
    private val mutex = Mutex()
    private var availableAtNanos = System.nanoTime()

    suspend fun consume(bytes: Int, limitMiBps: Int) {
        val bytesPerSecond = limitMiBps.toLong() * 1024 * 1024
        if (bytesPerSecond <= 0 || bytes <= 0) return
        val waitNanos = mutex.withLock {
            val now = System.nanoTime()
            val start = maxOf(now, availableAtNanos)
            availableAtNanos = start + bytes * 1_000_000_000L / bytesPerSecond
            start - now
        }
        if (waitNanos > 0) delay((waitNanos + 999_999) / 1_000_000)
    }
}

private class BlockingLimiter {
    private var availableAtNanos = System.nanoTime()

    fun consume(bytes: Int, limitMiBps: Int) {
        val bytesPerSecond = limitMiBps.toLong() * 1024 * 1024
        if (bytesPerSecond <= 0 || bytes <= 0) return
        val waitNanos = synchronized(this) {
            val now = System.nanoTime()
            val start = maxOf(now, availableAtNanos)
            availableAtNanos = start + bytes * 1_000_000_000L / bytesPerSecond
            start - now
        }
        if (waitNanos > 0) Thread.sleep(
            waitNanos / 1_000_000,
            (waitNanos % 1_000_000).toInt(),
        )
    }
}
