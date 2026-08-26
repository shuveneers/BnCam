package com.bncam.core.quality

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SpectraMilestone8HEFDemosaicAwbCcmVulkanSourceContractTest {
    private fun appDir(): File = sequenceOf(File("."), File("app"))
        .firstOrNull { File(it, "src/main/cpp/IspCore.cpp").isFile }
        ?: error("Cannot locate app module")

    @Test
    fun `demosaic shader owns bilinear malvar awb ccm and compact residual extraction`() {
        val app = appDir()
        val shader = File(app, "src/main/cpp/vulkan/shaders/spectra_demosaic_resident.comp").readText()
        val cmake = File(app, "src/main/cpp/CMakeLists.txt").readText()

        assertTrue(cmake.contains("VulkanSpectraResidentDemosaicBackend.cpp"))
        assertTrue(cmake.contains("spectra_demosaic_resident.comp"))
        assertTrue(cmake.contains("BNCAM_SPECTRA_DEMOSAIC_SHADER_AVAILABLE"))
        assertTrue(shader.contains("vec3 bilinearAt"))
        assertTrue(shader.contains("vec3 malvarAt"))
        assertTrue(shader.contains("pc.mode == 3u"))
        assertTrue(shader.contains("writeResidualCandidate"))
        assertTrue(shader.contains("layout(std430, binding = 4)"))
        assertTrue(shader.contains("ccm0 * wb.r"))
    }

    @Test
    fun `successful gpu demosaic stays resident through awb ccm without full rgb roundtrip`() {
        val app = appDir()
        val core = File(app, "src/main/cpp/IspCore.cpp").readText()
        val backend = File(app, "src/main/cpp/vulkan/VulkanSpectraResidentDemosaicBackend.cpp").readText()

        assertTrue(core.contains("vulkanDemosaicResident = true"))
        assertTrue(core.contains("measureLinearResidualGpuCandidates"))
        assertTrue(core.contains("request.rgbData = linearRgb.empty() ? nullptr"))
        assertTrue(core.contains("executeSpectraResidentAwbCcm"))
        assertTrue(core.contains("spectraDemosaicToAwbCcmFullRgbRoundtripAvoided="))
        assertTrue(backend.contains("full RGB device-resident"))
        assertTrue(backend.contains("result.fullReadbackDeferred = true"))
        assertTrue(backend.contains("residentInputUsable"))
        assertTrue(backend.contains("binding 2 may safely alias binding 1"))
        assertFalse(backend.contains("deviceColorOutput_"))
        assertFalse(backend.contains("colorReadback_"))
    }

    @Test
    fun `menon remains typed cpu fallback rather than throwaway gpu port`() {
        val app = appDir()
        val backend = File(app, "src/main/cpp/vulkan/VulkanSpectraResidentDemosaicBackend.cpp").readText()
        val core = File(app, "src/main/cpp/IspCore.cpp").readText()

        assertTrue(backend.contains("GPU_DEMOSAIC_MENON_TYPED_FALLBACK_PENDING_RCD_AMAZE_MIGRATION"))
        assertTrue(core.contains("replacement by the MALVAR/RCD/AMAZE set"))
        assertTrue(core.contains("runCpuDemosaicFallback"))
    }

    @Test
    fun `runtime and debug expose ef production authority and residency`() {
        val app = appDir()
        val runtime = File(app, "src/main/cpp/vulkan/VulkanRuntime.cpp").readText()
        val core = File(app, "src/main/cpp/IspCore.cpp").readText()

        assertTrue(runtime.contains("SPECTRA_FP32_DEMOSAIC_TO_AWB_CCM_RESIDENT"))
        assertTrue(runtime.contains("SPECTRA_FP32_AWB_CCM_GPU_PRIMARY"))
        assertTrue(runtime.contains("SPECTRA_FP32_LINEAR_RESIDUAL_COMPACT_GPU_OBSERVER"))
        assertTrue(core.contains("RAW_FINALIZE_DEMOSAIC_AWB_CCM_POST_DEMOSAIC"))
        assertTrue(core.contains("spectraDemosaicGpuPrimary="))
        assertTrue(core.contains("spectraAwbCcmGpuPrimary="))
        assertTrue(core.contains("vulkanDemosaicResidualKernelMs="))
        assertTrue(core.contains("vulkanAwbCcmResidualKernelMs="))
    }
}
