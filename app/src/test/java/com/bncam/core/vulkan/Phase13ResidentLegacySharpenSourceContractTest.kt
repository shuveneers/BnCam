package com.bncam.core.vulkan

import java.io.File
import org.junit.Test
import org.junit.Assert.assertTrue

class Phase13ResidentLegacySharpenSourceContractTest {
    private val appDir = File(System.getProperty("user.dir"))
    private fun source(path: String): String = File(appDir, path).readText()

    @Test
    fun spectraOffLegacySharpenRunsBeforePublicationReadbackOnResidentGpuPath() {
        val isp = source("src/main/cpp/IspCore.cpp")
        assertTrue(isp.contains("residentLegacySharpenRequested"))
        assertTrue(isp.contains("(isNoiseModelActive || residentLegacySharpenRequested)"))
        assertTrue(isp.contains("request.legacySharpenAmount"))
        assertTrue(isp.contains("residentLegacySharpenApplied = residentExecution.legacySharpenApplied"))
        assertTrue(isp.contains("VULKAN_RESIDENT_LEGACY_EDGE_AWARE_SHARPEN_APPLIED"))
        assertTrue(isp.contains("sharpenBackend="))
        assertTrue(isp.contains("CPU_FAILSAFE"))
    }

    @Test
    fun residentShaderPreservesLegacyEightBitSrgbSharpenDomainAndGaussianKernel() {
        val shader = source("src/main/cpp/vulkan/shaders/spectra_post_demosaic_resident.comp")
        assertTrue(shader.contains("legacyQuantizedSrgbChannel"))
        assertTrue(shader.contains("lutIndex = floor(v * 4096.0 + 0.5)"))
        assertTrue(shader.contains("const float legacyGaussian9[9]"))
        assertTrue(shader.contains("0.469343272"))
        assertTrue(shader.contains("smoothstepExact(0.004, 0.035"))
        assertTrue(shader.contains("smoothstepExact(0.72, 0.94"))
        assertTrue(shader.contains("smoothstepExact(0.025, 0.12"))
        assertTrue(shader.contains("smoothstepExact(0.045, 0.12"))
        assertTrue(shader.contains("runLegacySrgbSharpen"))
    }

    @Test
    fun postDemosaicBackendMarksSrgbResidentSharpenOutputExplicitly() {
        val header = source("src/main/cpp/vulkan/VulkanSpectraResidentPostDemosaicBackend.h")
        val cpp = source("src/main/cpp/vulkan/VulkanSpectraResidentPostDemosaicBackend.cpp")
        assertTrue(header.contains("float legacySharpenAmount"))
        assertTrue(header.contains("bool legacySharpenApplied"))
        assertTrue(header.contains("bool outputSrgbEncoded"))
        assertTrue(cpp.contains("request.legacySharpenAmount"))
        assertTrue(cpp.contains("SPECTRA_POST_DEMOSAIC_GPU_RESIDENT_LEGACY_SHARPEN_PUBLICATION_READY"))
    }
}
