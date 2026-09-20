package com.bncam.core.capture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class Phase11HCaptureMatrixRegressionTest {
    private val allCapabilities = CaptureCapabilities(
        yuv = true,
        raw10 = true,
        rawSensor = true,
        camera2RawCapability = true
    )

    private fun plan(mode: CaptureMode, origin: FrameOrigin, requested: OutputPolicy): CaptureRequestPlan {
        val effective = CaptureOutputPolicyResolver.effectivePolicy(origin, requested)
        return CaptureRoutePlanner.plan(
            captureMode = mode,
            frameOrigin = origin,
            outputPolicy = effective,
            renderProfileId = "phase11h",
            cameraId = "0",
            capabilities = allCapabilities,
            debugPolicy = PerformanceDebugPolicy(false)
        )
    }

    @Test
    fun `YUV single and multi remain JPEG only and JPEG plus RAW degrades only the inapplicable RAW side`() {
        assertEquals(CaptureRoute.SingleYuvJpeg, plan(CaptureMode.SINGLE, FrameOrigin.YUV, OutputPolicy.JPEG).route)
        assertEquals(CaptureRoute.MultiYuvJpeg, plan(CaptureMode.MULTI, FrameOrigin.YUV, OutputPolicy.JPEG).route)
        assertEquals(CaptureRoute.SingleYuvJpeg, plan(CaptureMode.SINGLE, FrameOrigin.YUV, OutputPolicy.JPEG_PLUS_RAW).route)
        assertEquals(CaptureRoute.MultiYuvJpeg, plan(CaptureMode.MULTI, FrameOrigin.YUV, OutputPolicy.JPEG_PLUS_RAW).route)
        assertThrows(UnsupportedCaptureContractException::class.java) {
            plan(CaptureMode.SINGLE, FrameOrigin.YUV, OutputPolicy.RAW_ONLY)
        }
    }

    @Test
    fun `RAW10 single and multi preserve JPEG JPEG plus RAW and RAW only route families`() {
        assertEquals(CaptureRoute.SingleRaw10Jpeg, plan(CaptureMode.SINGLE, FrameOrigin.RAW10, OutputPolicy.JPEG).route)
        assertEquals(CaptureRoute.SingleRaw10Jpeg, plan(CaptureMode.SINGLE, FrameOrigin.RAW10, OutputPolicy.JPEG_PLUS_RAW).route)
        assertEquals(CaptureRoute.SingleRaw10RawOnly, plan(CaptureMode.SINGLE, FrameOrigin.RAW10, OutputPolicy.RAW_ONLY).route)
        assertEquals(CaptureRoute.MultiRaw10MasterRaw16Jpeg, plan(CaptureMode.MULTI, FrameOrigin.RAW10, OutputPolicy.JPEG).route)
        assertEquals(CaptureRoute.MultiRaw10MasterRaw16Jpeg, plan(CaptureMode.MULTI, FrameOrigin.RAW10, OutputPolicy.JPEG_PLUS_RAW).route)
        assertEquals(CaptureRoute.MultiRaw10RawOnly, plan(CaptureMode.MULTI, FrameOrigin.RAW10, OutputPolicy.RAW_ONLY).route)
    }

    @Test
    fun `RAW SENSOR single and multi preserve JPEG JPEG plus RAW and RAW only route families`() {
        assertEquals(CaptureRoute.SingleRawSensorJpeg, plan(CaptureMode.SINGLE, FrameOrigin.RAW_SENSOR, OutputPolicy.JPEG).route)
        assertEquals(CaptureRoute.SingleRawSensorJpeg, plan(CaptureMode.SINGLE, FrameOrigin.RAW_SENSOR, OutputPolicy.JPEG_PLUS_RAW).route)
        assertEquals(CaptureRoute.SingleRawSensorRawOnly, plan(CaptureMode.SINGLE, FrameOrigin.RAW_SENSOR, OutputPolicy.RAW_ONLY).route)
        assertEquals(CaptureRoute.MultiRawSensorMasterRaw16Jpeg, plan(CaptureMode.MULTI, FrameOrigin.RAW_SENSOR, OutputPolicy.JPEG).route)
        assertEquals(CaptureRoute.MultiRawSensorMasterRaw16Jpeg, plan(CaptureMode.MULTI, FrameOrigin.RAW_SENSOR, OutputPolicy.JPEG_PLUS_RAW).route)
        assertEquals(CaptureRoute.MultiRawSensorRawOnly, plan(CaptureMode.MULTI, FrameOrigin.RAW_SENSOR, OutputPolicy.RAW_ONLY).route)
    }
}
