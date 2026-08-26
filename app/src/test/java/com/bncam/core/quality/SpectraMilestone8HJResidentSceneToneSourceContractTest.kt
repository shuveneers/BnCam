package com.bncam.core.quality

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SpectraMilestone8HJResidentSceneToneSourceContractTest {
    private fun appDir(): File = sequenceOf(File("."), File("app"))
        .firstOrNull { File(it, "src/main/cpp/IspCore.cpp").isFile }
        ?: error("Cannot locate app module")

    @Test
    fun `post ccm scene observer and tone are real vulkan compute stages`() {
        val app = appDir()
        val cmake = File(app, "src/main/cpp/CMakeLists.txt").readText()
        val shader = File(app, "src/main/cpp/vulkan/shaders/spectra_tone_resident.comp").readText()
        val backend = File(app, "src/main/cpp/vulkan/VulkanSpectraResidentToneBackend.cpp").readText()

        assertTrue(cmake.contains("VulkanSpectraResidentToneBackend.cpp"))
        assertTrue(cmake.contains("spectra_tone_resident.comp"))
        assertTrue(cmake.contains("getSpectraResidentToneSpirv"))
        assertTrue(shader.contains("void runHighlightRecovery"))
        assertTrue(shader.contains("void writeSceneSample"))
        assertTrue(shader.contains("void writeDisplayCell"))
        assertTrue(shader.contains("void runTone"))
        assertTrue(shader.contains("float profileContrastCurve"))
        assertTrue(shader.contains("float profileVibrance"))
        assertFalse(shader.contains("hueEnabled"))
        assertFalse(shader.contains("hueCos"))
        assertFalse(shader.contains("hueSin"))
        assertTrue(backend.contains("vkCmdDispatch"))
        assertTrue(backend.contains("residentSceneGeneration_"))
        assertTrue(backend.contains("residentToneGeneration_"))
        assertTrue(backend.contains("compact_"))
        assertTrue(backend.contains("deferFullReadback"))
    }

    @Test
    fun `awb ccm output can remain opaque resident until scene and tone processing`() {
        val app = appDir()
        val header = File(app, "src/main/cpp/vulkan/VulkanSpectraResidentDemosaicBackend.h").readText()
        val implementation = File(app, "src/main/cpp/vulkan/VulkanSpectraResidentDemosaicBackend.cpp").readText()
        val runtime = File(app, "src/main/cpp/vulkan/VulkanRuntime.cpp").readText()

        assertTrue(header.contains("bool deferFullReadback = false"))
        assertTrue(header.contains("residentColorGeneration"))
        assertTrue(header.contains("resolveResidentColorOutput"))
        assertTrue(implementation.contains("fullReadbackDeferred"))
        assertTrue(implementation.contains("residentColorGeneration_"))
        assertTrue(runtime.contains("executeSpectraResidentSceneObserverFromAwbCcm"))
        assertTrue(runtime.contains("resolveResidentColorOutput"))
        assertTrue(runtime.contains("executeSpectraResidentTone"))
        assertTrue(runtime.contains("SPECTRA_FP32_POST_CCM_SCENE_TONE_RESIDENT"))
    }

    @Test
    fun `isp uses gpu scene tone path without cpu shadow processing`() {
        val app = appDir()
        val core = File(app, "src/main/cpp/IspCore.cpp").readText()

        assertTrue(core.contains("request.deferFullReadback = true"))
        assertTrue(core.contains("executeSpectraResidentSceneObserverFromAwbCcm"))
        assertTrue(core.contains("executeSpectraResidentTone"))
        assertTrue(core.contains("if (!vulkanToneApplied)"))
        assertTrue(core.contains("M8H_J_GPU_RESIDENT_POST_CCM_SCENE_HIGHLIGHT_TONE_VIBRANCE_PROFILE_COLOR"))
        assertTrue(core.contains("spectraAwbCcmToToneFullRgbRoundtripAvoided"))
        assertTrue(core.contains("cpuSceneProcessingApplied"))

        val toneFallback = core.substringAfter("if (!vulkanToneApplied) {")
            .substringBefore("jpegRaw.mosaic.release()")
        assertTrue(toneFallback.contains("rawJpegBaseVibrance"))
        assertFalse(toneFallback.isBlank())
    }
}
