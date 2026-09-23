package com.bncam.core.quality

import java.io.File
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class Phase4SingleFrameRawDenoiseSourceContractTest {
    private fun source(path: String): String = File(path).readText()

    @Test
    fun `neural remains optional enhancement while baseline chroma owner stays narrow`() {
        val demosaicShader = source("src/main/cpp/vulkan/shaders/spectra_demosaic_resident.comp")
        val toneShader = source("src/main/cpp/vulkan/shaders/spectra_tone_resident.comp")
        val demosaicBackend = source("src/main/cpp/vulkan/VulkanSpectraResidentDemosaicBackend.cpp")
        val pipelineHeader = source("src/main/cpp/vulkan/VulkanComputePipelineManager.h")
        val pipelineSource = source("src/main/cpp/vulkan/VulkanComputePipelineManager.cpp")

        assertFalse(demosaicShader.contains("phase6ClassifyAndStore"))
        assertFalse(demosaicShader.contains("phase6ApplyCorrection"))
        assertFalse(demosaicShader.contains("preWbCloudCorrect"))
        assertTrue(demosaicShader.contains("baselineDetailPreservingChromaAt"))
        assertTrue(demosaicShader.contains("Green is copied bit-for-bit"))
        assertFalse(demosaicShader.contains("neuralJddAt"))
        assertFalse(demosaicShader.contains("JDD_W1"))

        assertFalse(toneShader.contains("preToneChroma444FromInput"))
        assertFalse(toneShader.contains("preToneFirmResidualKeep"))
        assertFalse(toneShader.contains("GALOSH-style residual cleanup"))

        assertFalse(demosaicBackend.contains("push.mode = 9u"))
        assertFalse(demosaicBackend.contains("push.mode = 10u"))
        assertTrue(demosaicBackend.contains("result.phase6ResidualChromaUsedForOutput = false"))

        assertFalse(pipelineHeader.contains("executeIspLumaDenoise"))
        assertFalse(pipelineHeader.contains("executeIspChromaDenoise"))
        assertFalse(pipelineSource.contains("getIspLumaDenoiseSpirv"))
        assertFalse(pipelineSource.contains("getIspChromaDenoiseSpirv"))
    }

    @Test
    fun `retired JDD cannot regain denoise pixel authority`() {
        val shader = source("src/main/cpp/vulkan/shaders/spectra_demosaic_resident.comp")
        val backend = source("src/main/cpp/vulkan/VulkanSpectraResidentDemosaicBackend.cpp")

        assertTrue(shader.contains("pc.mode == 0u ? bilinearAt(x, y) : malvarAt(x, y)"))
        assertFalse(shader.contains("neuralJddResidualAt"))
        assertTrue(backend.contains("legacy direct requests cannot regain denoise pixel authority"))
        assertTrue(backend.contains("case SpectraGpuDemosaicAlgorithm::NEURAL_JDD:"))
        assertTrue(backend.contains("push.mode = 1u"))
    }

    @Test
    fun `observer stages remain read only while classical authorities are neutral`() {
        val isp = source("src/main/cpp/IspCore.cpp")
        val planner = source("src/main/cpp/vulkan/shaders/spectra_pass3_planner.comp")

        assertTrue(isp.contains("preDemosaicAuthorityState.lumaAuthority = 0.0f"))
        assertTrue(isp.contains("preDemosaicAuthorityState.chromaAuthority = 0.0f"))
        assertTrue(isp.contains("preDemosaicAuthorityState.lowFrequencyAuthority = 0.0f"))
        assertTrue(isp.contains("budgetState.applied = false"))
        assertTrue(planner.contains("This shader has no pixel output and cannot perform denoise or correction"))
    }

    @Test
    fun `post demosaic baseline chroma is physical adaptive and detail protected`() {
        val shader = source("src/main/cpp/vulkan/shaders/spectra_demosaic_resident.comp")
        val isp = source("src/main/cpp/IspCore.cpp")
        val policy = source("src/main/cpp/RawAdaptiveBaselineChroma.h")

        assertTrue(shader.contains("baselineDetailPreservingChromaAt"))
        assertTrue(shader.contains("return vec3(center.r - correctionRg, center.g, center.b - correctionBg);"))
        assertTrue(shader.contains("greenDetailGate"))
        assertTrue(shader.contains("chromaDetailGate"))
        assertTrue(isp.contains("resolveAdaptiveChromaPlan"))
        assertTrue(isp.contains("wbCcmRedOpponentDirectionalGain"))
        assertTrue(isp.contains("demosaicMeasuredPostVarianceRg"))
        assertTrue(policy.contains("physicalNoiseModelAvailable"))
        assertFalse(policy.contains("front"))
        assertFalse(policy.contains("ultrawide"))
        assertFalse(policy.contains("tele"))
    }

    @Test
    fun `tone mode zero is identity preparation not classical denoise`() {
        val shader = source("src/main/cpp/vulkan/shaders/spectra_tone_resident.comp")
        val backend = source("src/main/cpp/vulkan/VulkanSpectraResidentToneBackend.cpp")

        assertTrue(shader.contains("writeWorking(gid, inputAt(int(gid.x), int(gid.y)));"))
        assertTrue(backend.contains("constexpr bool preToneChroma444Requested = false"))
        assertTrue(backend.contains("constexpr bool preToneChromaCovarianceWhiteningRequested = false"))
    }
}
