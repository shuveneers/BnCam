package com.bncam.core.quality

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalToneVulkanSourceContractTest {
    private val appDir: File = sequenceOf(File("."), File("app"))
        .firstOrNull { File(it, "src/main/cpp/IspCore.cpp").isFile }
        ?: error("Cannot locate app module from ${File(".").absolutePath}")

    private fun source(relative: String): String = File(appDir, relative).readText()

    @Test
    fun `local tone is planned from scene dynamic range and noise pressure`() {
        val policy = source("src/main/cpp/LocalTonePolicy.h")
        val core = source("src/main/cpp/IspCore.cpp")

        assertTrue(policy.contains("dynamicRangePressure"))
        assertTrue(policy.contains("recoverableHighlightPressure"))
        assertTrue(policy.contains("noisePressure"))
        assertTrue(policy.contains("strength *= 1.0f - 0.45f * noise"))
        assertTrue(core.contains("resolveLocalTonePlan"))
        assertTrue(core.contains("request.localToneStrength = localTonePlan.enabled"))
        assertTrue(core.contains("localToneBackend="))
        assertTrue(policy.contains("std::clamp(input.sceneMidtoneTarget, 0.145f, 0.185f)"))
        assertTrue(shaderOrCoreSceneKeyContract())
    }

    private fun shaderOrCoreSceneKeyContract(): Boolean =
        source("src/main/cpp/vulkan/shaders/spectra_tone_resident.comp")
            .contains("telemetryFloat(4u, 0.155)")

    @Test
    fun `local tone spatial work stays Vulkan resident and edge aware`() {
        val shader = source("src/main/cpp/vulkan/shaders/spectra_tone_resident.comp")
        val backend = source("src/main/cpp/vulkan/VulkanSpectraResidentToneBackend.cpp")
        val header = source("src/main/cpp/vulkan/VulkanSpectraResidentToneBackend.h")

        assertTrue(shader.contains("binding = 10"))
        assertTrue(shader.contains("buildLocalToneBase"))
        assertTrue(shader.contains("rangeWeight = exp(-1.55 * abs(neighborLog - centerLog))"))
        assertTrue(shader.contains("rgb = applyLocalTone(gid, rgb)"))
        assertTrue(shader.contains("positiveHighlightGuard"))
        assertTrue(shader.contains("deepBlackGuard"))
        assertTrue(backend.contains("push.mode = 9u"))
        assertTrue(backend.contains("localToneBase_.buffer"))
        assertTrue(header.contains("PersistentBuffer localToneBase_"))
        assertFalse(coreOrBackendContainsCpuLtm(backend))
    }

    private fun coreOrBackendContainsCpuLtm(backend: String): Boolean =
        backend.contains("localToneCpu") || backend.contains("CPU_LOCAL_TONE")
}
