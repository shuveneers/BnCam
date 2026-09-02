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
        assertEquals("FRAME_EXPOSURE_TOO_SHORT_FOR_CURRENT_TRANSITION", decision.reason)
    }

    @Test
    fun neighbouringLongerFrameRemainsEligibleWhileWarmRingRefills() {
        val decision = FrameSelectionExposurePolicy.evaluate(31_000_000L, 30_000_000L)
        assertTrue(decision.eligible)
        assertEquals(400_000L, decision.deviationNs)
        assertEquals("TRANSITIONAL_WARM_FRAME", decision.reason)
    }

    @Test
    fun grosslyLongerFrameStillFailsClosed() {
        val decision = FrameSelectionExposurePolicy.evaluate(20_000_000L, 10_000_000L)
        assertFalse(decision.eligible)
        assertEquals("FRAME_EXPOSURE_TOO_LONG_FOR_CURRENT_TRANSITION", decision.reason)
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

    @Test
    fun staleHighIsoRemainsShutterEligibleButIsNotProductPreferred() {
        val decision = FrameSelectionExposurePolicy.evaluate(
            actualExposureNs = 20_000_000L,
            requestedExposureTargetNs = 20_000_000L,
            actualIso = 5_190,
            requestedIso = 1_455
        )
        assertTrue(decision.eligible)
        assertFalse(decision.productPreferred)
        assertEquals("SHUTTER_ELIGIBLE_PRODUCT_TOO_HIGH_FALLBACK", decision.reason)
        assertTrue((decision.exposureErrorEv ?: 0.0) > 1.5)
    }

    @Test
    fun staleLowIsoRemainsShutterEligibleButIsNotProductPreferred() {
        val decision = FrameSelectionExposurePolicy.evaluate(
            actualExposureNs = 20_000_000L,
            requestedExposureTargetNs = 20_000_000L,
            actualIso = 1_000,
            requestedIso = 2_000
        )
        assertTrue(decision.eligible)
        assertFalse(decision.productPreferred)
        assertEquals("SHUTTER_ELIGIBLE_PRODUCT_TOO_LOW_FALLBACK", decision.reason)
    }

    @Test
    fun neighbouringShutterWithCompensatingIsoRemainsEligible() {
        val decision = FrameSelectionExposurePolicy.evaluate(
            actualExposureNs = 20_000_000L,
            requestedExposureTargetNs = 15_000_000L,
            actualIso = 1_500,
            requestedIso = 2_000
        )
        assertTrue(decision.eligible)
        assertTrue(decision.productPreferred)
        assertEquals("TRANSITIONAL_WARM_FRAME_EXPOSURE_COMPENSATED", decision.reason)
        assertEquals(0.0, decision.exposureErrorEv ?: 99.0, 1.0e-9)
    }

    @Test
    fun missingIsoKeepsShutterEligibleAsNonPreferredFallback() {
        val decision = FrameSelectionExposurePolicy.evaluate(
            actualExposureNs = 20_000_000L,
            requestedExposureTargetNs = 20_000_000L,
            actualIso = null,
            requestedIso = 2_000
        )
        assertTrue(decision.eligible)
        assertFalse(decision.productPreferred)
        assertEquals("SHUTTER_ELIGIBLE_UNPROVEN_ISO_FOR_PREFERENCE", decision.reason)
    }

    @Test
    fun noIsoTargetPreserves0154ShutterOnlyTransitionContract() {
        val decision = FrameSelectionExposurePolicy.evaluate(
            actualExposureNs = 20_000_000L,
            requestedExposureTargetNs = 14_920_000L,
            actualIso = 6_000,
            requestedIso = null
        )
        assertTrue(decision.eligible)
        assertTrue(decision.productPreferred)
        assertEquals("TRANSITIONAL_WARM_FRAME", decision.reason)
    }

    @Test
    fun shutterUnsafeFrameStillRejectsEvenWhenIsoWouldCompensateProduct() {
        val decision = FrameSelectionExposurePolicy.evaluate(
            actualExposureNs = 20_000_000L,
            requestedExposureTargetNs = 10_000_000L,
            actualIso = 1_000,
            requestedIso = 2_000
        )
        assertFalse(decision.eligible)
        assertEquals("FRAME_EXPOSURE_TOO_LONG_FOR_CURRENT_TRANSITION", decision.reason)
    }

}
