package com.bncam.core.quality

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class RawPreviewProfileToneConsumptionSourceContractTest {
    private fun source(path: String): String = File(path).readText()

    @Test
    fun profileToneControlsReachRawPreviewWithoutPollutingAutomaticExposureState() {
        val root = File(System.getProperty("user.dir"))
        val manager = source(File(root, "src/main/java/com/bncam/core/engine/BnCameraManager.kt").path)
        val renderer = source(File(root, "src/main/java/com/bncam/ui/screens/capture/RawPreviewRenderer.kt").path)
        val imageUtils = source(File(root, "src/main/java/com/bncam/core/engine/ImageUtils.kt").path)
        val nativeLib = source(File(root, "src/main/cpp/native-lib.cpp").path)
        val rawPreview = source(File(root, "src/main/cpp/RawPreview.cpp").path)
        val backendHeader = source(File(root, "src/main/cpp/vulkan/VulkanRawPreviewBackend.h").path)
        val backend = source(File(root, "src/main/cpp/vulkan/VulkanRawPreviewBackend.cpp").path)

        val controls = listOf(
            "profileToneExposure",
            "profileToneHighlights",
            "profileToneShadows",
            "profileToneWhites",
            "profileToneBlacks",
            "profileToneContrast",
            "profileLocalToneBias"
        )
        controls.forEach { control ->
            assertTrue("manager must snapshot $control", manager.contains(control))
            assertTrue("renderer config must carry $control", renderer.contains(control))
            assertTrue("JNI bridge must carry $control", imageUtils.contains(control) && nativeLib.contains(control))
            assertTrue("native preview must consume $control", rawPreview.contains(control))
            assertTrue("Vulkan request must carry $control", backendHeader.contains(control) && backend.contains(control))
        }

        assertTrue(backend.contains("constexpr std::uint64_t PREVIEW_STATS_BASE_WORDS = 572u;"))
        assertTrue(backend.contains("previousExposureGain_ = automaticExposureGain;"))
        assertFalse(
            "profile render Exposure must not feed the automatic preview EMA",
            backend.contains("previousExposureGain_ = result.exposureGain")
        )
    }

    @Test
    fun bothRawPreviewShadersUseTheSameProfileToneStatisticsContract() {
        val root = File(System.getProperty("user.dir"))
        val shaders = listOf(
            source(File(root, "src/main/cpp/vulkan/shaders/raw_preview.comp").path),
            source(File(root, "src/main/cpp/vulkan/shaders/raw_preview_image.comp").path)
        )
        val required = listOf(
            "PROFILE_TONE_EXPOSURE_INDEX = 552u",
            "PROFILE_TONE_HIGHLIGHTS_INDEX = 553u",
            "PROFILE_TONE_SHADOWS_INDEX = 554u",
            "PROFILE_TONE_WHITES_INDEX = 555u",
            "PROFILE_TONE_BLACKS_INDEX = 556u",
            "PROFILE_TONE_CONTRAST_INDEX = 557u",
            "PROFILE_LOCAL_TONE_BIAS_INDEX = 558u",
            "LTM_STRENGTH_INDEX = 559u",
            "LTM_MAX_LIFT_EV_INDEX = 560u",
            "LTM_MAX_COMPRESS_EV_INDEX = 561u",
            "ANALYSIS_NV21_START_INDEX = 572u",
            "previewProfileExposureMultiplier()",
            "0.090 * profileToneContrast",
            "applyPreviewGtmLook(rgb)",
            "profileCurveLuma = tone(preCurveLuma)",
            "PROFILE_LOCAL_TONE_BIAS_INDEX"
        )
        required.forEach { needle ->
            shaders.forEach { shader -> assertTrue("missing shader contract: $needle", shader.contains(needle)) }
        }

        val semanticTokens = listOf(
            "PROFILE_TONE_EXPOSURE_INDEX",
            "PROFILE_TONE_HIGHLIGHTS_INDEX",
            "PROFILE_TONE_SHADOWS_INDEX",
            "PROFILE_TONE_WHITES_INDEX",
            "PROFILE_TONE_BLACKS_INDEX",
            "PROFILE_TONE_CONTRAST_INDEX",
            "PROFILE_LOCAL_TONE_BIAS_INDEX",
            "ANALYSIS_NV21_START_INDEX"
        )
        semanticTokens.forEach { token ->
            assertEquals(
                "both preview shaders must reference $token equally often",
                token.toRegex().findAll(shaders[0]).count(),
                token.toRegex().findAll(shaders[1]).count()
            )
        }
    }
}
