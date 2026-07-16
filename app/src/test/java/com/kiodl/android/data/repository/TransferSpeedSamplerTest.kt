package com.kiodl.android.data.repository

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TransferSpeedSamplerTest {
    @Test
    fun waitsForDesktopSampleSpanAndThenReportsEma() {
        val sampler = TransferSpeedSampler()
        assertEquals(0L, sampler.sample("download:file", 0, true, 0))
        assertEquals(0L, sampler.sample("download:file", 250, true, 250))
        assertEquals(1_000L, sampler.sample("download:file", 500, true, 500))
        val smoothed = checkNotNull(sampler.sample("download:file", 1_500, true, 1_000))
        assertTrue(smoothed in 1_000L..2_000L)
    }

    @Test
    fun clearsSamplesWhenTransferStops() {
        val sampler = TransferSpeedSampler()
        sampler.sample("upload:file", 0, true, 0)
        sampler.sample("upload:file", 500, true, 500)
        assertEquals(null, sampler.sample("upload:file", 500, false, 600))
        assertEquals(0L, sampler.sample("upload:file", 500, true, 700))
    }
}
