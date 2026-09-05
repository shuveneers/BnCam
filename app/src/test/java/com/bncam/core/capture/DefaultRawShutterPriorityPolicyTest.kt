package com.bncam.core.capture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DefaultRawShutterPriorityPolicyTest {
    private val bounds = ExposureBounds(
        minIso = 50,
        maxIso = 12_800,
        minExposureNs = 100_000L,
        maxExposureNs = 250_000_000L
    )

    @Test
    fun `static scene immediately uses longest safe shutter to reduce gain`() {
        val plan = DefaultRawShutterPriorityPolicy.resolve(
            measuredIso = 1_600,
            measuredExposureNs = 40_000_000L,
            bounds = bounds,
            ceilings = RawShutterSafetyCeilings(
                cameraMotionNs = 100_000_000L,
                sceneMotionNs = 100_000_000L,
                streamCadenceNs = 100_000_000L
            )
        )

        assertTrue(plan.ready)
        assertEquals(100_000_000L, plan.targetExposureNs)
        assertEquals(640, plan.expectedIso)
        assertEquals("CAMERA_MOTION", plan.limitingConstraint)
        assertEquals("photon_first_longest_safe_shutter_minimizes_gain", plan.reason)
    }

    @Test
    fun `moving scene still shortens shutter immediately and lets iso absorb requirement`() {
        val plan = DefaultRawShutterPriorityPolicy.resolve(
            measuredIso = 1_600,
            measuredExposureNs = 40_000_000L,
            bounds = bounds,
            ceilings = RawShutterSafetyCeilings(
                cameraMotionNs = 80_000_000L,
                sceneMotionNs = 20_000_000L,
                streamCadenceNs = 100_000_000L
            )
        )

        assertTrue(plan.ready)
        assertEquals(20_000_000L, plan.targetExposureNs)
        assertEquals(3_200, plan.expectedIso)
        assertEquals("SCENE_MOTION", plan.limitingConstraint)
    }

    @Test
    fun `minimum iso is reached directly when safe shutter permits it`() {
        val plan = DefaultRawShutterPriorityPolicy.resolve(
            measuredIso = 100,
            measuredExposureNs = 20_000_000L,
            bounds = bounds,
            ceilings = RawShutterSafetyCeilings(
                cameraMotionNs = 200_000_000L,
                sceneMotionNs = 200_000_000L
            )
        )

        assertTrue(plan.ready)
        assertEquals(40_000_000L, plan.targetExposureNs)
        assertEquals(50, plan.expectedIso)
        assertEquals("MIN_ISO", plan.limitingConstraint)
    }

    @Test
    fun `lens fallback alone cannot activate default strategy unless explicitly allowed`() {
        val plan = DefaultRawShutterPriorityPolicy.resolve(
            measuredIso = 1_600,
            measuredExposureNs = 40_000_000L,
            bounds = bounds,
            ceilings = RawShutterSafetyCeilings(
                streamCadenceNs = 80_000_000L,
                lensStabilityNs = 40_000_000L
            )
        )

        assertFalse(plan.ready)
        assertEquals("MOTION_EVIDENCE_REQUIRED", plan.limitingConstraint)
    }

    @Test
    fun `ae fps lower bound does not cap manual low light shutter`() {
        val plan = DefaultRawShutterPriorityPolicy.resolve(
            measuredIso = 2_000,
            measuredExposureNs = 25_000_000L,
            bounds = bounds,
            ceilings = RawShutterSafetyCeilings(
                cameraMotionNs = 120_000_000L,
                sceneMotionNs = 90_000_000L,
                streamCadenceNs = 50_000_000L
            )
        )

        assertTrue(plan.ready)
        assertEquals(90_000_000L, plan.targetExposureNs)
        assertEquals(556, plan.expectedIso)
        assertEquals("SCENE_MOTION", plan.limitingConstraint)
    }

    @Test
    fun `lens fallback with allow flag supplies calm wide angle shutter when motion is unavailable`() {
        val plan = DefaultRawShutterPriorityPolicy.resolve(
            measuredIso = 1_600,
            measuredExposureNs = 10_000_000L,
            bounds = bounds,
            ceilings = RawShutterSafetyCeilings(
                streamCadenceNs = 80_000_000L,
                lensStabilityNs = 42_000_000L,
                allowLensStabilityFallback = true
            )
        )

        assertTrue(plan.ready)
        assertEquals(42_000_000L, plan.targetExposureNs)
        assertEquals(381, plan.expectedIso)
        assertEquals("LENS_STABILITY_FALLBACK", plan.limitingConstraint)
    }

    @Test
    fun `measured motion replaces lens heuristic instead of being capped by it`() {
        val plan = DefaultRawShutterPriorityPolicy.resolve(
            measuredIso = 2_000,
            measuredExposureNs = 10_000_000L,
            bounds = bounds,
            ceilings = RawShutterSafetyCeilings(
                cameraMotionNs = 40_000_000L,
                sceneMotionNs = 50_000_000L,
                streamCadenceNs = 60_000_000L,
                lensStabilityNs = 10_000_000L,
                allowLensStabilityFallback = true
            )
        )

        assertTrue(plan.ready)
        assertEquals(40_000_000L, plan.targetExposureNs)
        assertEquals(500, plan.expectedIso)
        assertEquals("CAMERA_MOTION", plan.limitingConstraint)
    }

    @Test
    fun `ten millisecond bootstrap is not permanently pinned to first release step`() {
        val plan = DefaultRawShutterPriorityPolicy.resolve(
            measuredIso = 8_000,
            measuredExposureNs = 10_000_000L,
            bounds = bounds,
            ceilings = RawShutterSafetyCeilings(
                cameraMotionNs = 50_000_000L,
                sceneMotionNs = 60_000_000L,
                streamCadenceNs = 60_000_000L
            )
        )

        assertTrue(plan.ready)
        assertEquals(50_000_000L, plan.targetExposureNs)
        assertEquals(1_600, plan.expectedIso)
    }

    @Test
    fun `50hz photon first allocation selects longest complete light period below ceiling`() {
        val plan = DefaultRawShutterPriorityPolicy.resolve(
            measuredIso = 8_000,
            measuredExposureNs = 10_000_000L,
            bounds = bounds,
            ceilings = RawShutterSafetyCeilings(
                cameraMotionNs = 47_000_000L,
                sceneMotionNs = 60_000_000L,
                streamCadenceNs = 60_000_000L
            ),
            flickerConstraint = RawFlickerConstraint(
                frequency = RawFlickerFrequency.HZ_50,
                source = "TEST"
            )
        )

        assertTrue(plan.ready)
        assertEquals(40_000_000L, plan.targetExposureNs)
        assertEquals(2_000, plan.expectedIso)
        assertTrue(plan.limitingConstraint.startsWith("FLICKER_HZ_50"))
    }

    @Test
    fun `raw near clip reduces target product and expected gain`() {
        val normalPlan = DefaultRawShutterPriorityPolicy.resolve(
            measuredIso = 1_600,
            measuredExposureNs = 40_000_000L,
            bounds = bounds,
            ceilings = RawShutterSafetyCeilings(
                cameraMotionNs = 100_000_000L,
                sceneMotionNs = 100_000_000L
            ),
            rawNearClipFraction = 0f
        )
        val ettrPlan = DefaultRawShutterPriorityPolicy.resolve(
            measuredIso = 1_600,
            measuredExposureNs = 40_000_000L,
            bounds = bounds,
            ceilings = RawShutterSafetyCeilings(
                cameraMotionNs = 100_000_000L,
                sceneMotionNs = 100_000_000L
            ),
            rawNearClipFraction = 0.05f
        )
        assertTrue(ettrPlan.referenceExposureProduct!! < normalPlan.referenceExposureProduct!!)
        assertTrue(ettrPlan.expectedIso!! < normalPlan.expectedIso!!)
    }
}
