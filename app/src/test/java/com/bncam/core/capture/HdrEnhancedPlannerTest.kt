package com.bncam.core.capture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HdrEnhancedPlannerTest {

    @Test
    fun plannerSolvesFrameCountFromBurstCadenceAndBudget() {
        val plan = HdrEnhancedPlanner.plan(
            baseExposureTimeNs = 20_000_000L,
            baseSensitivityIso = 100,
            sensorMinFrameDurationNs = 33_333_333L,
            rawHeadroomEv = -1.0f,
            sceneMotionScore = 0.05f,
            userCaptureBudgetMs = 1000L
        )

        assertTrue(plan.requested)
        assertEquals(HdrEnhancedStatus.PLANNED, plan.status)
        assertEquals(HdrExposureAuthority.RAW_ETTR_HEADROOM, plan.exposureAuthority)
        assertEquals(HdrEnhancedMotionClass.LOW_MOTION, plan.sceneMotionClass)
        assertTrue(plan.mainFrameCount in 4..15)
        assertEquals(15, plan.frameBounds.hardwareFrameCapacity)
        assertEquals(15, plan.frameBounds.plannerMaximumFrames)
        assertTrue(plan.theoreticalSnrGain > 1.5f)
    }

    @Test
    fun highMotionConstrainsFrameCountToMotionBound() {
        val plan = HdrEnhancedPlanner.plan(
            baseExposureTimeNs = 15_000_000L,
            baseSensitivityIso = 200,
            sensorMinFrameDurationNs = 33_333_333L,
            sceneMotionScore = 0.80f,
            userCaptureBudgetMs = 1000L
        )

        assertEquals(HdrEnhancedMotionClass.HIGH_MOTION, plan.sceneMotionClass)
        assertEquals(6, plan.frameBounds.motionLimitedMaximumFrames)
        assertEquals(6, plan.mainFrameCount)
    }

    @Test
    fun unavailableMotionRemainsUnknownWithoutSyntheticScore() {
        val plan = HdrEnhancedPlanner.plan(
            baseExposureTimeNs = 15_000_000L,
            baseSensitivityIso = 200,
            sensorMinFrameDurationNs = 33_333_333L,
            sceneMotionScore = null,
            hardwareFrameCapacity = 10
        )

        assertEquals(HdrEnhancedMotionClass.UNKNOWN, plan.sceneMotionClass)
        assertEquals(10, plan.mainFrameCount)
    }

    @Test
    fun constraintsBelowFourFailInsteadOfBeingClampedUp() {
        val plan = HdrEnhancedPlanner.plan(
            baseExposureTimeNs = 40_000_000L,
            baseSensitivityIso = 100,
            sensorMinFrameDurationNs = 40_000_000L,
            hardwareFrameCapacity = 3,
            hdrEnhancedFrameSetting = "3"
        )

        assertEquals(HdrEnhancedStatus.FAILED, plan.status)
        assertEquals(0, plan.mainFrameCount)
        assertEquals(3, plan.frameBounds.finalMainFrameCount)
        assertTrue(plan.reason.contains("minimum=4"))
    }

    @Test
    fun auxiliaryExposureIsNotReportedUntilActuallyAcquired() {
        val plan = HdrEnhancedPlanner.plan(
            baseExposureTimeNs = 40_000_000L,
            baseSensitivityIso = 100,
            sensorMinFrameDurationNs = 33_333_333L,
            rawHeadroomEv = -1.5f,
            sceneMotionScore = 0.05f
        )

        assertEquals(HdrEnhancedAuxRole.NONE, plan.auxPlan.role)
        assertEquals("main_temporal_stack_only", plan.auxPlan.reason)
    }
    @Test
    fun shutterPriorityKeepsRepeatingShutterAndUsesIsoForHdrHighlightProtection() {
        val plan = HdrEnhancedPlanner.plan(
            baseExposureTimeNs = 20_000_000L,
            baseSensitivityIso = 400,
            sensorMinFrameDurationNs = 33_333_333L,
            rawHeadroomEv = -4.0f / 3.0f, // planner maps 0.75x headroom -> -1 EV
            exposurePriorityMode = CaptureExposurePriorityMode.SHUTTER_PRIORITY,
            exposureBounds = ExposureBounds(100, 6400, 500_000L, 100_000_000L)
        )

        assertEquals(20_000_000L, plan.mainExposureTimeNs)
        assertEquals(200, plan.mainSensitivityIso)
        assertTrue(plan.profileExposurePriorityHonored)
        assertEquals("NONE", plan.profileExposurePriorityConstraint)
    }

    @Test
    fun shutterPriorityOnlyShortensShutterWhenIsoFloorPreventsRequestedHdrProtection() {
        val plan = HdrEnhancedPlanner.plan(
            baseExposureTimeNs = 20_000_000L,
            baseSensitivityIso = 400,
            sensorMinFrameDurationNs = 33_333_333L,
            rawHeadroomEv = -2.0f, // clamps to -1.5 EV
            exposurePriorityMode = CaptureExposurePriorityMode.SHUTTER_PRIORITY,
            exposureBounds = ExposureBounds(300, 6400, 500_000L, 100_000_000L)
        )

        assertEquals(300, plan.mainSensitivityIso)
        assertTrue(plan.mainExposureTimeNs < 20_000_000L)
        assertTrue(!plan.profileExposurePriorityHonored)
        assertEquals("ISO_FLOOR_REQUIRES_SHUTTER_OVERRIDE", plan.profileExposurePriorityConstraint)
    }

    @Test
    fun isoPriorityKeepsRepeatingIsoAndUsesShutterForHdrHighlightProtection() {
        val plan = HdrEnhancedPlanner.plan(
            baseExposureTimeNs = 20_000_000L,
            baseSensitivityIso = 400,
            sensorMinFrameDurationNs = 33_333_333L,
            rawHeadroomEv = -4.0f / 3.0f,
            exposurePriorityMode = CaptureExposurePriorityMode.ISO_PRIORITY,
            exposureBounds = ExposureBounds(100, 6400, 500_000L, 100_000_000L)
        )

        assertEquals(10_000_000L, plan.mainExposureTimeNs)
        assertEquals(400, plan.mainSensitivityIso)
        assertTrue(plan.profileExposurePriorityHonored)
    }

}
