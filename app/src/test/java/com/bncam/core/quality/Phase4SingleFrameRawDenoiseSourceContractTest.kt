package com.bncam.core.quality

import java.io.File
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class Phase4SingleFrameRawDenoiseSourceContractTest {
    private fun source(path: String): String = File(path).readText()

    @Test
    fun `raw capture has no pre or post demosaic denoise owner`() {
        val rawFinalize = source("src/main/cpp/vulkan/shaders/spectra_raw_finalize.comp")
        val demosaic = source("src/main/cpp/vulkan/shaders/spectra_demosaic_resident.comp")
        val backendHeader = source("src/main/cpp/vulkan/VulkanSpectraResidentDemosaicBackend.h")
        val backend = source("src/main/cpp/vulkan/VulkanSpectraResidentDemosaicBackend.cpp")
        val isp = source("src/main/cpp/IspCore.cpp")

        assertFalse(rawFinalize.contains("baselineNoiseReducedAt"))
        assertFalse(rawFinalize.contains("Local Wiener shrinkage"))
        assertFalse(demosaic.contains("baselineDetailPreservingChromaAt"))
        assertFalse(demosaic.contains("baselineDirectionCorrection"))
        assertFalse(backendHeader.contains("preWbOpponentCleanupBlend"))
        assertFalse(backendHeader.contains("baselineChromaSigma"))
        assertFalse(backend.contains("baselineChromaCleanupApplied"))
        assertFalse(isp.contains("resolveAdaptiveChromaPlan"))
        assertFalse(isp.contains("RawAdaptiveBaselineChroma"))
        assertTrue(demosaic.contains("vec3 rawForWb = rawInput;"))
        assertTrue(isp.contains("rawZeroDenoiseContract=true"))
    }

    @Test
    fun `neural denoise is disconnected from raw production`() {
        val isp = source("src/main/cpp/IspCore.cpp")

        assertFalse(isp.contains("executeSpectraNeuralThenRawFinalizeFromRawNormalize"))
        assertFalse(isp.contains("SpectraNeuralProductionTrace"))
        assertFalse(isp.contains("prepareNeuralProductionContext"))
        assertFalse(isp.contains("neuralProductionTrace"))
        assertTrue(isp.contains("rawZeroDenoiseNeuralProductionPath=false"))
        assertTrue(isp.contains("rawZeroDenoiseNeuralSubsystemConnected=false"))
    }

    @Test
    fun `raw preview has no luma or chroma denoise stage`() {
        val shader = source("src/main/cpp/vulkan/shaders/raw_preview.comp")
        val backendHeader = source("src/main/cpp/vulkan/VulkanRawPreviewBackend.h")
        val backend = source("src/main/cpp/vulkan/VulkanRawPreviewBackend.cpp")
        val preview = source("src/main/cpp/RawPreview.cpp")

        assertFalse(shader.contains("applyPreviewProfileNoiseReduction"))
        assertFalse(shader.contains("filteredChroma"))
        assertFalse(shader.contains("lumaDenoiseStrength"))
        assertFalse(shader.contains("filteredGreen"))
        assertFalse(backendHeader.contains("profileNrLuminance"))
        assertFalse(backendHeader.contains("profileNrColor"))
        assertFalse(backend.contains("profileNrSeeds"))
        assertFalse(preview.contains("previewRequest.profileNr"))
        assertTrue(shader.contains("RAW preview zero-denoise baseline"))
    }

    @Test
    fun `noise model remains measurement only not denoise authority`() {
        val isp = source("src/main/cpp/IspCore.cpp")
        val planner = source("src/main/cpp/vulkan/shaders/spectra_pass3_planner.comp")

        assertTrue(isp.contains("preDemosaicAuthorityState.lumaAuthority = 0.0f"))
        assertTrue(isp.contains("preDemosaicAuthorityState.chromaAuthority = 0.0f"))
        assertTrue(isp.contains("preDemosaicAuthorityState.lowFrequencyAuthority = 0.0f"))
        assertTrue(isp.contains("budgetState.applied = false"))
        assertTrue(isp.contains("rawZeroDenoiseNoiseModelRole=TELEMETRY_AND_COVARIANCE_ONLY"))
        assertTrue(planner.contains("This shader has no pixel output and cannot perform denoise or correction"))
    }

    @Test
    fun `tone preparation remains identity rather than hidden denoise`() {
        val shader = source("src/main/cpp/vulkan/shaders/spectra_tone_resident.comp")
        assertTrue(shader.contains("writeWorking(gid, inputAt(int(gid.x), int(gid.y)));"))
        assertFalse(shader.contains("preToneChroma444FromInput"))
        assertFalse(shader.contains("preToneFirmResidualKeep"))
    }
}
