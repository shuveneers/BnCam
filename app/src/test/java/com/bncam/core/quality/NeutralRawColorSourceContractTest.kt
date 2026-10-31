package com.bncam.core.quality

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Test

/** Source contract for neutral RAW: exactly one physical camera colour owner. */
class NeutralRawColorSourceContractTest {
    @Test
    fun `neutral raw accepts only verified paired DNG colour and leaves profile colour untouched`() {
        val isp = File("src/main/cpp/IspCore.cpp").readText()
        val policy = File("src/main/cpp/NeutralRawHueSatAuthorityPolicy.h").readText()
        val tone = File("src/main/cpp/vulkan/shaders/spectra_tone_resident.comp").readText()

        assertTrue(isp.contains("useCalibratedDngMatrixForNeutralRaw(neutralRawColorInput)"))
        assertTrue(isp.contains("usePairedDngHueSatForNeutralRaw(neutralRawColorInput)"))
        assertTrue(isp.contains("preserveExactCamera2Pair && !neutralRawCalibratedMatrixRequested"))
        assertTrue(isp.contains("request.calibratedHueSatMapEnabled = calibratedHueSatMapActive;"))
        assertTrue(policy.contains("in.pairedForwardTransformReady"))
        assertTrue(policy.contains("in.genuineHueSatMapAvailable"))
        assertTrue(tone.contains("pc.rawJpegBaseVibrance >= 0.0"))
        assertTrue(isp.contains("request.rawJpegBaseVibrance = neutralDefaultRaw ? -1.0f : 1.0f;"))
        assertFalse(tone.contains("applyBroadShadowPlacement"))
    }
}
