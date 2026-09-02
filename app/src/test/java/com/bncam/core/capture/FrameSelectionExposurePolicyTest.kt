package com.bncam.core.capture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FrameSelectionExposurePolicyTest {
    @Test
    fun exactRequestedExposureIsEligible() {
        val decision = FrameSelectionExposurePolicy.evaluate(30_000_000L, 30_000_000L)
        assertTrue(decision.eligible)
        assertEquals("WITHIN_CURRENT_EXPOSURE_CONTRACT", decision.reason)
    }

    @Test
    fun modestApi36ShortRealizationIsTolerated() {
        val decision = FrameSelectionExposurePolicy.evaluate(28_800_000L, 30_000_000L)
        assertTrue(decision.eligible)
    }

    @Test
    fun materiallyShortOlderFrameIsRejectedAfterQualityExtension() {
        val decision = FrameSelectionExposurePolicy.evaluate(20_000_000L, 80_000_000L)
        assertFalse(decision.eligible)
        assertEquals("FRAME_EXPOSURE_BELOW_CURRENT_TARGET", decision.reason)
    }

    @Test
    fun materiallyLongerFrameIsRejectedAfterMotionTightening() {
        val decision = FrameSelectionExposurePolicy.evaluate(31_000_000L, 30_000_000L)
        assertFalse(decision.eligible)
        assertEquals(400_000L, decision.deviationNs)
        assertEquals("FRAME_EXPOSURE_ABOVE_CURRENT_TARGET", decision.reason)
    }

    @Test
    fun missingFrameExposureFailsClosed() {
        val decision = FrameSelectionExposurePolicy.evaluate(0L, 30_000_000L)
        assertFalse(decision.eligible)
        assertEquals("UNPROVEN_FRAME_EXPOSURE", decision.reason)
    }

    @Test
    fun shortExposureGetsAbsoluteToleranceFloor() {
        val decision = FrameSelectionExposurePolicy.evaluate(950_000L, 1_000_000L)
        assertTrue(decision.eligible)
        assertEquals(900_000L, decision.toleratedExposureMinNs)
        assertEquals(1_100_000L, decision.toleratedExposureMaxNs)
    }

    @Test
    fun upperToleranceArithmeticSaturates() {
        val decision = FrameSelectionExposurePolicy.evaluate(Long.MAX_VALUE, Long.MAX_VALUE - 10L)
        assertTrue(decision.eligible)
        assertEquals(Long.MAX_VALUE, decision.toleratedExposureMaxNs)
    }
}
