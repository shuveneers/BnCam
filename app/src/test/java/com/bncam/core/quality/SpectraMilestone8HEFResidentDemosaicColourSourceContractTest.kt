package com.bncam.core.quality

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class SpectraMilestone8HEFResidentDemosaicColourSourceContractTest {
    private fun source(path: String): String {
        val roots = listOf(File("."), File("app"), File(".."))
        val file = roots.asSequence().map { File(it, path) }.firstOrNull { it.exists() }
            ?: error("Source not found: $path")
        return file.readText()
    }

    @Test
    fun demosaicAndColourHaveRealVulkanKernelAndRuntimeOwner() {
        val cmake = source("app/src/main/cpp/CMakeLists.txt")
        val shader = source("app/src/main/cpp/vulkan/shaders/spectra_demosaic_resident.comp")
        val backend = source("app/src/main/cpp/vulkan/VulkanSpectraResidentDemosaicBackend.cpp")
        val runtime = source("app/src/main/cpp/vulkan/VulkanRuntime.cpp")
        assertTrue(cmake.contains("spectra_demosaic_resident.comp"))
        assertTrue(cmake.contains("getSpectraResidentDemosaicSpirv"))
        assertTrue(shader.contains("bilinearAt"))
        assertTrue(shader.contains("malvarAt"))
        assertTrue(shader.contains("writeResidualCandidate"))
        assertTrue(shader.contains("pc.mode == 3u"))
        assertTrue(backend.contains("spirv.size() * sizeof(std::uint32_t)"))
        assertTrue(runtime.contains("executeSpectraResidentDemosaic"))
        assertTrue(runtime.contains("executeSpectraResidentAwbCcm"))
    }

    @Test
    fun successfulDemosaicStaysResidentThroughAwbCcm() {
        val core = source("app/src/main/cpp/IspCore.cpp")
        val backend = source("app/src/main/cpp/vulkan/VulkanSpectraResidentDemosaicBackend.cpp")
        assertTrue(core.contains("vulkanDemosaicResident = true"))
        assertTrue(core.contains("measureLinearResidualGpuCandidates"))
        assertTrue(core.contains("linearRgb.empty() ? nullptr : linearRgb.ptr<float>(0)"))
        assertTrue(core.contains("vulkanColorTransform.residentInputUsed"))
        assertTrue(core.contains("spectraDemosaicToAwbCcmFullRgbRoundtripAvoided"))
        assertTrue(backend.contains("result.fullReadbackDeferred = true"))
        assertTrue(backend.contains("push.mode = 3u"))
        assertFalse(backend.substringAfter("result.fullReadbackDeferred = true")
            .substringBefore("return result;").contains("result.outputRgb.resize"))
    }

    @Test
    fun cpuDemosaicAndColourAreTypedFallbackOnly() {
        val core = source("app/src/main/cpp/IspCore.cpp")
        assertTrue(core.contains("if (!vulkanDemosaicResident && linearRgb.empty())"))
        assertTrue(core.contains("linearRgb = runCpuDemosaicFallback()"))
        assertTrue(core.contains("if (linearRgb.empty()) {\n            linearRgb = runCpuDemosaicFallback();"))
        assertTrue(core.contains("RAW_FINALIZE_DEMOSAIC_AWB_CCM_POST_DEMOSAIC"))
    }

    @Test
    fun compactResidualCandidatesReplaceIntermediateFullRgbCpuScan() {
        val shader = source("app/src/main/cpp/vulkan/shaders/spectra_demosaic_resident.comp")
        val header = source("app/src/main/cpp/vulkan/VulkanSpectraResidentDemosaicBackend.h")
        val core = source("app/src/main/cpp/IspCore.cpp")
        assertTrue(shader.contains("ResidualCandidates"))
        assertTrue(header.contains("std::vector<float> residualCandidates"))
        assertTrue(header.contains("bool fullReadbackDeferred = false"))
        assertTrue(core.contains("FLAT_REGION_CROSS_5_LINEAR_RGB_GPU_COMPACT_TILE_MEDIAN"))
        assertTrue(core.contains("vulkanDemosaic.fullReadbackDeferred"))
    }
}
