package com.bncam.core.quality

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class Phase2DynamicRangeToneSourceContractTest {
    private val appDir: File = sequenceOf(File("."), File("app"))
        .firstOrNull { File(it, "src/main/cpp/IspCore.cpp").isFile }
        ?: error("Cannot locate app module from ${File(".").absolutePath}")

    private fun source(relative: String): String = File(appDir, relative).readText()

    @Test
    fun `capture tone separates sensor clipping from recoverable highlight pressure`() {
        val policy = source("src/main/cpp/DynamicRangeTonePolicy.h")
        val core = source("src/main/cpp/IspCore.cpp")

        assertTrue(policy.contains("sensorClipPressure"))
        assertTrue(policy.contains("recoverableHighlightPressure"))
        assertTrue(policy.contains("dynamicRangePressure"))
        assertTrue(policy.contains("sceneMidtoneTarget"))
        assertTrue(policy.contains("sensorSaturatedPct"))
        assertTrue(core.contains("fullRaw16SaturatedPct"))
        assertTrue(core.contains("toneSensorClipPressure="))
        assertTrue(core.contains("toneRecoverableHighlightPressure="))
        assertTrue(core.contains("toneSceneMidtoneTarget="))
        assertTrue(core.contains("const float targetP50 = dynamicRangeTonePlan.sceneMidtoneTarget"))
    }

    @Test
    fun `phase10 separates RAW scene placement from YUV legacy shoulder ownership`() {
        val core = source("src/main/cpp/IspCore.cpp")
        val backend = source("src/main/cpp/vulkan/VulkanSpectraResidentToneBackend.cpp")
        val shader = source("src/main/cpp/vulkan/shaders/spectra_tone_resident.comp")

        assertTrue(core.contains("const bool phase10RawToneArchitecture = isRawBayer"))
        assertTrue(core.contains("phase10RawToneArchitecture ? 0.0f : dynamicRangeTonePlan.contrastStrength"))
        assertTrue(core.contains("if (!phase10RawToneArchitecture)"))
        assertTrue(core.contains("request.localToneStrength = !phase10RawToneArchitecture"))
        assertTrue(core.contains("phase10ToneArchitecture="))
        assertTrue(core.contains("GTM_SCENE_PLACEMENT__FLLF__AGX"))
        assertTrue(backend.contains("push.presenceReserved0 = fllfRequested ? 1u : 0u"))
        assertTrue(shader.contains("if (pc.presenceReserved0 != 0u) rgb = applyFllfLocalExposure"))
        assertTrue(shader.contains("rgb = applyAgXTonemap(rgb);"))
        assertTrue(shader.indexOf("rgb = applyAgXTonemap(rgb);") >
            shader.indexOf("applyFllfLocalExposure(gid, rgb)"))
        assertFalse(shader.contains("const float shoulderStart = 0.68"))
    }

    @Test
    fun `dynamic range adaptation does not create a cpu pixel production stage`() {
        val policy = source("src/main/cpp/DynamicRangeTonePolicy.h")
        val core = source("src/main/cpp/IspCore.cpp")
        val residentRequest = core.substringAfter("bncam::vulkan::SpectraResidentToneRequest request")
            .substringBefore("executeSpectraResidentTone(request)")

        assertTrue(policy.contains("Scalar scene policy only"))
        assertTrue(residentRequest.contains("request.toneLut = vulkanToneLut.data()"))
        assertTrue(residentRequest.contains("request.deferFullReadback = true"))
        assertTrue(core.contains("explicit Vulkan tone failure"))
    }
}
