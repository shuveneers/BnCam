package com.bncam.core.capture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AntiFlickerAcquisitionPolicyTest {
    private val bounds = ExposureBounds(50, 12_800, 100_000L, 250_000_000L)

    @Test
    fun `50hz shutter integrates complete ten millisecond light periods`() {
        val flicker = RawFlickerConstraint(RawFlickerFrequency.HZ_50, "TEST")
        assertEquals(30_000_000L, flicker.constrainExposureNs(34_000_000L, 100_000L, 250_000_000L))
        assertEquals(20_000_000L, flicker.closestAlignedExposureNs(18_000_000L, 30_000_000L, 100_000L, 250_000_000L))
    }

    @Test
    fun `60hz uses rational light periods without accumulated rounding drift`() {
        val flicker = RawFlickerConstraint(RawFlickerFrequency.HZ_60, "TEST")
        assertEquals(16_666_667L, flicker.constrainExposureNs(17_000_000L, 100_000L, 250_000_000L))
        assertEquals(25_000_000L, flicker.constrainExposureNs(30_000_000L, 100_000L, 250_000_000L))
        assertTrue(flicker.isExposureAligned(16_666_667L))
    }

    @Test
    fun `large flicker shutter snap is compensated by iso instead of becoming brightness pulse`() {
        val plan = DefaultRawShutterPriorityPolicy.resolve(
            measuredIso = 1_600,
            measuredExposureNs = 14_900_000L,
            bounds = bounds,
            ceilings = RawShutterSafetyCeilings(50_000_000L, 50_000_000L, 50_000_000L),
            flickerConstraint = RawFlickerConstraint(RawFlickerFrequency.HZ_50, "TEST")
        )
        assertTrue(plan.ready)
        assertEquals(10_000_000L, plan.targetExposureNs)
        assertEquals(2_384, plan.expectedIso)
        assertEquals("FLICKER_HZ_50", plan.limitingConstraint)
    }

    @Test
    fun `motion safety remains stronger than anti flicker when full light period cannot fit`() {
        val plan = DefaultRawShutterPriorityPolicy.resolve(
            measuredIso = 1_600,
            measuredExposureNs = 20_000_000L,
            bounds = bounds,
            ceilings = RawShutterSafetyCeilings(5_000_000L, 5_000_000L, 40_000_000L),
            flickerConstraint = RawFlickerConstraint(RawFlickerFrequency.HZ_50, "TEST")
        )
        assertEquals(5_000_000L, plan.targetExposureNs)
        assertEquals("FLICKER_UNAVOIDABLE_SHORT_EXPOSURE", plan.limitingConstraint)
    }

    @Test
    fun `manual fallback holds aligned shutter and uses iso for small ae correction`() {
        val flicker = RawFlickerConstraint(RawFlickerFrequency.HZ_50, "TEST")
        val initial = DefaultRawShutterManualFallbackPolicy.initial(
            referenceExposureProduct = 800.0 * 40_000_000.0,
            targetLuma = 0.18f,
            safeExposureCeilingNs = 80_000_000L,
            bounds = bounds,
            flickerConstraint = flicker
        )!!
        val next = DefaultRawShutterManualFallbackPolicy.adapt(
            previous = initial,
            observedLuma = 0.16f,
            rawNearClipFraction = 0f,
            safeExposureCeilingNs = 80_000_000L,
            bounds = bounds,
            flickerConstraint = flicker
        )
        assertEquals(initial.exposureTimeNs, next.exposureTimeNs)
        assertTrue(next.sensitivityIso >= initial.sensitivityIso)
        assertEquals(80_000_000L, next.frameDurationNs)
        assertTrue(next.lastCorrectionEv in 0f..0.10f)
    }
}
