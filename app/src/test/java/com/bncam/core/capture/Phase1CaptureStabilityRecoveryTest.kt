package com.bncam.core.capture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class Phase1CaptureStabilityRecoveryTest {
    private val bounds = ExposureBounds(50, 12_800, 100_000L, 250_000_000L)

    @Test
    fun `50hz quantization snaps 14 point 9ms to strict 10ms flicker period`() {
        val plan = DefaultRawShutterPriorityPolicy.resolve(
            measuredIso = 3200,
            measuredExposureNs = 20_000_000L,
            bounds = bounds,
            ceilings = RawShutterSafetyCeilings(
                cameraMotionNs = 14_920_000L,
                sceneMotionNs = 30_000_000L,
                streamCadenceNs = 40_000_000L
            ),
            flickerConstraint = RawFlickerConstraint(RawFlickerFrequency.HZ_50, "TEST")
        )
        assertTrue(plan.ready)
        assertEquals(10_000_000L, plan.targetExposureNs)
        assertEquals("strict_flicker_safe_shutter_iso_preserves_exposure_product", plan.reason)
    }

    @Test
    fun `longer shutter recovers by at most quarter ev per realization`() {
        val plan = DefaultRawShutterPriorityPolicy.resolve(
            measuredIso = 3200,
            measuredExposureNs = 10_000_000L,
            bounds = bounds,
            ceilings = RawShutterSafetyCeilings(
                cameraMotionNs = 40_000_000L,
                sceneMotionNs = 40_000_000L,
                streamCadenceNs = 40_000_000L
            )
        )
        assertTrue(plan.ready)
        assertTrue(plan.targetExposureNs!! in 11_880_000L..11_900_000L)
        assertEquals("AE_TRANSITION_RELEASE_RATE", plan.limitingConstraint)
    }

    @Test
    fun `new tighter motion ceiling is obeyed immediately`() {
        val plan = DefaultRawShutterPriorityPolicy.resolve(
            measuredIso = 1600,
            measuredExposureNs = 40_000_000L,
            bounds = bounds,
            ceilings = RawShutterSafetyCeilings(
                cameraMotionNs = 12_000_000L,
                sceneMotionNs = 20_000_000L,
                streamCadenceNs = 40_000_000L
            )
        )
        assertEquals(12_000_000L, plan.targetExposureNs)
    }

    @Test
    fun `neighbouring warm frame survives request transition`() {
        val decision = FrameSelectionExposurePolicy.evaluate(
            actualExposureNs = 20_000_000L,
            requestedExposureTargetNs = 14_920_000L,
            actualIso = 1_492,
            requestedIso = 2_000
        )
        assertTrue(decision.eligible)
        assertEquals("TRANSITIONAL_WARM_FRAME_EXPOSURE_COMPENSATED", decision.reason)
    }

    @Test
    fun `grossly stale long warm frame remains rejected`() {
        val decision = FrameSelectionExposurePolicy.evaluate(
            actualExposureNs = 40_000_000L,
            requestedExposureTargetNs = 10_000_000L
        )
        assertFalse(decision.eligible)
        assertEquals("FRAME_EXPOSURE_TOO_LONG_FOR_CURRENT_TRANSITION", decision.reason)
    }
}
