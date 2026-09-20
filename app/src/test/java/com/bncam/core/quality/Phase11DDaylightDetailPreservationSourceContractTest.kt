package com.bncam.core.quality

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class Phase11DDaylightDetailPreservationSourceContractTest {
    private val appDir: File = sequenceOf(File("."), File("app"))
        .firstOrNull { File(it, "src/main/cpp/vulkan/VulkanSpectraRawFinalizeBackend.cpp").isFile }
        ?: error("Cannot locate app module from ${File(".").absolutePath}")

    private fun source(relative: String): String = File(appDir, relative).readText()

    @Test
    fun `capture baseline Wiener has explicit physical significance identity gate`() {
        val shader = source("src/main/cpp/vulkan/shaders/spectra_raw_finalize.comp")

        assertTrue(shader.contains("float physicalPressure = smoothstep(0.012, 0.055, relativeSigma);"))
        assertTrue(shader.contains("float dominance = smoothstep(0.16, 0.72, noiseToMeasured);"))
        assertTrue(shader.contains("if (!(authority > 1.0e-5)) return center;"))
        assertTrue(shader.contains("float effectiveDetailGain = mix(1.0"))
        assertTrue(shader.contains("detailGainOut = effectiveDetailGain;"))

        // Valid S/O must no longer imply unconditional mutation.
        assertFalse(shader.contains("float filtered = localMean + clamp(detailGain, 0.0, 1.0)"))
    }

    @Test
    fun `baseline NR diagnostics are sampled and exported without becoming authority`() {
        val shader = source("src/main/cpp/vulkan/shaders/spectra_raw_finalize.comp")
        val backend = source("src/main/cpp/vulkan/VulkanSpectraRawFinalizeBackend.cpp")
        val header = source("src/main/cpp/vulkan/VulkanSpectraRawFinalizeBackend.h")
        val isp = source("src/main/cpp/IspCore.cpp")

        assertTrue(shader.contains("NR_TELEMETRY_START"))
        assertTrue(shader.contains("((gid.x & 7u) == 0u) && ((gid.y & 7u) == 0u)"))
        assertTrue(shader.contains("NR_NEAR_IDENTITY_COUNT"))
        assertTrue(shader.contains("NR_STRONGLY_FILTERED_COUNT"))

        assertTrue(backend.contains("constexpr std::uint32_t kExposureTelemetryWords = 615u;"))
        assertTrue(header.contains("float predictedNoiseSigma = 0.0f;"))
        assertTrue(header.contains("float baselineNrAuthority = 0.0f;"))
        assertTrue(header.contains("float meanDetailGain = 1.0f;"))
        assertTrue(header.contains("float fractionNearIdentity = 1.0f;"))
        assertTrue(header.contains("float fractionStronglyFiltered = 0.0f;"))

        listOf(
            "predictedNoiseSigma",
            "noisePressure",
            "baselineNrAuthority",
            "meanDetailGain",
            "fractionNearIdentity",
            "fractionStronglyFiltered",
            "spectraOffPixelMutationSemantics"
        ).forEach { key -> assertTrue("missing debug metric $key", isp.contains(key)) }
    }

    @Test
    fun `Spectra Off keeps neural identity while baseline can become physical identity`() {
        val isp = source("src/main/cpp/IspCore.cpp")
        val shader = source("src/main/cpp/vulkan/shaders/spectra_raw_finalize.comp")

        assertTrue(isp.contains("EXACT_USER_DISABLED_IDENTITY"))
        assertTrue(isp.contains("executeSpectraRawFinalizeFromRawNormalize"))
        assertTrue(isp.contains("physical_baseline_nr_noise_gated,defect_correction,green_split,lens_shading,neural_identity"))
        assertTrue(shader.contains("clean daylight can resolve to exact identity"))
    }

    @Test
    fun `11A async and 11B highlight architecture remain outside denoise rewrite`() {
        val backend = source("src/main/cpp/vulkan/VulkanRawPreviewBackend.cpp")
        val full = source("src/main/cpp/vulkan/shaders/raw_preview.comp")
        val fast = source("src/main/cpp/vulkan/shaders/raw_preview_image.comp")

        assertTrue(backend.contains("vkGetFenceStatus"))
        assertTrue(full.contains("reconstructRawHighlightDirect"))
        assertTrue(fast.contains("reconstructRawHighlightDirect"))
        assertFalse(full.contains("magenta suppress", ignoreCase = true))
        assertFalse(fast.contains("magenta suppress", ignoreCase = true))
    }
}
