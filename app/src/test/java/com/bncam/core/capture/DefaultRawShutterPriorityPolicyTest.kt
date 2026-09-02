package com.bncam.core.capture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.pow

class DefaultRawShutterPriorityPolicyTest {
    private val bounds = ExposureBounds(
        minIso = 50,
        maxIso = 12_800,
        minExposureNs = 100_000L,
        maxExposureNs = 250_000_000L
    )

    @Test
    fun `static scene lengthens shutter gradually before lowering iso further`() {
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

        val expectedExposure = (40_000_000.0 * 2.0.pow(0.25)).toLong()
        assertTrue(plan.ready)
        assertEquals(expectedExposure, plan.targetExposureNs)
        assertEquals(1_346, plan.expectedIso)
        assertEquals("AE_TRANSITION_RELEASE_RATE", plan.limitingConstraint)
    }

    @Test
    fun `moving scene shortens shutter immediately and lets iso absorb requirement`() {
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
    fun `minimum iso target is approached gradually instead of one exposure jump`() {
        val plan = DefaultRawShutterPriorityPolicy.resolve(
            measuredIso = 100,
            measuredExposureNs = 20_000_000L,
            bounds = bounds,
            ceilings = RawShutterSafetyCeilings(
                cameraMotionNs = 200_000_000L,
                sceneMotionNs = 200_000_000L
            )
        )

        val expectedExposure = (20_000_000.0 * 2.0.pow(0.25)).toLong()
        assertTrue(plan.ready)
        assertEquals(expectedExposure, plan.targetExposureNs)
        assertEquals(85, plan.expectedIso)
        assertEquals("AE_TRANSITION_RELEASE_RATE", plan.limitingConstraint)
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
    fun `stream cadence extension is approached gradually`() {
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

        val expectedExposure = (25_000_000.0 * 2.0.pow(0.25)).toLong()
        assertTrue(plan.ready)
        assertEquals(expectedExposure, plan.targetExposureNs)
        assertEquals(1_682, plan.expectedIso)
        assertEquals("AE_TRANSITION_RELEASE_RATE", plan.limitingConstraint)
    }
}
