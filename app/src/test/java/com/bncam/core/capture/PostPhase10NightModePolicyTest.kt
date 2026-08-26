package com.bncam.core.capture

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PostPhase10NightModePolicyTest {
    @Test
    fun `night mode raises frame floor as exposure product becomes darker`() {
        val dim = NightCapturePolicy.resolve(
            origin = FrameOrigin.RAW10,
            measuredIso = 200,
            measuredExposureNs = 10_000_000L,
            profileRequestedFrames = 2,
            runtimeSafeMaximum = 25
        )
        val dark = NightCapturePolicy.resolve(
            origin = FrameOrigin.RAW10,
            measuredIso = 1600,
            measuredExposureNs = 33_000_000L,
            profileRequestedFrames = 2,
            runtimeSafeMaximum = 25
        )
        assertEquals(NightSceneClass.DIM, dim.sceneClass)
        assertEquals(6, dim.requestedFrames)
        assertEquals(NightSceneClass.VERY_DARK, dark.sceneClass)
        assertEquals(10, dark.requestedFrames)
        assertTrue(dark.requestedFrames > dim.requestedFrames)
    }

    @Test
    fun `profile may request more frames and runtime authority still clamps`() {
        val profileHigher = NightCapturePolicy.resolve(
            origin = FrameOrigin.YUV,
            measuredIso = 100,
            measuredExposureNs = 5_000_000L,
            profileRequestedFrames = 14,
            runtimeSafeMaximum = 25
        )
        val rawSensorClamped = NightCapturePolicy.resolve(
            origin = FrameOrigin.RAW_SENSOR,
            measuredIso = 3200,
            measuredExposureNs = 66_000_000L,
            profileRequestedFrames = 10,
            runtimeSafeMaximum = 7
        )
        assertEquals(14, profileHigher.requestedFrames)
        assertEquals(7, rawSensorClamped.requestedFrames)
    }
}
