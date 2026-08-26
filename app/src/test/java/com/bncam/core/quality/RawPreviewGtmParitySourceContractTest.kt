package com.bncam.core.quality

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RawPreviewGtmParitySourceContractTest {
    private val appDir: File = sequenceOf(File("."), File("app"))
        .firstOrNull { File(it, "src/main/cpp/RawPreview.cpp").isFile }
        ?: error("Cannot locate app module from ${File(".").absolutePath}")

    private fun source(relative: String): String = File(appDir, relative).readText()

    @Test
    fun `raw preview uses scene adaptive GTM instead of legacy dark fixed mapping`() {
        val bufferShader = source("src/main/cpp/vulkan/shaders/raw_preview.comp")
        val imageShader = source("src/main/cpp/vulkan/shaders/raw_preview_image.comp")
        val preview = source("src/main/cpp/RawPreview.cpp")

        for (shader in listOf(bufferShader, imageShader)) {
            assertTrue(shader.contains("float midtoneTarget = clamp("))
            assertTrue(shader.contains("0.155 + 0.016 * shadowPressure + 0.014 * dynamicRangePressure"))
            assertTrue(shader.contains("GTM_SHOULDER_START_INDEX"))
            assertTrue(shader.contains("GTM_SHOULDER_STRENGTH_INDEX"))
            assertTrue(shader.contains("GTM_BLACK_ANCHOR_INDEX"))
            assertTrue(shader.contains("GTM_LOWER_MID_LIFT_INDEX"))
            assertTrue(shader.contains("GTM_CONTRAST_STRENGTH_INDEX"))
            assertTrue(shader.contains("float applyDynamicGtmPreCurve"))
            assertTrue(shader.contains("float applyDynamicGtmLowerMidLift"))
            assertFalse(shader.contains("const float shoulderStart = 0.68"))
            assertFalse(shader.contains("float target = lowLight ? 0.090 : 0.100"))
        }
        assertFalse(preview.contains("quality.softBlackPointAnchor"))
        assertFalse(preview.contains("previewRequest.userSoftBlackAnchor"))
        assertFalse(bufferShader.contains("USER_BLACK_ANCHOR_INDEX"))
        assertFalse(imageShader.contains("USER_BLACK_ANCHOR_INDEX"))
    }

    @Test
    fun `buffer and gpu resident image preview share one statistics layout`() {
        val bufferShader = source("src/main/cpp/vulkan/shaders/raw_preview.comp")
        val imageShader = source("src/main/cpp/vulkan/shaders/raw_preview_image.comp")
        val backend = source("src/main/cpp/vulkan/VulkanRawPreviewBackend.cpp")

        val required = listOf(
            "DISPLAY_LUMA_HISTOGRAM64_START_INDEX = 283u",
            "DISPLAY_R_HISTOGRAM64_START_INDEX = 347u",
            "DISPLAY_G_HISTOGRAM64_START_INDEX = 411u",
            "DISPLAY_B_HISTOGRAM64_START_INDEX = 475u",
            "RAW_NEAR_CLIP_COUNT_INDEX = 539u",
            "RAW_SAMPLE_COUNT_INDEX = 540u",
            "DISPLAY_R_CLIP_COUNT_INDEX = 541u",
            "DISPLAY_G_CLIP_COUNT_INDEX = 542u",
            "DISPLAY_B_CLIP_COUNT_INDEX = 543u",
            "ANALYSIS_NV21_START_INDEX = 572u"
        )
        for (token in required) {
            assertTrue("buffer shader missing $token", bufferShader.contains(token))
            assertTrue("image shader missing $token", imageShader.contains(token))
        }
        assertTrue(backend.contains("PREVIEW_STATS_BASE_WORDS = 572u"))
        assertTrue(imageShader.contains("atomicAdd(statsBuffer[RAW_SAMPLE_COUNT_INDEX], 1u)"))
        assertTrue(imageShader.contains("compactNv21AnalysisEnabled()"))
        assertFalse(imageShader.contains("ANALYSIS_LUMA_START_INDEX"))
    }

    @Test
    fun `preview exposure smoothing survives the per frame stats clear`() {
        val backend = source("src/main/cpp/vulkan/VulkanRawPreviewBackend.cpp")
        val header = source("src/main/cpp/vulkan/VulkanRawPreviewBackend.h")
        val shader = source("src/main/cpp/vulkan/shaders/raw_preview.comp")

        assertTrue(header.contains("float previousExposureGain_ = 0.0f"))
        assertTrue(backend.contains("seededExposureGain"))
        assertTrue(backend.contains("vkCmdUpdateBuffer"))
        assertTrue(backend.contains("previousExposureGain_ = automaticExposureGain"))
        assertFalse(backend.contains("previousExposureGain_ = result.exposureGain"))
        assertTrue(shader.contains("backend seeds EXPOSURE_INDEX with the previous completed"))
    }
}
