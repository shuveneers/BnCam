package com.bncam.core.quality

import java.io.File
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class Phase4SingleFrameRawDenoiseSourceContractTest {
    @Test
    fun physicalPolicyIsSensorVarianceDrivenNotFormatOrIsoDriven() {
        val policy = File("src/main/cpp/SingleFrameRawDenoisePolicy.h").readText()
        assertTrue(policy.contains("meanSensorNoiseVariance"))
        assertTrue(policy.contains("resolvePhysicalChromaBaseStrength"))
        assertFalse(policy.contains("isRaw10"))
        assertFalse(policy.contains("RAW_SENSOR"))
        assertFalse(policy.contains("captureIso"))
        assertFalse(policy.contains("effectiveIso"))
    }

    @Test
    fun spectraOffPhysicalBaselineUsesResidentPass1AndPass2() {
        val isp = File("src/main/cpp/IspCore.cpp").readText()
        val runtime = File("src/main/cpp/vulkan/VulkanRuntime.cpp").readText()
        assertTrue(isp.contains("physicalRawDenoiseActive"))
        assertTrue(isp.contains("CAMERA2_SO_PHYSICAL_BASELINE"))
        assertTrue(isp.contains("pass1State.physicalBaselineMode = true"))
        assertTrue(isp.contains("pass2State.physicalBaselineMode = true"))
        assertTrue(runtime.contains("executeSpectraResidentPreDemosaicPass1FromRawNormalize"))
        assertTrue(isp.contains("PHYSICAL_SINGLE_FRAME_COMPACT_OBSERVER_READY"))
    }

    @Test
    fun downstreamPhysicalCleanupConsumesMeasuredResidualHeadroom() {
        val isp = File("src/main/cpp/IspCore.cpp").readText()
        val physical = File("src/main/cpp/SpectraPhysicalBaselineNr.h").readText()
        assertTrue(isp.contains("physicalPreDemosaicLumaReduction"))
        assertTrue(isp.contains("physicalPreDemosaicChromaReduction"))
        assertTrue(physical.contains("upstreamLumaReduction"))
        assertTrue(physical.contains("upstreamChromaReduction"))
        assertTrue(physical.contains("residualHeadroom"))
    }

    @Test
    fun physicalLowFrequencyChromaUsesSoAuthorityAndNeverSceneProxyBanding() {
        val isp = File("src/main/cpp/IspCore.cpp").readText()
        assertTrue(isp.contains("PHYSICAL_SINGLE_FRAME_LOW_FREQUENCY_CHROMA_READY"))
        assertTrue(isp.contains("singleFrameRawDenoise.lowFrequencyChromaAuthority"))
        assertTrue(isp.contains("pass3State.physicalBaselineMode = true"))
        assertTrue(isp.contains("pass3State.applyRowBanding = false"))
        assertTrue(isp.contains("pass3State.applyColBanding = false"))
        assertTrue(isp.contains("pass3State.bandingAuthority = 0.0f"))
        assertTrue(isp.contains("singleFrameRawPass3Physical="))
        assertTrue(isp.contains("singleFrameRawPass3LowFreqApplied="))
        assertTrue(isp.contains("singleFrameRawPass3RowBandingApplied="))
        assertTrue(isp.contains("singleFrameRawPass3ColBandingApplied="))
    }
}
