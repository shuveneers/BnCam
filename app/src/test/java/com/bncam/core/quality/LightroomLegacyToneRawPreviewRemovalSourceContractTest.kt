package com.bncam.core.quality

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LightroomLegacyToneRawPreviewRemovalSourceContractTest {
    private val appDir: File = sequenceOf(File("."), File("app"))
        .firstOrNull { File(it, "src/main/cpp/RawPreview.cpp").isFile }
        ?: error("Cannot locate app module")
    private fun source(relative: String): String = File(appDir, relative).readText()

    @Test
    fun `raw preview no longer carries legacy shadow toe black controls`() {
        val renderer = source("src/main/java/com/bncam/ui/screens/capture/RawPreviewRenderer.kt")
        val imageUtils = source("src/main/java/com/bncam/core/engine/ImageUtils.kt")
        val native = source("src/main/cpp/native-lib.cpp")
        val preview = source("src/main/cpp/RawPreview.cpp")
        val backend = source("src/main/cpp/vulkan/VulkanRawPreviewBackend.cpp")
        val header = source("src/main/cpp/vulkan/VulkanRawPreviewBackend.h")
        val shader = source("src/main/cpp/vulkan/shaders/raw_preview.comp")

        val config = renderer.substringAfter("data class RawPreviewRenderConfig(").substringBefore(")")
        assertFalse(config.contains("shadowLift"))
        assertFalse(config.contains("toeExponent"))
        assertFalse(config.contains("softBlackPointAnchor"))
        val kotlinNative = imageUtils.substringAfter("private external fun renderRawPreviewNative(").substringBefore("): IntArray?")
        assertFalse(kotlinNative.contains("shadowLift"))
        assertFalse(kotlinNative.contains("toeExponent"))
        assertFalse(kotlinNative.contains("softBlackPointAnchor"))
        val nativePreview = native.substringAfter("Java_com_bncam_core_engine_ImageUtils_renderRawPreviewNative(")
            .substringBefore("Java_com_bncam_core_engine_ImageUtils_analyzeFrameCandidateNative")
        assertFalse(nativePreview.contains("shadowLift"))
        assertFalse(nativePreview.contains("toeExponent"))
        assertFalse(nativePreview.contains("softBlackPointAnchor"))
        assertFalse(preview.contains("quality.shadowLift"))
        assertFalse(preview.contains("quality.toeExponent"))
        assertFalse(preview.contains("quality.softBlackPointAnchor"))
        assertFalse(header.contains("userSoftBlackAnchor"))
        assertFalse(backend.contains("userBlackAnchorBits"))
        assertFalse(shader.contains("USER_BLACK_ANCHOR_INDEX"))
        assertTrue(shader.contains("profileBlacks"))
    }
}
