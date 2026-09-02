package com.bncam.core.capture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DefaultRawShutterManualFallbackPolicyTest {
    private val bounds = ExposureBounds(50, 12_800, 100_000L, 250_000_000L)

    @Test
    fun `initial plan preserves ae sensitivity with longest safe shutter`() {
        val plan = DefaultRawShutterManualFallbackPolicy.initial(
            1_600.0 * 40_000_000.0, 0.18f, 80_000_000L, bounds
        )!!
        assertEquals(80_000_000L, plan.exposureTimeNs)
        assertEquals(800, plan.sensitivityIso)
    }

    @Test
    fun `darker observation increases product only by bounded step`() {
        val initial = DefaultRawShutterManualFallbackPolicy.initial(
            800.0 * 80_000_000.0, 0.18f, 80_000_000L, bounds
        )!!
        val next = DefaultRawShutterManualFallbackPolicy.adapt(
            initial, 0.09f, 0f, 80_000_000L, bounds
        )
        assertTrue(next.lastCorrectionEv in 0f..0.25f)
        assertTrue(next.exposureProduct > initial.exposureProduct)
    }

    @Test
    fun `raw clipping blocks a positive exposure correction`() {
        val initial = DefaultRawShutterManualFallbackPolicy.initial(
            800.0 * 80_000_000.0, 0.18f, 80_000_000L, bounds
        )!!
        val next = DefaultRawShutterManualFallbackPolicy.adapt(
            initial, 0.10f, 0.03f, 80_000_000L, bounds
        )
        assertTrue(next.lastCorrectionEv < 0f)
    }

    @Test
    fun `motion reallocates same product between shutter and iso`() {
        val initial = DefaultRawShutterManualFallbackPolicy.initial(
            1_600.0 * 40_000_000.0, 0.18f, 80_000_000L, bounds
        )!!
        val moved = DefaultRawShutterManualFallbackPolicy.reallocateForMotion(
            initial, 20_000_000L, bounds
        )
        assertEquals(initial.exposureProduct, moved.exposureProduct, 0.001)
        assertEquals(20_000_000L, moved.exposureTimeNs)
        assertEquals(3_200, moved.sensitivityIso)
    }
}
