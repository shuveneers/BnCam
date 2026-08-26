package com.bncam.core.capture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProfileExposurePriorityPlannerTest {
    private val bounds = ExposureBounds(50, 6400, 100_000L, 1_000_000_000L)

    @Test
    fun autoLeavesCameraAeAuthoritative() {
        val plan = ProfileExposurePriorityPlanner.initialPlan(
            CaptureExposurePreferences(),
            measuredIso = 200,
            measuredExposureNs = 30_000_000L,
            bounds = bounds
        )
        assertTrue(plan.autoExposure)
        assertTrue(plan.ready)
        assertEquals(null, plan.exposureTimeNs)
        assertEquals(null, plan.sensitivityIso)
    }

    @Test
    fun fixedIsoKeepsIsoAndSolvesShutterFromMeasuredAeProduct() {
        val plan = ProfileExposurePriorityPlanner.initialPlan(
            CaptureExposurePreferences(shotBiasExposure = ShotBiasExposureChoice.ISO_100),
            measuredIso = 200,
            measuredExposureNs = 30_000_000L,
            bounds = bounds
        )
        assertFalse(plan.autoExposure)
        assertEquals(CaptureExposurePriorityMode.ISO_PRIORITY, plan.mode)
        assertEquals(100, plan.sensitivityIso)
        assertEquals(60_000_000L, plan.exposureTimeNs)
    }

    @Test
    fun fixedTimeKeepsTimeAndSolvesIsoFromMeasuredAeProduct() {
        val plan = ProfileExposurePriorityPlanner.initialPlan(
            CaptureExposurePreferences(shotBiasExposure = ShotBiasExposureChoice.TIME_60),
            measuredIso = 200,
            measuredExposureNs = 30_000_000L,
            bounds = bounds
        )
        assertFalse(plan.autoExposure)
        assertEquals(CaptureExposurePriorityMode.SHUTTER_PRIORITY, plan.mode)
        assertEquals(16_666_667L, plan.exposureTimeNs)
        assertEquals(360, plan.sensitivityIso)
    }

    @Test
    fun captureEvBiasChangesCompensatingVariableNotFixedIso() {
        val plan = ProfileExposurePriorityPlanner.initialPlan(
            CaptureExposurePreferences(
                captureEvBias = 1f,
                shotBiasExposure = ShotBiasExposureChoice.ISO_100
            ),
            measuredIso = 200,
            measuredExposureNs = 30_000_000L,
            bounds = bounds
        )
        assertEquals(100, plan.sensitivityIso)
        assertEquals(120_000_000L, plan.exposureTimeNs)
    }

    @Test
    fun maxFrameExposureIsHardCeilingEvenForFixedTime() {
        val plan = ProfileExposurePriorityPlanner.initialPlan(
            CaptureExposurePreferences(
                shotBiasExposure = ShotBiasExposureChoice.TIME_1S,
                maxFrameExposure = MaxFrameExposureChoice.SEC_0_3
            ),
            measuredIso = 100,
            measuredExposureNs = 500_000_000L,
            bounds = bounds
        )
        assertEquals(300_000_000L, plan.exposureTimeNs)
        assertEquals(CaptureExposurePriorityMode.SHUTTER_PRIORITY, plan.mode)
        assertTrue(plan.limitingConstraint.contains("MAX_FRAME_EXPOSURE"))
    }

    @Test
    fun autoWithFrameCeilingUsesMeasuredAeBaselineAndHonoursCeiling() {
        val plan = ProfileExposurePriorityPlanner.initialPlan(
            CaptureExposurePreferences(maxFrameExposure = MaxFrameExposureChoice.SEC_0_3),
            measuredIso = 100,
            measuredExposureNs = 600_000_000L,
            bounds = bounds
        )
        assertFalse(plan.autoExposure)
        assertEquals(CaptureExposurePriorityMode.BALANCED, plan.mode)
        assertEquals(300_000_000L, plan.exposureTimeNs)
        assertEquals(200, plan.sensitivityIso)
    }

    @Test
    fun evOnlyDoesNotRequireManualSensorAuthority() {
        val prefs = CaptureExposurePreferences(captureEvBias = 1f)
        assertFalse(prefs.requiresAeBaseline())
        val plan = ProfileExposurePriorityPlanner.initialPlan(
            prefs,
            measuredIso = 200,
            measuredExposureNs = 30_000_000L,
            bounds = bounds
        )
        assertTrue(plan.autoExposure)
        assertTrue(plan.ready)
    }

    @Test
    fun missingMeasuredAeKeepsCameraAeUntilDirectAuthorityCanBootstrap() {
        val plan = ProfileExposurePriorityPlanner.initialPlan(
            CaptureExposurePreferences(shotBiasExposure = ShotBiasExposureChoice.ISO_400),
            measuredIso = null,
            measuredExposureNs = null,
            bounds = bounds
        )
        assertTrue(plan.autoExposure)
        assertFalse(plan.ready)
        assertEquals("AWAITING_AE_BASELINE", plan.limitingConstraint)
    }

    @Test
    fun liveAdaptationKeepsFixedTimeAndChangesIso() {
        val initial = ProfileExposurePriorityPlanner.initialPlan(
            CaptureExposurePreferences(shotBiasExposure = ShotBiasExposureChoice.TIME_30),
            200, 30_000_000L, bounds
        )
        val adapted = ProfileExposurePriorityPlanner.adaptToLuma(
            initial,
            observedLuma = 0.10f,
            targetLuma = 0.20f,
            clippingFraction = 0f,
            bounds = bounds
        )
        assertEquals(initial.exposureTimeNs, adapted.exposureTimeNs)
        assertTrue(adapted.sensitivityIso!! > initial.sensitivityIso!!)
    }

    @Test
    fun liveAdaptationKeepsFixedIsoAndChangesTime() {
        val initial = ProfileExposurePriorityPlanner.initialPlan(
            CaptureExposurePreferences(shotBiasExposure = ShotBiasExposureChoice.ISO_200),
            200, 30_000_000L, bounds
        )
        val adapted = ProfileExposurePriorityPlanner.adaptToLuma(
            initial,
            observedLuma = 0.10f,
            targetLuma = 0.20f,
            clippingFraction = 0f,
            bounds = bounds
        )
        assertEquals(initial.sensitivityIso, adapted.sensitivityIso)
        assertTrue(adapted.exposureTimeNs!! > initial.exposureTimeNs!!)
    }

    @Test
    fun clippingNeverRaisesExposureProduct() {
        val initial = ProfileExposurePriorityPlanner.initialPlan(
            CaptureExposurePreferences(shotBiasExposure = ShotBiasExposureChoice.TIME_30),
            400, 30_000_000L, bounds
        )
        val adapted = ProfileExposurePriorityPlanner.adaptToLuma(
            initial,
            observedLuma = 0.10f,
            targetLuma = 0.20f,
            clippingFraction = 0.04f,
            bounds = bounds
        )
        assertTrue(adapted.achievedExposureProduct!! < initial.achievedExposureProduct!!)
    }

    @Test
    fun legacyPriorityValuesAreDecodedButDoNotExecute() {
        val prefs = CaptureExposurePreferences.fromPersisted(
            priorityMode = "Shutter Priority",
            shutterMultiplier = 0.25f,
            isoMultiplier = 4f,
            captureEvBias = 0f,
            shotBiasExposure = "Auto",
            maxFrameExposure = "Max exposure time"
        )
        assertEquals(CaptureExposurePriorityMode.BALANCED, prefs.priorityMode)
        assertEquals(1f, prefs.shutterMultiplier)
        assertEquals(1f, prefs.isoMultiplier)
        assertFalse(prefs.requiresAeBaseline())
    }
}
