package com.bncam.core.quality

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SpectraMilestone8HBPreDemosaicVulkanSourceContractTest {
    private fun appDir(): File = sequenceOf(File("."), File("app"))
        .firstOrNull { File(it, "src/main/cpp/IspCore.cpp").isFile }
        ?: error("Cannot locate app module")

    @Test
    fun `pass1 is gpu primary and cpu reference executes only on typed failure`() {
        val app = appDir()
        val core = File(app, "src/main/cpp/IspCore.cpp").readText()
        val passBlock = core.substringAfter("const auto pass1Start")
            .substringBefore("const bncam::spectra2::FloatPlaneView postPass1PerformanceView")

        assertTrue(passBlock.contains("tryApplySpectraPass1Vulkan"))
        assertTrue(passBlock.contains("if (!pass1GpuApplied)"))
        assertTrue(passBlock.contains("applySpectraPass1(workingRaw"))
        assertTrue(passBlock.contains("applySpectraNoRegretGate"))
        assertTrue(passBlock.indexOf("tryApplySpectraPass1Vulkan") < passBlock.indexOf("applySpectraPass1(workingRaw"))
        assertFalse(passBlock.substringBefore("if (!pass1GpuApplied)").contains("workingRaw.mosaic.clone()"))
        assertTrue(core.contains("vulkanCandidateReadbackAvoided"))
        assertTrue(core.contains("vulkanUsedForOutput = true"))
    }

    @Test
    fun `resident pass1 shader keeps candidate and no regret blend on device`() {
        val app = appDir()
        val shader = File(app, "src/main/cpp/vulkan/shaders/spectra_pass1_resident.comp").readText()
        val backend = File(app, "src/main/cpp/vulkan/VulkanSpectraResidentPreDemosaicBackend.cpp").readText()
        val header = File(app, "src/main/cpp/vulkan/VulkanSpectraResidentPreDemosaicBackend.h").readText()

        assertTrue(shader.contains("void pass1Pixel()"))
        assertTrue(shader.contains("void collectAfterTileStats()"))
        assertTrue(shader.contains("void noRegretBlend()"))
        assertTrue(shader.contains("candidateMosaic"))
        assertTrue(shader.contains("candidateMosaic[index] = before + weight * (proposed - before)"))
        assertTrue(backend.contains("result.candidateBytes, 0u, candidate_"))
        assertTrue(backend.contains("cpuFullFrameCandidateReadbackAvoided = true"))
        assertTrue(backend.contains("gpuNoRegretBlendUsed = true"))
        assertTrue(backend.contains("SPECTRA_PASS1_GPU_RESIDENT_NO_REGRET_READY"))
        assertTrue(backend.contains("vkCmdDispatch"))
        assertTrue(header.contains("PersistentBuffer candidate_"))
        assertFalse(header.contains("PersistentBuffer output_"))
        assertTrue(backend.contains("vkCmdCopyBuffer(commandBuffer_, candidate_.buffer, input_.buffer"))
    }

    @Test
    fun `runtime cmake and schema expose real pre demosaic production stage`() {
        val app = appDir()
        val runtime = File(app, "src/main/cpp/vulkan/VulkanRuntime.cpp").readText()
        val cmake = File(app, "src/main/cpp/CMakeLists.txt").readText()
        val trace = File(app, "src/main/java/com/bncam/core/quality/SpectraVulkanPreDemosaicTrace.kt").readText()
        val noiseTrace = File(app, "src/main/java/com/bncam/core/quality/NoiseModelTrace.kt").readText()

        assertTrue(runtime.contains("executeSpectraResidentPreDemosaicPass1"))
        assertTrue(runtime.contains("SPECTRA_FP32_PRE_DEMOSAIC_PASS1_RESIDENT"))
        assertTrue(cmake.contains("spectra_pass1_resident.comp"))
        assertTrue(cmake.contains("getSpectraResidentPreDemosaicSpirv"))
        assertTrue(cmake.contains("VulkanSpectraResidentPreDemosaicBackend.cpp"))
        assertTrue(trace.contains("GPU_PASS1_PRIMARY_CPU_TYPED_FALLBACK_ONLY"))
        assertTrue(noiseTrace.contains("CURRENT_SCHEMA_VERSION = 22"))
        assertTrue(noiseTrace.contains("\"vulkanResidentPreDemosaic\""))
        assertTrue(noiseTrace.contains("\"productionBackend\""))
        assertTrue(noiseTrace.contains("\"gpuPrimaryStages\""))
        assertTrue(File(app, "src/main/cpp/IspCore.cpp").readText().contains(
            "spectraProductionBackend=VULKAN_GPU_PRIMARY_HYBRID_TRANSITION"
        ))
    }
}
