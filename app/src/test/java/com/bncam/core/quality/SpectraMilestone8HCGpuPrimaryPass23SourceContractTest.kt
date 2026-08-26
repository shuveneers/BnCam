package com.bncam.core.quality

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SpectraMilestone8HCGpuPrimaryPass23SourceContractTest {
    private fun appDir(): File = sequenceOf(File("."), File("app"))
        .firstOrNull { File(it, "src/main/cpp/IspCore.cpp").isFile }
        ?: error("Cannot locate app module")

    @Test
    fun `pass2 and pass3 use vulkan before typed cpu fallback`() {
        val app = appDir()
        val core = File(app, "src/main/cpp/IspCore.cpp").readText()
        val pass2Block = core.substringAfter("const auto pass2Start")
            .substringBefore("const auto pass3Start")
        val pass3Block = core.substringAfter("const auto pass3Start")
            .substringBefore("const auto finalProvenanceStart")

        assertTrue(pass2Block.contains("tryApplySpectraPass2Vulkan"))
        assertTrue(pass2Block.contains("if (pass2GpuApplied)"))
        assertTrue(pass2Block.contains("} else {"))
        assertTrue(pass2Block.contains("applySpectraPass2(workingRaw"))
        assertFalse(pass2Block.substringBefore("if (pass2GpuApplied)").contains("workingRaw.mosaic.clone()"))

        assertTrue(pass3Block.contains("tryApplySpectraPass3Vulkan"))
        assertTrue(pass3Block.contains("if (!pass3GpuApplied)"))
        assertTrue(pass3Block.contains("applySpectraPass3(workingRaw"))
        assertFalse(pass3Block.substringBefore("if (!pass3GpuApplied)").contains("workingRaw.mosaic.clone()"))

        assertTrue(core.contains("spectraPass2VulkanUsedForOutput="))
        assertTrue(core.contains("spectraPass3VulkanUsedForOutput="))
        assertTrue(core.contains("spectraPass2GpuPrimary="))
        assertTrue(core.contains("spectraPass3GpuPrimary="))
    }

    @Test
    fun `resident chroma shader owns pass2 pass3 candidate stats and blend`() {
        val app = appDir()
        val shader = File(app, "src/main/cpp/vulkan/shaders/spectra_chroma_resident.comp").readText()
        val backend = File(app, "src/main/cpp/vulkan/VulkanSpectraResidentChromaBackend.cpp").readText()
        val header = File(app, "src/main/cpp/vulkan/VulkanSpectraResidentChromaBackend.h").readText()

        assertTrue(shader.contains("void pass2Fine()"))
        assertTrue(shader.contains("void pass2Mid()"))
        assertTrue(shader.contains("void pass3Candidate()"))
        assertTrue(shader.contains("void collectTileStats()"))
        assertTrue(shader.contains("void noRegretBlendPass2()"))
        assertTrue(shader.contains("void noRegretBlendPass3()"))
        assertTrue(backend.contains("executePass2"))
        assertTrue(backend.contains("executePass3"))
        assertTrue(backend.contains("gpuNoRegretBlendUsed = true"))
        assertTrue(backend.contains("cpuFullFrameCandidateReadbackAvoided = true"))
        assertTrue(header.contains("PersistentBuffer auxiliary_"))
        assertTrue(header.contains("PersistentBuffer tileStatistics_"))
        assertTrue(header.contains("PersistentBuffer acceptance_"))
    }

    @Test
    fun `runtime cmake trace and schema expose milestone 8hc`() {
        val app = appDir()
        val runtime = File(app, "src/main/cpp/vulkan/VulkanRuntime.cpp").readText()
        val cmake = File(app, "src/main/cpp/CMakeLists.txt").readText()
        val trace = File(app, "src/main/java/com/bncam/core/quality/SpectraVulkanResidentChromaTrace.kt").readText()
        val noiseTrace = File(app, "src/main/java/com/bncam/core/quality/NoiseModelTrace.kt").readText()
        val core = File(app, "src/main/cpp/IspCore.cpp").readText()

        assertTrue(runtime.contains("executeSpectraResidentPass2"))
        assertTrue(runtime.contains("executeSpectraResidentPass3"))
        assertTrue(runtime.contains("SPECTRA_FP32_PRE_DEMOSAIC_PASS2_PASS3_RESIDENT"))
        assertTrue(cmake.contains("spectra_chroma_resident.comp"))
        assertTrue(cmake.contains("getSpectraResidentChromaSpirv"))
        assertTrue(cmake.contains("VulkanSpectraResidentChromaBackend.cpp"))
        assertTrue(trace.contains("MILESTONE_8H_C_GPU_PRIMARY_PRE_DEMOSAIC_PASS2_PASS3"))
        assertTrue(noiseTrace.contains("CURRENT_SCHEMA_VERSION = 22"))
        assertTrue(noiseTrace.contains("\"vulkanResidentChroma\""))
        assertTrue(core.contains("spectraProductionBackend=VULKAN_GPU_PRIMARY_HYBRID_TRANSITION"))
    }

    @Test
    fun `generated spirv code sizes are byte counts`() {
        val app = appDir()
        val pass1 = File(app, "src/main/cpp/vulkan/VulkanSpectraResidentPreDemosaicBackend.cpp").readText()
        val pass23 = File(app, "src/main/cpp/vulkan/VulkanSpectraResidentChromaBackend.cpp").readText()
        assertTrue(pass1.contains("spirv.size() * sizeof(std::uint32_t)"))
        assertTrue(pass23.contains("spirv.size() * sizeof(std::uint32_t)"))
        assertFalse(pass1.contains("shaderInfo.codeSize = spirv.size();"))
        assertFalse(pass23.contains("shaderInfo.codeSize = spirv.size();"))
    }
}
