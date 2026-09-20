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
        val fullShader = source(File(root, "src/main/cpp/vulkan/shaders/raw_preview.comp").path)
        val fastShader = source(File(root, "src/main/cpp/vulkan/shaders/raw_preview_image.comp").path)

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

        assertTrue(backendHeader.contains("PersistentBuffer previewExposureState_;"))
        assertTrue(backend.contains("VkBufferMemoryBarrier exposureStateBarrier"))
        assertFalse(backend.contains("previousExposureGain_"))
        assertTrue(fullShader.contains("previewExposureState[0] = floatBitsToUint(exp2(exposurePlan.appliedEv))"))
        assertTrue(fastShader.contains("previewExposureState[0] = floatBitsToUint(exp2(exposurePlan.appliedEv))"))
        assertTrue(fullShader.contains("rgb *= previewProfileExposureMultiplier();"))
        assertTrue(fastShader.contains("rgb *= profileExposureMultiplier();"))
    }

    @Test
    fun fullRawPreviewConsumesDetailedProfileToneWhileFastPathKeepsExposureContract() {
        val root = File(System.getProperty("user.dir"))
        val full = source(File(root, "src/main/cpp/vulkan/shaders/raw_preview.comp").path)
        val fast = source(File(root, "src/main/cpp/vulkan/shaders/raw_preview_image.comp").path)

        val detailed = listOf(
            "PROFILE_TONE_HIGHLIGHTS_INDEX = 553u",
            "PROFILE_TONE_SHADOWS_INDEX = 554u",
            "PROFILE_TONE_WHITES_INDEX = 555u",
            "PROFILE_TONE_BLACKS_INDEX = 556u",
            "PROFILE_TONE_CONTRAST_INDEX = 557u",
            "PROFILE_LOCAL_TONE_BIAS_INDEX = 558u"
        )
        detailed.forEach { token -> assertTrue("full preview missing $token", full.contains(token)) }

        // Both kernels preserve the explicit profile Exposure stage after the automatic scene owner.
        assertTrue(full.contains("PROFILE_TONE_EXPOSURE_INDEX = 552u"))
        assertTrue(fast.contains("PROFILE_TONE_EXPOSURE_INDEX = 552u"))
        assertTrue(full.contains("rgb *= previewAutomaticExposureMultiplier();"))
        assertTrue(full.contains("rgb *= previewProfileExposureMultiplier();"))
        assertTrue(fast.contains("rgb *= autoExposureMultiplier();"))
        assertTrue(fast.contains("rgb *= profileExposureMultiplier();"))
        // The fast image path intentionally avoids the detailed tone-stat controls for realtime cost;
        // 11C parity is the automatic global scene exposure, not identical downstream tone cost.
        detailed.forEach { token -> assertFalse("fast path should not own detailed profile tone: $token", fast.contains(token)) }
    }

}
