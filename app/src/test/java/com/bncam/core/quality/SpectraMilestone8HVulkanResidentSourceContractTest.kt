package com.bncam.core.quality

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SpectraMilestone8HVulkanResidentSourceContractTest {
    private fun appDir(): File = sequenceOf(File("."), File("app"))
        .firstOrNull { File(it, "src/main/cpp/IspCore.cpp").isFile }
        ?: error("Cannot locate app module")

    @Test
    fun `m8h owns spatial nr visible chroma no regret and reconstruction on vulkan`() {
        val app = appDir()
        val core = File(app, "src/main/cpp/IspCore.cpp").readText()
        val backend = File(
            app,
            "src/main/cpp/vulkan/VulkanSpectraResidentPostDemosaicBackend.cpp"
        ).readText()
        val shader = File(
            app,
            "src/main/cpp/vulkan/shaders/spectra_post_demosaic_resident.comp"
        ).readText()
        val runtime = File(app, "src/main/cpp/vulkan/VulkanRuntime.cpp").readText()

        assertTrue(backend.contains("vkCmdDispatch"))
        assertTrue(backend.contains("SPECTRA_POST_DEMOSAIC_GPU_RESIDENT_CHAIN_READY"))
        assertTrue(backend.contains("VkBufferMemoryBarrier intermediateBarrier"))
        assertTrue(shader.contains("void runSpatialNr"))
        assertTrue(shader.contains("void runVisibleChroma"))
        assertTrue(shader.contains("pc.visibleChromaEnabled == 0u"))
        assertTrue(shader.contains("mahalanobis2"))
        assertTrue(shader.contains("float acceptance ="))
        assertTrue(shader.contains("storeOutput(localOutputY, x, finalRgb)"))
        assertTrue(runtime.contains("SPECTRA_FP32_POST_DEMOSAIC_CHAIN_RESIDENT"))
        assertTrue(core.contains("M8H_DEVICE_LOCAL_INTERMEDIATE_NO_CPU_SHADOW_EXECUTION"))
    }

    @Test
    fun `production success skips cpu loops and failure remains typed fallback`() {
        val app = appDir()
        val core = File(app, "src/main/cpp/IspCore.cpp").readText()
        val telemetry = File(app, "src/main/cpp/SpectraVisibleChroma.h").readText()
        val trace = File(
            app,
            "src/main/java/com/bncam/core/quality/SpectraVulkanResidentTrace.kt"
        ).readText()

        assertTrue(core.contains("vulkanResidentUsedForOutput = residentExecution.success"))
        assertTrue(core.contains("!residentPostDemosaicApplied"))
        assertTrue(core.contains("linearRgb = std::move(residentGpuOutput)"))
        assertFalse(core.substringAfter("shouldRunResidentPostDemosaic")
            .substringBefore("if (!residentPostDemosaicApplied)")
            .contains("linearRgb.clone()"))
        assertTrue(core.contains("M8H_TYPED_RESIDENT_CHAIN_FAILURE_CPU_REFERENCE_FALLBACK"))
        assertTrue(telemetry.contains("GPU_FINAL_DECISION_PRIMARY_CPU_FALLBACK_ONLY"))
        assertTrue(trace.contains("GPU_SPATIAL_NR_VISIBLE_CHROMA_NO_REGRET_AND_RGB_RECONSTRUCTION_CPU_NEIGHBOUR_LOOPS_SKIPPED"))
        assertFalse(trace.contains("CPU_AND_GPU_ALWAYS_RUN"))
    }

    @Test
    fun `resident resources generated shader and schema telemetry are wired`() {
        val app = appDir()
        val cmake = File(app, "src/main/cpp/CMakeLists.txt").readText()
        val backend = File(
            app,
            "src/main/cpp/vulkan/VulkanSpectraResidentPostDemosaicBackend.cpp"
        ).readText()
        val noiseTrace = File(
            app,
            "src/main/java/com/bncam/core/quality/NoiseModelTrace.kt"
        ).readText()

        assertTrue(cmake.contains("spectra_post_demosaic_resident.comp"))
        assertTrue(cmake.contains("EmbedSpirv.cmake"))
        assertTrue(cmake.contains("VulkanSpectraResidentPostDemosaicBackend.cpp"))
        assertTrue(backend.contains("persistentBufferReuseHit"))
        assertTrue(backend.contains("descriptorBindingsInitialized_"))
        assertTrue(backend.contains("spatialGenerationId_"))
        assertTrue(noiseTrace.contains("CURRENT_SCHEMA_VERSION = 22"))
        assertTrue(noiseTrace.contains("\"vulkanResidentVisibleChroma\""))
    }
}
