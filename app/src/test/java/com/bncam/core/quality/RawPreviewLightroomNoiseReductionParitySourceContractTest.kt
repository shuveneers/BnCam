package com.bncam.core.quality

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RawPreviewLightroomNoiseReductionParitySourceContractTest {
    private val appDir: File = sequenceOf(File("."), File("app"))
        .firstOrNull { File(it, "src/main/cpp/vulkan/VulkanRawPreviewBackend.cpp").isFile }
        ?: error("Cannot locate app module from ${File(".").absolutePath}")

    private fun source(relative: String): String = File(appDir, relative).readText()

    @Test
    fun `six lightroom noise reduction controls reach raw preview without fake spectra`() {
        val manager = source("src/main/java/com/bncam/core/engine/BnCameraManager.kt")
        val renderer = source("src/main/java/com/bncam/ui/screens/capture/RawPreviewRenderer.kt")
        val imageUtils = source("src/main/java/com/bncam/core/engine/ImageUtils.kt")
        val native = source("src/main/cpp/native-lib.cpp")
        val preview = source("src/main/cpp/RawPreview.cpp")
        val request = source("src/main/cpp/vulkan/VulkanRawPreviewBackend.h")
        val backend = source("src/main/cpp/vulkan/VulkanRawPreviewBackend.cpp")

        val controls = listOf(
            "profileNrLuminance",
            "profileNrLuminanceDetail",
            "profileNrLuminanceContrast",
            "profileNrColor",
            "profileNrColorDetail",
            "profileNrColorSmoothness"
        )
        controls.forEach { control ->
            assertTrue("manager must resolve $control", manager.contains(control))
            assertTrue("render config must carry $control", renderer.contains(control))
            assertTrue("JNI must carry $control", imageUtils.contains(control) && native.contains(control))
            assertTrue("native preview must carry $control", preview.contains(control))
            assertTrue("Vulkan request must carry $control", request.contains(control))
        }
        assertTrue(backend.contains("constexpr std::uint64_t PREVIEW_STATS_BASE_WORDS = 572u;"))
        assertTrue(backend.contains("const float profileNrSeeds[6]"))
        assertFalse(
            "RAW preview creative NR must not invoke the capture post-demosaic SPECTRA denoise backend",
            backend.contains("SpectraResidentPostDemosaic") ||
                preview.contains("SpectraResidentPostDemosaic") ||
                backend.contains("executeSpectraDenoise") ||
                preview.contains("executeSpectraDenoise")
        )
    }

    @Test
    fun `both raw preview shaders use one compact noise reduction contract`() {
        val shaders = listOf(
            source("src/main/cpp/vulkan/shaders/raw_preview.comp"),
            source("src/main/cpp/vulkan/shaders/raw_preview_image.comp")
        )
        val required = listOf(
            "PROFILE_NR_LUMINANCE_INDEX = 566u",
            "PROFILE_NR_LUMINANCE_DETAIL_INDEX = 567u",
            "PROFILE_NR_LUMINANCE_CONTRAST_INDEX = 568u",
            "PROFILE_NR_COLOR_INDEX = 569u",
            "PROFILE_NR_COLOR_DETAIL_INDEX = 570u",
            "PROFILE_NR_COLOR_SMOOTHNESS_INDEX = 571u",
            "ANALYSIS_NV21_START_INDEX = 572u",
            "vec3 applyPreviewProfileNoiseReduction",
            "if (luminanceAmount <= 1.0e-6 && colorAmount <= 1.0e-6) return sensorRgb",
            "sensorRgb = applyPreviewProfileNoiseReduction"
        )
        required.forEach { needle ->
            shaders.forEach { shader -> assertTrue("missing RAW preview NR contract: $needle", shader.contains(needle)) }
        }
        listOf(
            "PROFILE_NR_LUMINANCE_INDEX",
            "PROFILE_NR_LUMINANCE_DETAIL_INDEX",
            "PROFILE_NR_LUMINANCE_CONTRAST_INDEX",
            "PROFILE_NR_COLOR_INDEX",
            "PROFILE_NR_COLOR_DETAIL_INDEX",
            "PROFILE_NR_COLOR_SMOOTHNESS_INDEX",
            "ANALYSIS_NV21_START_INDEX",
            "applyPreviewProfileNoiseReduction"
        ).forEach { token ->
            assertEquals(
                "both RAW preview shaders must reference $token equally often",
                token.toRegex().findAll(shaders[0]).count(),
                token.toRegex().findAll(shaders[1]).count()
            )
        }
    }

    @Test
    fun `preview noise reduction remains in existing render dispatch and keeps push constants stable`() {
        val backend = source("src/main/cpp/vulkan/VulkanRawPreviewBackend.cpp")
        val shaders = listOf(
            source("src/main/cpp/vulkan/shaders/raw_preview.comp"),
            source("src/main/cpp/vulkan/shaders/raw_preview_image.comp")
        )
        assertTrue(backend.contains("static_assert(sizeof(PushConstants) == 128u"))
        shaders.forEach { shader ->
            assertTrue(shader.contains("prepareRenderBayerTile();"))
            assertTrue(shader.contains("sensorRgb = applyPreviewProfileNoiseReduction"))
            assertFalse(shader.contains("profileNrFullFramePass"))
        }
    }
}
