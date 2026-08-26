package com.bncam.core.capture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HdrExposureBracketPlannerTest {
    private val manualBounds = HdrManualSensorBounds(
        exposureTimeMinNs = 100_000L,
        exposureTimeMaxNs = 200_000_000L,
        sensitivityIsoMin = 50,
        sensitivityIsoMax = 6400,
        maxFrameDurationNs = 200_000_000L
    )

    @Test fun `manual bracket captures anchor highlights and shadows`() {
        val plan = HdrExposureBracketPlanner.plan(10_000_000L, 200, manualBounds, null, false)
        assertTrue(plan.enabled)
        assertEquals(HdrExposureControlMode.MANUAL_SENSOR, plan.controlMode)
        assertEquals(listOf(HdrBracketRole.ANCHOR, HdrBracketRole.HIGHLIGHT, HdrBracketRole.SHADOW), plan.frames.map { it.role })
        assertTrue(plan.frames[1].expectedExposureScaleToAnchor < 0.5f)
        assertTrue(plan.frames[2].expectedExposureScaleToAnchor > 2f)
        assertEquals(1f, plan.frames[0].expectedExposureScaleToAnchor, 1e-6f)
    }

    @Test fun `motion limits positive ev integration`() {
        val stable = HdrExposureBracketPlanner.plan(20_000_000L, 200, manualBounds, null, false)
        val moving = HdrExposureBracketPlanner.plan(20_000_000L, 200, manualBounds, null, true)
        val stableShadow = stable.frames.first { it.role == HdrBracketRole.SHADOW }
        val movingShadow = moving.frames.first { it.role == HdrBracketRole.SHADOW }
        assertTrue(moving.motionLimited)
        assertTrue((movingShadow.exposureTimeNs ?: Long.MAX_VALUE) <= (stableShadow.exposureTimeNs ?: 0L))
        assertTrue(movingShadow.targetEvFromAnchor < stableShadow.targetEvFromAnchor)
    }

    @Test fun `ae compensation is capability fallback`() {
        val plan = HdrExposureBracketPlanner.plan(
            baseExposureTimeNs = 10_000_000L,
            baseSensitivityIso = 100,
            manualBounds = null,
            aeBounds = HdrAeCompensationBounds(-6, 6, 1f / 3f),
            motionHigh = false
        )
        assertTrue(plan.enabled)
        assertEquals(HdrExposureControlMode.AE_COMPENSATION, plan.controlMode)
        assertTrue(plan.frames.any { it.aeCompensationIndex != null && it.aeCompensationIndex < 0 })
        assertTrue(plan.frames.any { it.aeCompensationIndex != null && it.aeCompensationIndex > 0 })
    }

    @Test fun `insufficient target capability disables instead of fabricating bracket`() {
        val plan = HdrExposureBracketPlanner.plan(10_000_000L, 100, null, HdrAeCompensationBounds(0, 0, 1f / 3f), false)
        assertFalse(plan.enabled)
        assertTrue(plan.fallbackReason.isNotBlank())
    }
    @Test fun `actual bracket rejects iso only pseudo hdr`() {
        val invalidHighlight = HdrExposureBracketPlanner.validateActualBracket(
            anchorExposureTimeNs = 10_000_000L,
            highlightExposureTimeNs = 10_000_000L,
            shadowExposureTimeNs = 20_000_000L,
            highlightExposureScaleToAnchor = 0.25f,
            shadowExposureScaleToAnchor = 2f
        )
        assertFalse(invalidHighlight.valid)
        assertEquals("highlight_source_did_not_reduce_integration", invalidHighlight.reason)

        val invalidShadow = HdrExposureBracketPlanner.validateActualBracket(
            anchorExposureTimeNs = 10_000_000L,
            highlightExposureTimeNs = 2_500_000L,
            shadowExposureTimeNs = 10_000_000L,
            highlightExposureScaleToAnchor = 0.25f,
            shadowExposureScaleToAnchor = 2f
        )
        assertFalse(invalidShadow.valid)
        assertEquals("shadow_source_did_not_add_integration", invalidShadow.reason)
    }

    @Test fun `actual bracket accepts real integration headroom and photons`() {
        val valid = HdrExposureBracketPlanner.validateActualBracket(
            anchorExposureTimeNs = 10_000_000L,
            highlightExposureTimeNs = 2_500_000L,
            shadowExposureTimeNs = 25_000_000L,
            highlightExposureScaleToAnchor = 0.25f,
            shadowExposureScaleToAnchor = 2.5f
        )
        assertTrue(valid.valid)
        assertEquals("none", valid.reason)
    }

}
