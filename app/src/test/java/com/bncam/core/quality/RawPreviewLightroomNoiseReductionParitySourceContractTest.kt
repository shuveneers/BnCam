package com.bncam.core.quality

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RawPreviewLightroomNoiseReductionParitySourceContractTest {
    private val appDir: File = sequenceOf(File("."), File("app"))
        .firstOrNull { File(it, "src/main/cpp/vulkan/VulkanRawPreviewBackend.cpp").isFile }
        ?: error("Cannot locate app module from ${File(".").absolutePath}")

    private fun source(relative: String): String = File(appDir, relative).readText()

    @Test
    fun `retired detail noise reduction settings retain ABI but own no preview pixels`() {
        val settings = source("src/main/java/com/bncam/data/settings/ProfileLensTuningSettings.kt")
        val full = source("src/main/cpp/vulkan/shaders/raw_preview.comp")
        val fast = source("src/main/cpp/vulkan/shaders/raw_preview_image.comp")

        assertTrue(settings.contains("Legacy Detail > Noise Reduction persistence/ABI"))
        assertTrue(settings.contains("These keys no longer own pixels"))

        // The fixed ABI words may remain in the full shader/backend so old profiles and JNI layouts
        // stay readable, but there must be no retired profile-NR function or invocation.
        assertTrue(full.contains("PROFILE_NR_LUMINANCE_INDEX = 566u"))
        assertTrue(full.contains("PROFILE_NR_COLOR_SMOOTHNESS_INDEX = 571u"))
        assertFalse(full.contains("vec3 applyPreviewProfileNoiseReduction"))
        assertFalse(full.contains("sensorRgb = applyPreviewProfileNoiseReduction"))
        assertFalse(fast.contains("applyPreviewProfileNoiseReduction"))
    }

    @Test
    fun `preview denoise authority is physical noise driven not ISO driven`() {
        val full = source("src/main/cpp/vulkan/shaders/raw_preview.comp")
        val fast = source("src/main/cpp/vulkan/shaders/raw_preview_image.comp")

        assertTrue(full.contains("return previewPhysicalNoiseSigma(max(localSignal, 0.0));"))
        assertTrue(full.contains("float noisePressure = previewPhysicalNoisePressure"))
        assertTrue(full.contains("float chromaStrength = clamp(0.90 * noisePressure * flatness"))
        assertTrue(full.contains("float lumaDenoiseStrength = clamp(0.45 * noisePressure * flatness"))
        assertFalse(full.contains("float chromaStrength = clamp(0.35 + 0.55"))
        assertFalse(full.contains("float isoNoise = smoothstep(100.0, 1600.0"))

        assertTrue(fast.contains("float noisePressure = previewPhysicalNoisePressure"))
        assertFalse(fast.contains("smoothstep(100.0, 1600.0"))
    }

    @Test
    fun `CFA decimation can resolve to identity on clean physical evidence`() {
        val full = source("src/main/cpp/vulkan/shaders/raw_preview.comp")
        val fast = source("src/main/cpp/vulkan/shaders/raw_preview_image.comp")

        listOf(full, fast).forEach { shader ->
            assertTrue(shader.contains("float aliasRisk = smoothstep(0.055, 0.18"))
            assertTrue(shader.contains("0.76 * noisePressure"))
            assertTrue(shader.contains("mix("))
        }
        assertTrue(full.contains("return mix(centreSample, filtered, filterAuthority);"))
        assertTrue(fast.contains("return mix(c, filtered, filterAuthority);"))
    }
}
