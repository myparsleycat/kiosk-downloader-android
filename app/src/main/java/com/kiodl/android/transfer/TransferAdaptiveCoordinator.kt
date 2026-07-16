package com.kiodl.android.transfer

import com.kiodl.android.data.remote.TransferRateLimitException
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

@Singleton
class TransferAdaptiveCoordinator @Inject constructor() {
    private val mutex = Mutex()
    private var targetWorkers = 1
    private var activeWorkers = 0
    private var successesAtCurrentConcurrency = 0
    private var consecutiveRateLimits = 0
    private var cooldownUntil = 0L

    suspend fun <T> run(countSuccess: Boolean = true, block: suspend () -> T): T {
        acquire()
        try {
            return block().also { if (countSuccess) recordSuccess() }
        } finally {
            mutex.withLock { activeWorkers = (activeWorkers - 1).coerceAtLeast(0) }
        }
    }

    suspend fun registerRateLimit(error: TransferRateLimitException): RateLimitDecision = mutex.withLock {
        val now = System.currentTimeMillis()
        if (now >= cooldownUntil) {
            consecutiveRateLimits += 1
            targetWorkers = 1
            successesAtCurrentConcurrency = 0
            val configured = RATE_LIMIT_DELAYS[
                (consecutiveRateLimits - 1).coerceAtMost(RATE_LIMIT_DELAYS.lastIndex)
            ]
            cooldownUntil = now + maxOf(configured, error.retryAfterMillis ?: 0)
        }
        RateLimitDecision(consecutiveRateLimits, (cooldownUntil - now).coerceAtLeast(0))
    }

    private suspend fun acquire() {
        while (true) {
            val waitMillis = mutex.withLock {
                val now = System.currentTimeMillis()
                when {
                    now < cooldownUntil -> cooldownUntil - now
                    activeWorkers < targetWorkers -> {
                        activeWorkers += 1
                        return
                    }
                    else -> 50L
                }
            }
            delay(waitMillis.coerceAtLeast(1))
        }
    }

    private suspend fun recordSuccess() = mutex.withLock {
        if (System.currentTimeMillis() >= cooldownUntil) consecutiveRateLimits = 0
        if (System.currentTimeMillis() < cooldownUntil || targetWorkers >= MAX_WORKERS) return@withLock
        successesAtCurrentConcurrency += 1
        if (successesAtCurrentConcurrency >= SUCCESSES_PER_INCREASE) {
            successesAtCurrentConcurrency = 0
            targetWorkers += 1
        }
    }

    data class RateLimitDecision(val consecutiveRateLimits: Int, val cooldownMillis: Long)

    private companion object {
        const val MAX_WORKERS = 4
        const val SUCCESSES_PER_INCREASE = 2
        val RATE_LIMIT_DELAYS = longArrayOf(2_000, 5_000, 10_000)
    }
}
