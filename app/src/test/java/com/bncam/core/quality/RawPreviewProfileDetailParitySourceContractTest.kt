package com.bncam.core.quality

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RawPreviewProfileDetailParitySourceContractTest {
    private val appDir: File = sequenceOf(File("."), File("app"))
        .firstOrNull { File(it, "src/main/cpp/vulkan/VulkanRawPreviewBackend.cpp").isFile }
        ?: error("Cannot locate app module from ${File(".").absolutePath}")

    private fun source(relative: String): String = File(appDir, relative).readText()

    @Test
    fun `lightroom detail reaches raw preview through one four-control authority`() {
        val manager = source("src/main/java/com/bncam/core/engine/BnCameraManager.kt")
        val renderer = source("src/main/java/com/bncam/ui/screens/capture/RawPreviewRenderer.kt")
        val imageUtils = source("src/main/java/com/bncam/core/engine/ImageUtils.kt")
        val native = source("src/main/cpp/native-lib.cpp")
        val preview = source("src/main/cpp/RawPreview.cpp")
        val backendHeader = source("src/main/cpp/vulkan/VulkanRawPreviewBackend.h")
        val backend = source("src/main/cpp/vulkan/VulkanRawPreviewBackend.cpp")

        listOf("profileDetailAmount", "profileDetailRadius", "profileDetailDetail", "profileDetailMasking")
            .forEach { control ->
                assertTrue("manager must resolve $control", manager.contains(control))
                assertTrue("render config must carry $control", renderer.contains(control))
                assertTrue("JNI must carry $control", imageUtils.contains(control) && native.contains(control))
                assertTrue("native request must carry $control", preview.contains(control))
                assertTrue("Vulkan request must carry $control", backendHeader.contains(control))
            }
        assertTrue(backend.contains("const float profileDetailSeeds[4]"))
        assertTrue(backend.contains("PREVIEW_STATS_BASE_WORDS = 572u"))
    }

    @Test
    fun `raw preview shaders consume detail without an extra image pass or chroma sharpening`() {
        val shaders = listOf(
            source("src/main/cpp/vulkan/shaders/raw_preview.comp"),
            source("src/main/cpp/vulkan/shaders/raw_preview_image.comp")
        )
        shaders.forEach { shader ->
            assertTrue(shader.contains("PROFILE_DETAIL_AMOUNT_INDEX = 562u"))
            assertTrue(shader.contains("PROFILE_DETAIL_RADIUS_INDEX = 563u"))
            assertTrue(shader.contains("PROFILE_DETAIL_VALUE_INDEX = 564u"))
            assertTrue(shader.contains("PROFILE_DETAIL_MASKING_INDEX = 565u"))
            assertTrue(shader.contains("ANALYSIS_NV21_START_INDEX = 572u"))
            assertTrue(shader.contains("float previewProfileLumaDetail"))
            assertTrue(shader.contains("previewProfileLumaDetail(int(gid.x), int(gid.y))"))
            assertTrue(shader.contains("if (amount <= 1.0e-6) return 0.0"))
            assertTrue(shader.contains("maskingGate"))
            assertTrue(shader.contains("previewGreenStructureAt"))
            assertFalse(shader.contains("profileDetailRgbSharpen"))
        }
    }

    @Test
    fun `raw preview detail preserves the 128 byte push constant contract`() {
        val backend = source("src/main/cpp/vulkan/VulkanRawPreviewBackend.cpp")
        assertTrue(backend.contains("static_assert(sizeof(PushConstants) == 128u"))
        assertFalse(backend.substringBefore("static_assert(sizeof(PushConstants)")
            .contains("profileDetailAmount"))
    }
}
