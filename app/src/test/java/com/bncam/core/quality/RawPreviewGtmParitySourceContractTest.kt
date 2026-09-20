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
    fun `raw preview uses shared capture equivalent scene exposure GTM and display ordering`() {
        val bufferShader = source("src/main/cpp/vulkan/shaders/raw_preview.comp")
        val imageShader = source("src/main/cpp/vulkan/shaders/raw_preview_image.comp")
        val shared = source("src/main/cpp/vulkan/shaders/preview_scene_exposure.glsl")
        val captureGtm = source("src/main/cpp/GlobalToneMappingPolicy.h")
        val backend = source("src/main/cpp/vulkan/VulkanRawPreviewBackend.cpp")
        val preview = source("src/main/cpp/RawPreview.cpp")

        assertTrue(shared.contains("resolvePreviewSceneExposurePolicy("))
        assertTrue(shared.contains("resolvePreviewGlobalToneMappingPlan("))
        assertTrue(shared.contains("0.86 - 0.16 * highlightPressure - 0.025 * rangePressure"))
        assertTrue(captureGtm.contains("0.86f - 0.16f * out.highlightPressure - 0.025f * rangePressure"))

        for (shader in listOf(bufferShader, imageShader)) {
            assertTrue(shader.contains("#include \"preview_scene_exposure.glsl\""))
            assertTrue(shader.contains("resolvePreviewSceneExposurePolicy("))
            assertTrue(shader.contains("resolvePreviewGlobalToneMappingPlan("))
            assertTrue(shader.contains("applyPreviewGtm(rgb)"))
            assertTrue(shader.contains("applyPreviewFllf(gid, rgb)"))
            assertTrue(shader.contains("const float startCompression = 0.88"))
            assertTrue(shader.contains("const float shoulderPrepStart = 0.72"))
            assertFalse(shader.contains("applyPreviewBroadShadowPlacement("))
            assertFalse(shader.contains("logSceneShape("))
        }

        assertTrue(backend.contains("push.cfaAndMode = packedMode(3u)"))
        assertFalse(backend.contains("push.cfaAndMode = packedMode(5u)"))
        assertFalse(backend.contains("push.cfaAndMode = packedMode(6u)"))
        assertFalse(preview.contains("quality.softBlackPointAnchor"))
        assertFalse(preview.contains("previewRequest.userSoftBlackAnchor"))
    }

    @Test
    fun `buffer and gpu resident image preview share the fixed statistics prefix and Phase 11E tail`() {
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
            "TONE_SCENE_KEY_INDEX = 544u",
            "PROFILE_TONE_EXPOSURE_INDEX = 552u",
            "PHYSICAL_AWB_START_INDEX = 572u",
            "HIGHLIGHT_TELEMETRY_START_INDEX",
            "PREVIEW_SCENE_EXPOSURE_START_INDEX",
            "RAW_TRUE_SATURATED_COUNT_INDEX = PREVIEW_SCENE_EXPOSURE_START_INDEX + PREVIEW_SCENE_EXPOSURE_WORDS",
            "ANALYSIS_NV21_START_INDEX = RAW_TRUE_SATURATED_COUNT_INDEX + SENSOR_EXPOSURE_TELEMETRY_WORDS"
        )
        for (token in required) {
            assertTrue("buffer shader missing $token", bufferShader.contains(token))
            assertTrue("image shader missing $token", imageShader.contains(token))
        }
        assertTrue(backend.contains("PREVIEW_AWB_STATS_START_WORD = 572u"))
        assertTrue(backend.contains("PREVIEW_SCENE_EXPOSURE_START_WORD == 31505u"))
        assertTrue(backend.contains("PREVIEW_ANALYSIS_NV21_START_WORD == 31512u"))
        assertTrue(imageShader.contains("atomicAdd(statsBuffer[RAW_SAMPLE_COUNT_INDEX], 1u)"))
        assertFalse(imageShader.contains("ANALYSIS_LUMA_START_INDEX"))
    }

    @Test
    fun `preview exposure smoothing survives per frame stats clear without CPU readback`() {
        val backend = source("src/main/cpp/vulkan/VulkanRawPreviewBackend.cpp")
        val header = source("src/main/cpp/vulkan/VulkanRawPreviewBackend.h")
        val shaders = listOf(
            source("src/main/cpp/vulkan/shaders/raw_preview.comp"),
            source("src/main/cpp/vulkan/shaders/raw_preview_image.comp")
        )

        assertTrue(header.contains("PersistentBuffer previewExposureState_;"))
        assertTrue(backend.contains("neutralExposureGain = 1.0f"))
        assertTrue(backend.contains("VkBufferMemoryBarrier exposureStateBarrier"))
        assertTrue(backend.contains("exposureStateBarrier.srcAccessMask = VK_ACCESS_SHADER_WRITE_BIT"))
        assertTrue(backend.contains("VK_ACCESS_SHADER_READ_BIT | VK_ACCESS_SHADER_WRITE_BIT"))
        assertFalse(header.contains("previousExposureGain_"))
        assertFalse(backend.contains("previousExposureGain_"))
        shaders.forEach { shader ->
            assertTrue(shader.contains("binding = 6) buffer PreviewExposureState"))
            assertTrue(shader.contains("previousGain = uintBitsToFloat(previewExposureState[0])"))
            assertTrue(shader.contains("exp2(-0.50)"))
            assertTrue(shader.contains("previewExposureState[0] = floatBitsToUint(exp2(exposurePlan.appliedEv))"))
        }
    }
}
