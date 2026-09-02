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
    fun `static scene spends available exposure time before iso`() {
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
    }

    @Test
    fun `moving scene shortens shutter and lets iso absorb requirement`() {
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
    fun `minimum iso prevents deliberate overexposure`() {
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
    fun `lens fallback alone cannot activate default strategy`() {
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
    fun `stream cadence can be stricter than measured motion`() {
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
        assertEquals(50_000_000L, plan.targetExposureNs)
        assertEquals(1_000, plan.expectedIso)
        assertEquals("STREAM_CADENCE", plan.limitingConstraint)
    }
}
