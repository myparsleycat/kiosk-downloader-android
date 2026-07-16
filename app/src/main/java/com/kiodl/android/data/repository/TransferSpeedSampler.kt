package com.kiodl.android.data.repository

import kotlin.math.exp

internal class TransferSpeedSampler {
    private data class ByteSample(val bytes: Long, val at: Long)
    private data class State(
        val samples: ArrayDeque<ByteSample> = ArrayDeque(),
        var ema: Double? = null,
        var lastEmaAt: Long? = null,
    )

    private val states = mutableMapOf<String, State>()

    @Synchronized
    fun sample(key: String, bytes: Long, active: Boolean, now: Long = System.currentTimeMillis()): Long? {
        if (!active) {
            states.remove(key)
            return null
        }
        val windowMillis = if (key.startsWith("upload")) UPLOAD_WINDOW_MS else DOWNLOAD_WINDOW_MS
        var state = states.getOrPut(key, ::State)
        if (state.samples.lastOrNull()?.bytes?.let { bytes < it } == true) {
            state = State()
            states[key] = state
        }
        state.samples.addLast(ByteSample(bytes, now))
        while (state.samples.firstOrNull()?.let { now - it.at > windowMillis } == true) {
            state.samples.removeFirst()
        }

        val first = state.samples.firstOrNull()
        val last = state.samples.lastOrNull()
        if (first == null || last == null || last.at - first.at < MIN_SAMPLE_SPAN_MS) {
            if (state.lastEmaAt?.let { now - it > windowMillis } == true) {
                state.ema = null
                state.lastEmaAt = null
            }
            return state.ema?.toLong() ?: 0L
        }

        val elapsedMillis = last.at - first.at
        val instantBps = ((last.bytes - first.bytes).coerceAtLeast(0) * 1_000.0) / elapsedMillis
        val previous = state.ema
        val dtMillis = state.lastEmaAt?.let { now - it } ?: Long.MAX_VALUE
        state.ema = if (previous == null || dtMillis == Long.MAX_VALUE) {
            instantBps
        } else {
            val alpha = 1.0 - exp(-dtMillis.toDouble() / EMA_TAU_MS)
            alpha * instantBps + (1.0 - alpha) * previous
        }
        state.lastEmaAt = now
        return state.ema?.toLong() ?: 0L
    }

    private companion object {
        const val DOWNLOAD_WINDOW_MS = 2_000L
        const val UPLOAD_WINDOW_MS = 3_000L
        const val MIN_SAMPLE_SPAN_MS = 500L
        const val EMA_TAU_MS = 1_500.0
    }
}
