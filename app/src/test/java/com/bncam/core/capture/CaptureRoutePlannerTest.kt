package com.bncam.core.capture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class CaptureRoutePlannerTest {
    private val allCapabilities = CaptureCapabilities(
        yuv = true,
        raw10 = true,
        rawSensor = true,
        camera2RawCapability = true
    )

    @Test
    fun singleYuvJpegIsDirectRoute() {
        val plan = plan(CaptureMode.SINGLE, FrameOrigin.YUV, OutputPolicy.JPEG)
        assertEquals(CaptureRoute.SingleYuvJpeg, plan.route)
    }

    @Test
    fun rawOnlyHasDistinctSingleAndMultiRoutes() {
        assertEquals(
            CaptureRoute.SingleRaw10RawOnly,
            plan(CaptureMode.SINGLE, FrameOrigin.RAW10, OutputPolicy.RAW_ONLY).route
        )
        assertEquals(
            CaptureRoute.MultiRawSensorRawOnly,
            plan(CaptureMode.MULTI, FrameOrigin.RAW_SENSOR, OutputPolicy.RAW_ONLY).route
        )
    }

    @Test
    fun yuvRawOutputIsRejected() {
        assertThrows(UnsupportedCaptureContractException::class.java) {
            plan(CaptureMode.SINGLE, FrameOrigin.YUV, OutputPolicy.RAW_ONLY)
        }
    }

    @Test
    fun yuvJpegPlusRawKeepsTheApplicableJpegOutput() {
        val effective = CaptureOutputPolicyResolver.effectivePolicy(
            frameOrigin = FrameOrigin.YUV,
            requestedPolicy = OutputPolicy.JPEG_PLUS_RAW
        )

        assertEquals(OutputPolicy.JPEG, effective)
        assertEquals(
            CaptureRoute.MultiYuvJpeg,
            plan(CaptureMode.MULTI, FrameOrigin.YUV, effective).route
        )
    }

    @Test
    fun outputPolicyResolverDoesNotChangeRawOrExplicitRawOnlyRequests() {
        assertEquals(
            OutputPolicy.JPEG_PLUS_RAW,
            CaptureOutputPolicyResolver.effectivePolicy(
                FrameOrigin.RAW10,
                OutputPolicy.JPEG_PLUS_RAW
            )
        )
        assertEquals(
            OutputPolicy.JPEG_PLUS_RAW,
            CaptureOutputPolicyResolver.effectivePolicy(
                FrameOrigin.RAW_SENSOR,
                OutputPolicy.JPEG_PLUS_RAW
            )
        )
        assertEquals(
            OutputPolicy.RAW_ONLY,
            CaptureOutputPolicyResolver.effectivePolicy(
                FrameOrigin.YUV,
                OutputPolicy.RAW_ONLY
            )
        )
    }

    @Test
    fun rawStreamWithoutRawCapabilityIsRejected() {
        assertThrows(UnsupportedCaptureContractException::class.java) {
            CaptureRoutePlanner.plan(
                captureMode = CaptureMode.SINGLE,
                frameOrigin = FrameOrigin.RAW10,
                outputPolicy = OutputPolicy.JPEG,
                renderProfileId = "profile",
                cameraId = "0",
                capabilities = allCapabilities.copy(camera2RawCapability = false),
                debugPolicy = PerformanceDebugPolicy(false)
            )
        }
    }

    private fun plan(mode: CaptureMode, origin: FrameOrigin, output: OutputPolicy): CaptureRequestPlan =
        CaptureRoutePlanner.plan(
            captureMode = mode,
            frameOrigin = origin,
            outputPolicy = output,
            renderProfileId = "profile",
            cameraId = "0",
            capabilities = allCapabilities,
            debugPolicy = PerformanceDebugPolicy(false)
        )
}
