package com.bncam.core.quality

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SpectraMilestone8HIRawFinalizeVulkanSourceContractTest {
    private fun appDir(): File = sequenceOf(File("."), File("app"))
        .firstOrNull { File(it, "src/main/cpp/IspCore.cpp").isFile }
        ?: error("Cannot locate app module")

    @Test
    fun `raw finalizer is a real vulkan correction and compact auto scene kernel`() {
        val app = appDir()
        val cmake = File(app, "src/main/cpp/CMakeLists.txt").readText()
        val shader = File(app, "src/main/cpp/vulkan/shaders/spectra_raw_finalize.comp").readText()
        val backend = File(app, "src/main/cpp/vulkan/VulkanSpectraRawFinalizeBackend.cpp").readText()

        assertTrue(cmake.contains("VulkanSpectraRawFinalizeBackend.cpp"))
        assertTrue(cmake.contains("spectra_raw_finalize.comp"))
        assertTrue(cmake.contains("getSpectraRawFinalizeSpirv"))
        assertTrue(shader.contains("float defectCorrectedAt"))
        assertTrue(shader.contains("void sampleGreen()"))
        assertTrue(shader.contains("void sampleAutoScene()"))
        assertTrue(shader.contains("float lensGainAt"))
        assertTrue(shader.contains("atomicAdd(telemetry"))
        assertTrue(shader.contains("binding = 1) buffer OutputBuffer"))
        assertFalse(shader.contains("binding = 1) writeonly buffer OutputBuffer"))
        assertTrue(backend.contains("vkCmdDispatch"))
        assertTrue(backend.contains("executeFromResident"))
        assertTrue(backend.contains("autoSceneMetricsReady"))
        assertTrue(backend.contains("residentOutputGeneration_"))
    }

    @Test
    fun `runtime keeps pass3 raw finalize and demosaic opaque resident generations`() {
        val app = appDir()
        val runtime = File(app, "src/main/cpp/vulkan/VulkanRuntime.cpp").readText()
        val header = File(app, "src/main/cpp/vulkan/VulkanRuntime.h").readText()

        assertTrue(header.contains("executeSpectraRawFinalizeFromPass3"))
        assertTrue(header.contains("executeSpectraResidentDemosaicFromRawFinalize"))
        val finalizeBlock = runtime.substringAfter("VulkanRuntime::executeSpectraRawFinalizeFromPass3")
            .substringBefore("VulkanRuntime::executeSpectraResidentDemosaicFromRawFinalize")
        assertTrue(finalizeBlock.contains("spectraResidentChromaBackend_.resolveResidentOutput"))
        assertTrue(finalizeBlock.contains("spectraRawFinalizeBackend_.executeFromResident"))
        val demosaicBlock = runtime.substringAfter("VulkanRuntime::executeSpectraResidentDemosaicFromRawFinalize")
            .substringBefore("VulkanRuntime::executeSpectraResidentDemosaic")
        assertTrue(demosaicBlock.contains("spectraRawFinalizeBackend_.resolveResidentOutput"))
        assertTrue(demosaicBlock.contains("spectraResidentDemosaicBackend_.executeFromResidentMosaic"))
    }

    @Test
    fun `isp prefers gpu jpeg raw finalization and keeps old correction chain fallback only`() {
        val app = appDir()
        val core = File(app, "src/main/cpp/IspCore.cpp").readText()
        val block = core.substringAfter("bool rawFinalizeResident = false")
            .substringBefore("const auto demosaicStart")

        assertTrue(block.contains("const auto runCpuRawFinalize"))
        assertTrue(block.contains("executeSpectraRawFinalize"))
        assertTrue(block.contains("rawFinalizeResident = true"))
        assertTrue(block.contains("runCpuRawFinalize();"))
        assertTrue(block.indexOf("executeSpectraRawFinalize") < block.lastIndexOf("runCpuRawFinalize();"))
        assertTrue(core.contains("executeSpectraResidentDemosaicFromRawFinalize"))
        assertTrue(core.contains("M8H_I_GPU_PRIMARY_TEMPORAL_PASS1_PASS2_PASS3_COMPACT_PLANNER_RAW_FINALIZE_DEMOSAIC_AWB_CCM_POST_DEMOSAIC"))
        assertTrue(core.contains("rawFinalizeAutoSceneMetricsReady="))
    }

    @Test
    fun `auto demosaic consumes compact finalized gpu scene metrics`() {
        val app = appDir()
        val header = File(app, "src/main/cpp/Demosaic.h").readText()
        val implementation = File(app, "src/main/cpp/Demosaic.cpp").readText()
        val core = File(app, "src/main/cpp/IspCore.cpp").readText()

        assertTrue(header.contains("struct AutoDemosaicSceneMetrics"))
        assertTrue(header.contains("resolveDemosaicForSceneMetrics"))
        assertTrue(implementation.contains("resolveDemosaicForSceneMetrics"))
        assertTrue(core.contains("vulkanRawFinalize.autoSceneMetricsReady"))
        assertTrue(core.contains("resolveDemosaicForSceneMetrics"))
    }
}
