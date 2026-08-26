package com.bncam.core.capture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FrameCapacityPolicyTest {
    @Test
    fun configuredValueWithinLimitsIsUnchanged() {
        val result = FrameCapacityPolicy.resolveProcessingFrames(
            origin = FrameOrigin.YUV,
            captureMode = CaptureMode.MULTI,
            requestedValue = 12,
            runtimeSafeMaximum = 35
        )
        assertEquals(12, result.effectiveValue)
        assertEquals(FrameCapacityResolutionReason.AS_REQUESTED, result.resolutionReason)
        assertFalse(result.wasClamped)
    }

    @Test
    fun configuredAndProductLimitsHaveTypedReasons() {
        val configured = FrameCapacityPolicy.resolveProcessingFrames(
            origin = FrameOrigin.RAW10,
            captureMode = CaptureMode.MULTI,
            requestedValue = 20,
            configuredMaximum = 12,
            runtimeSafeMaximum = 35
        )
        assertEquals(12, configured.effectiveValue)
        assertEquals(FrameCapacityResolutionReason.CONFIGURED_MAXIMUM, configured.resolutionReason)

        val product = FrameCapacityPolicy.resolveProcessingFrames(
            origin = FrameOrigin.RAW_SENSOR,
            captureMode = CaptureMode.MULTI,
            requestedValue = 25,
            configuredMaximum = 25,
            runtimeSafeMaximum = 25
        )
        assertEquals(10, product.effectiveValue)
        assertEquals(
            FrameCapacityResolutionReason.PRODUCT_SOURCE_MAXIMUM,
            product.resolutionReason
        )
    }

    @Test
    fun runtimeSafeLimitBelowProductLimitIsExplicit() {
        val result = FrameCapacityPolicy.resolveWarmBuffer(
            origin = FrameOrigin.YUV,
            runtimeSafeMaximum = 18
        )
        assertEquals(35, result.requestedValue)
        assertEquals(18, result.runtimeSafeMaximum)
        assertEquals(18, result.effectiveValue)
        assertTrue(
            FrameCapacityResolutionReason.RUNTIME_SAFE_MAXIMUM in result.limitingReasons
        )
    }

    @Test
    fun productTargetsAreCorrectForEverySource() {
        assertEquals(35, FrameCapacityPolicy.warmBufferTarget(FrameOrigin.YUV))
        assertEquals(35, FrameCapacityPolicy.warmBufferTarget(FrameOrigin.RAW10))
        assertEquals(15, FrameCapacityPolicy.warmBufferTarget(FrameOrigin.RAW_SENSOR))
        assertEquals(25, FrameCapacityPolicy.maximumProcessingFrames(FrameOrigin.YUV))
        assertEquals(25, FrameCapacityPolicy.maximumProcessingFrames(FrameOrigin.RAW10))
        assertEquals(10, FrameCapacityPolicy.maximumProcessingFrames(FrameOrigin.RAW_SENSOR))
    }

    @Test
    fun singleAndMultiFrameRestrictionsResolveDifferently() {
        val single = FrameCapacityPolicy.resolveProcessingFrames(
            origin = FrameOrigin.RAW10,
            captureMode = CaptureMode.SINGLE,
            requestedValue = 9,
            runtimeSafeMaximum = 35
        )
        val multi = FrameCapacityPolicy.resolveProcessingFrames(
            origin = FrameOrigin.RAW10,
            captureMode = CaptureMode.MULTI,
            requestedValue = 9,
            runtimeSafeMaximum = 35
        )
        assertEquals(1, single.effectiveValue)
        assertEquals(
            FrameCapacityResolutionReason.SINGLE_FRAME_RESTRICTION,
            single.resolutionReason
        )
        assertEquals(9, multi.effectiveValue)
    }

    @Test
    fun yuvDngMasterIsNotApplicable() {
        val result = FrameCapacityPolicy.resolveDngMasterFrames(
            origin = FrameOrigin.YUV,
            captureMode = CaptureMode.MULTI,
            requestedValue = 5,
            runtimeSafeMaximum = 35
        )
        assertEquals(0, result.effectiveValue)
        assertEquals(
            FrameCapacityResolutionReason.SOURCE_NOT_APPLICABLE,
            result.resolutionReason
        )
    }

    @Test
    fun jpegOnlyDngMasterIsNotApplicable() {
        val result = FrameCapacityPolicy.resolveDngMasterFrames(
            origin = FrameOrigin.RAW10,
            captureMode = CaptureMode.MULTI,
            requestedValue = 4,
            runtimeSafeMaximum = 35,
            outputPolicy = OutputPolicy.JPEG
        )
        assertEquals(0, result.effectiveValue)
        assertEquals(
            FrameCapacityResolutionReason.OUTPUT_NOT_APPLICABLE,
            result.resolutionReason
        )
    }
}
