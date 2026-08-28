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
    fun `raw local adaptation is planned by phase10 FLLF from scene evidence`() {
        val policy = source("src/main/cpp/FastLocalLaplacianPolicy.h")
        val core = source("src/main/cpp/IspCore.cpp")

        assertTrue(policy.contains("dynamicRangePressure"))
        assertTrue(policy.contains("recoverableHighlightPressure"))
        assertTrue(policy.contains("sensorClipPressure"))
        assertTrue(policy.contains("noisePressure"))
        assertTrue(policy.contains("out.maxLiftEv"))
        assertTrue(policy.contains("out.maxCompressEv"))
        assertTrue(policy.contains("out.edgeStopEv"))
        assertTrue(core.contains("resolveFastLocalLaplacianPlan"))
        assertTrue(core.contains("request.fllfEnabled = fllfPlan.enabled"))
        assertTrue(core.contains("request.localToneStrength = !phase10RawToneArchitecture"))
        assertTrue(core.contains("phase10ToneArchitecture="))
    }

    @Test
    fun `raw FLLF is resident pyramid based while legacy LTM is YUV only`() {
        val shader = source("src/main/cpp/vulkan/shaders/spectra_tone_resident.comp")
        val backend = source("src/main/cpp/vulkan/VulkanSpectraResidentToneBackend.cpp")
        val header = source("src/main/cpp/vulkan/VulkanSpectraResidentToneBackend.h")

        assertTrue(shader.contains("binding = 11"))
        assertTrue(shader.contains("binding = 12"))
        assertTrue(shader.contains("buildFllfGaussianLevel0"))
        assertTrue(shader.contains("buildFllfGaussianNextLevel"))
        assertTrue(shader.contains("seedFllfCorrection"))
        assertTrue(shader.contains("reconstructFllfCorrection"))
        assertTrue(shader.contains("if (pc.presenceReserved0 != 0u) rgb = applyFllfLocalExposure"))
        assertTrue(shader.contains("else {\n        rgb = applyLocalTone(gid, rgb);"))
        assertTrue(backend.contains("push.mode = 10u"))
        assertTrue(backend.contains("push.mode = 11u"))
        assertTrue(backend.contains("push.mode = 12u"))
        assertTrue(backend.contains("push.mode = 13u"))
        assertTrue(header.contains("PersistentBuffer fllfGaussian_"))
        assertTrue(header.contains("PersistentBuffer fllfCorrection_"))
        assertFalse(backend.contains("CPU_FLLF"))
        assertFalse(backend.contains("fllfCpu"))
    }
}
