package com.bncam.core.quality

import java.io.File
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class Phase4SingleFrameRawDenoiseSourceContractTest {
    @Test
    fun physicalPreDemosaicPolicyIsLumaOnlyAndSensorVarianceDriven() {
        val policy = File("src/main/cpp/SingleFrameRawDenoisePolicy.h").readText()
        assertTrue(policy.contains("meanSensorNoiseVariance"))
        assertTrue(policy.contains("lumaAuthority"))
        assertFalse(policy.contains("chromaAuthority"))
        assertFalse(policy.contains("lowFrequencyChromaAuthority"))
        assertFalse(policy.contains("captureIso"))
        assertFalse(policy.contains("effectiveIso"))
        assertFalse(policy.contains("isRaw10"))
    }

    @Test
    fun physicalCfaLumaUsesUnbiasedGeneralizedAnscombeInverseApproximation() {
        val shader = File("src/main/cpp/vulkan/shaders/spectra_pass1_resident.comp").readText()
        assertTrue(shader.contains("inverseVstUnbiasedApprox"))
        assertTrue(shader.contains("Mäkitalo-Foi"))
        assertFalse(shader.contains("float inverseVst(float y"))
        assertTrue(shader.contains("pc.physicalBaselineMode == 0u && midRing.valid"))
        assertTrue(shader.contains("pc.physicalBaselineMode != 0u\n            ? 1.0"))
    }

    @Test
    fun lateLumaUsesPropagatedResidualAndNoIsoOrRenderGainHeuristic() {
        val shader = File("src/main/cpp/vulkan/shaders/spectra_post_demosaic_resident.comp").readText()
        val backend = File("src/main/cpp/vulkan/VulkanSpectraResidentPostDemosaicBackend.h").readText()
        val isp = File("src/main/cpp/IspCore.cpp").readText()

        assertTrue(shader.contains("inputResidualLumaSigma"))
        assertTrue(shader.contains("predictedResidualVariance"))
        assertTrue(shader.contains("estimatedSignalVariance"))
        assertTrue(shader.contains("noiseDominance"))
        assertTrue(shader.contains("correctionFraction"))
        assertTrue(shader.contains("microDetailConfidence"))
        assertFalse(shader.contains("lumaUserBoost"))
        assertFalse(shader.contains("noiseModelMultiplier"))
        assertFalse(shader.contains("configuredDynamicIsoCoeff"))

        assertTrue(backend.contains("inputResidualLumaSigma"))
        assertFalse(backend.contains("noiseModelMultiplier"))
        assertFalse(backend.contains("configuredDynamicIsoCoeff"))
        assertTrue(isp.contains("spectraResidualNr.inputLumaSigma"))
        assertTrue(isp.contains("postToneResidualLumaSigma"))
    }

    @Test
    fun deadPhysicalLumaOwnersAreRemovedRatherThanDisabled() {
        val isp = File("src/main/cpp/IspCore.cpp").readText()
        val physical = File("src/main/cpp/SpectraPhysicalBaselineNr.h").readText()
        assertFalse(physical.contains("PhysicalBaselineNrPlan"))
        assertFalse(physical.contains("resolvePhysicalBaselineNr"))
        assertFalse(physical.contains("resolvePhysicalPreToneLumaAuthority"))
        assertFalse(physical.contains("renderGainPressure"))
        assertFalse(physical.contains("captureIsoPressure"))
        assertFalse(isp.contains("galoshPreToneLumaBaselineAuthority"))
        assertFalse(isp.contains("residualLumaStrengthScale"))
    }

    @Test
    fun separatePhysicalChromaOwnersRemainConnected() {
        val isp = File("src/main/cpp/IspCore.cpp").readText()
        val physical = File("src/main/cpp/SpectraPhysicalBaselineNr.h").readText()
        assertTrue(physical.contains("resolvePhysicalChromaBaseStrength"))
        assertTrue(physical.contains("resolvePhysicalPreToneChroma"))
        assertTrue(isp.contains("PHYSICAL_SINGLE_FRAME_LOW_FREQUENCY_CHROMA_READY"))
        assertTrue(isp.contains("pass3State.applyRowBanding = false"))
        assertTrue(isp.contains("pass3State.applyColBanding = false"))
        assertTrue(isp.contains("state.bandingAuthority = 0.0f"))
    }
}
