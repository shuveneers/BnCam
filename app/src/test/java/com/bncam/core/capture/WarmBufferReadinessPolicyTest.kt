package com.bncam.core.capture

import android.graphics.ImageFormat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WarmBufferReadinessPolicyTest {
    @Test
    fun singleFrameAdmissionRequiresExactlyOneCompletePairForEveryProductionFormat() {
        val yuv = WarmBufferReadinessPolicy.captureRoute(
            format = ImageFormat.YUV_420_888,
            captureMode = CaptureMode.SINGLE,
            requestedFrameCount = 4,
            bufferCapacity = 20
        )
        val raw10 = WarmBufferReadinessPolicy.captureRoute(
            format = ImageFormat.RAW10,
            captureMode = CaptureMode.SINGLE,
            requestedFrameCount = 4,
            bufferCapacity = 12
        )
        val rawSensor = WarmBufferReadinessPolicy.captureRoute(
            format = ImageFormat.RAW_SENSOR,
            captureMode = CaptureMode.SINGLE,
            requestedFrameCount = 4,
            bufferCapacity = 6
        )

        assertEquals(1, yuv.requiredCompleteFrames)
        assertEquals(1, raw10.requiredCompleteFrames)
        assertEquals(1, rawSensor.requiredCompleteFrames)
    }

    @Test
    fun streamHealthStillUsesMultipleFramesIndependentlyFromShutterAdmission() {
        assertEquals(3, WarmBufferReadinessPolicy.streamHealth(ImageFormat.YUV_420_888).requiredCompleteFrames)
        assertEquals(3, WarmBufferReadinessPolicy.streamHealth(ImageFormat.RAW10).requiredCompleteFrames)
        assertEquals(2, WarmBufferReadinessPolicy.streamHealth(ImageFormat.RAW_SENSOR).requiredCompleteFrames)
    }

    @Test
    fun multiFrameRequirementUsesOneExistingAnchorNotFullFusionTarget() {
        val raw10 = WarmBufferReadinessPolicy.captureRoute(
            format = ImageFormat.RAW10,
            captureMode = CaptureMode.MULTI,
            requestedFrameCount = 12,
            bufferCapacity = 8
        )
        val rawSensor = WarmBufferReadinessPolicy.captureRoute(
            format = ImageFormat.RAW_SENSOR,
            captureMode = CaptureMode.MULTI,
            requestedFrameCount = 8,
            bufferCapacity = 6
        )

        assertEquals(1, raw10.requiredCompleteFrames)
        assertEquals(1, rawSensor.requiredCompleteFrames)
    }

    @Test
    fun bufferHealthWindowIsNotShutterCandidateWindow() {
        val yuvHealth =
            WarmBufferReadinessPolicy.streamHealth(ImageFormat.YUV_420_888)
        val rawHealth =
            WarmBufferReadinessPolicy.streamHealth(ImageFormat.RAW_SENSOR)

        assertEquals(350.0, yuvHealth.streamHealthFreshnessWindowMs, 0.0)
        assertEquals(500.0, rawHealth.streamHealthFreshnessWindowMs, 0.0)
        assertTrue(
            ShutterCandidateFreshnessPolicy.GENUINE_NEAR_ZSL_WINDOW_MS <
                    yuvHealth.streamHealthFreshnessWindowMs
        )
    }
}
