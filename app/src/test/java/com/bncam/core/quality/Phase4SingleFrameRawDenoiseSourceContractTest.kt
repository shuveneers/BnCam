package com.bncam.core.quality

import java.io.File
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class Phase4SingleFrameRawDenoiseSourceContractTest {
    private fun source(path: String): String = File(path).readText()

    @Test
    fun `raw capture has one physical chroma owner and no legacy denoisers`() {
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
        assertTrue(demosaic.contains("vec3 rawForWb = physicalChromaDenoise"))
        assertTrue(isp.contains("rawBaselinePhysicalChromaContract=true"))
    }

    @Test
    fun `spectra off bypasses neural processing but both modes receive baseline chroma`() {
        val isp = source("src/main/cpp/IspCore.cpp")
        val runtime = source("src/main/cpp/vulkan/VulkanRuntime.cpp")
        val cmake = source("src/main/cpp/CMakeLists.txt")

        assertTrue(isp.contains("meta.calibration.spectraProcessingMode == 0"))
        assertTrue(isp.contains("EXACT_USER_DISABLED_IDENTITY"))
        assertTrue(isp.contains("executeSpectraRawFinalizeFromRawNormalize"))
        assertTrue(isp.contains("executeSpectraNeuralThenRawFinalizeFromRawNormalize"))
        assertTrue(isp.contains("prepareNeuralProductionContext(neuralEvidence)"))
        assertTrue(isp.contains("rawZeroDenoiseNeuralProductionPath=PROFILE_GATED"))
        assertTrue(isp.contains("rawZeroDenoiseNeuralSubsystemConnected=true"))
        assertTrue(runtime.contains("configureSpectraNeuralModel"))
        assertTrue(cmake.contains("bncam_attach_spectra_neural_backend(bncam)"))
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
    fun `physical model owns baseline chroma while legacy raw filters remain disabled`() {
        val isp = source("src/main/cpp/IspCore.cpp")
        val planner = source("src/main/cpp/vulkan/shaders/spectra_pass3_planner.comp")

        assertTrue(isp.contains("preDemosaicAuthorityState.lumaAuthority = 0.0f"))
        assertTrue(isp.contains("preDemosaicAuthorityState.chromaAuthority = 0.0f"))
        assertTrue(isp.contains("preDemosaicAuthorityState.lowFrequencyAuthority = 0.0f"))
        assertTrue(isp.contains("budgetState.applied = false"))
        assertTrue(isp.contains("rawBaselineNoiseModelRole=PHYSICAL_CHROMA_AND_COVARIANCE"))
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
