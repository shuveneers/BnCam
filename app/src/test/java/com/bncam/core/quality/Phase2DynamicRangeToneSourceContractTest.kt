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
    fun `scene adaptive shoulder is applied identically by Vulkan and CPU failsafe`() {
        val core = source("src/main/cpp/IspCore.cpp")
        val header = source("src/main/cpp/vulkan/VulkanSpectraResidentToneBackend.h")
        val backend = source("src/main/cpp/vulkan/VulkanSpectraResidentToneBackend.cpp")
        val shader = source("src/main/cpp/vulkan/shaders/spectra_tone_resident.comp")

        // Automatic GTM is combined with the active profile Highlight control exactly once.
        assertTrue(core.contains("dynamicRangeTonePlan.shoulderStart + profileTonePlan.shoulderStartDelta"))
        assertTrue(core.contains("dynamicRangeTonePlan.shoulderStrength * profileTonePlan.shoulderStrengthScale"))
        assertTrue(core.contains("request.shoulderStart = effectiveShoulderStart"))
        assertTrue(core.contains("request.shoulderStrength = effectiveShoulderStrength"))
        assertTrue(core.contains("const float shoulderStart = effectiveShoulderStart"))
        assertTrue(core.contains("const float shoulderStrength = effectiveShoulderStrength"))
        assertTrue(header.contains("float shoulderStart"))
        assertTrue(header.contains("float shoulderStrength"))
        assertTrue(backend.contains("push.shoulderStart"))
        assertTrue(backend.contains("push.shoulderStrength"))
        assertTrue(shader.contains("pc.shoulderStart"))
        assertTrue(shader.contains("pc.shoulderStrength"))
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
