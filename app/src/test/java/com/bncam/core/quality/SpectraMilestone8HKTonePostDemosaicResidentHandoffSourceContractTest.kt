package com.bncam.core.quality

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SpectraMilestone8HKTonePostDemosaicResidentHandoffSourceContractTest {
    private fun appDir(): File = sequenceOf(File("."), File("app"))
        .firstOrNull { File(it, "src/main/cpp/IspCore.cpp").isFile }
        ?: error("Cannot locate app module")

    @Test
    fun `tone generation is consumed by post demosaic without rgb cpu reupload`() {
        val app = appDir()
        val core = File(app, "src/main/cpp/IspCore.cpp").readText()
        val runtime = File(app, "src/main/cpp/vulkan/VulkanRuntime.cpp").readText()
        val backend = File(app, "src/main/cpp/vulkan/VulkanSpectraResidentPostDemosaicBackend.cpp").readText()

        assertTrue(core.contains("request.deferFullReadback = true"))
        assertTrue(core.contains("executeResidentTonePostDemosaic"))
        assertTrue(core.contains("executeSpectraResidentPostDemosaicFromTone"))
        assertTrue(core.contains("spectraToneToPostDemosaicFullRgbRoundtripAvoided"))
        assertTrue(core.contains("M8H_K_GPU_RESIDENT_TONE_TO_SPATIAL_VISIBLE_CHROMA_HANDOFF"))

        assertTrue(runtime.contains("resolveResidentOutput"))
        assertTrue(runtime.contains("residentRequest.residentInputBuffer = toneBuffer"))
        assertTrue(runtime.contains("SPECTRA_FP32_TONE_TO_POST_DEMOSAIC_RESIDENT_HANDOFF"))

        assertTrue(backend.contains("residentInputUsed"))
        assertTrue(backend.contains("result.inputBytes = residentInputUsed ? 0u : requiredInputBytes"))
        assertTrue(backend.contains("residentInputBarrier"))
        assertTrue(backend.contains("push.padding1 = residentInputUsed ? 1.0f : 0.0f"))
        assertTrue(backend.contains("push.mode = 1u;\n    // The visible-chroma pass consumes the strip-local intermediate buffer"))
        assertTrue(backend.contains("push.padding1 = 0.0f"))
        assertFalse(backend.contains("std::memcpy(packed, request.rgbData,\n                    static_cast<std::size_t>(result.inputBytes))"))
    }

    @Test
    fun `typed fallback can materialize resident tone only after downstream failure`() {
        val app = appDir()
        val core = File(app, "src/main/cpp/IspCore.cpp").readText()
        val runtimeHeader = File(app, "src/main/cpp/vulkan/VulkanRuntime.h").readText()
        val toneHeader = File(app, "src/main/cpp/vulkan/VulkanSpectraResidentToneBackend.h").readText()

        assertTrue(runtimeHeader.contains("readbackSpectraResidentTone"))
        assertTrue(toneHeader.contains("readbackResidentOutput"))
        assertTrue(core.contains("M8H_K_TONE_READBACK_FAILED_CPU_REFERENCE_REBUILT_"))
        assertTrue(core.contains("applyCpuToneAndVibrance"))
    }
}
