package com.bncam.core.quality

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProfilePresenceVibranceSourceContractTest {
    private val appDir: File = sequenceOf(File("."), File("app"))
        .firstOrNull { File(it, "src/main/cpp/ProfileColorManagement.h").isFile }
        ?: error("Cannot locate app module")
    private fun source(relative: String): String = File(appDir, relative).readText()

    @Test
    fun `vibrance uses former hue slot without expanding raw preview push constants`() {
        val keys = source("src/main/java/com/bncam/data/settings/ProfileLensTuningSettings.kt")
        val ui = source("src/main/java/com/bncam/ui/screens/settings/lens_profiles/ProfileTuningSubScreens.kt")
        val render = source("src/main/java/com/bncam/core/quality/RenderQualityConfig.kt")
        val nativeConfig = source("src/main/cpp/NativeRenderQualityConfig.h")
        val cpu = source("src/main/cpp/ProfileColorManagement.h")
        val preview = source("src/main/cpp/vulkan/VulkanRawPreviewBackend.cpp")
        val previewShader = source("src/main/cpp/vulkan/shaders/raw_preview.comp")
        val resident = source("src/main/cpp/vulkan/VulkanSpectraResidentToneBackend.cpp")
        val residentShader = source("src/main/cpp/vulkan/shaders/spectra_tone_resident.comp")

        assertTrue(keys.contains("PRESENCE_VIBRANCE"))
        assertTrue(ui.contains("title = \"Vibrance\""))
        assertTrue(render.contains("val vibrance: Float = 0f"))
        assertTrue(nativeConfig.contains("profilePresenceVibrance"))
        assertFalse(nativeConfig.contains("profileColorHueShift"))
        assertTrue(cpu.contains("protection = std::clamp(1.0f - saturation"))
        assertTrue(preview.contains("static_assert(sizeof(PushConstants) == 128u"))
        assertTrue(previewShader.contains("float profileVibrance;"))
        assertFalse(previewShader.contains("profileHueShift"))
        assertTrue(resident.contains("static_assert(sizeof(PushConstants) == 128u"))
        assertTrue(residentShader.contains("float profileVibrance;"))
        assertFalse(residentShader.contains("hueCos"))

        val yuvHeader = source("src/main/cpp/vulkan/VulkanYuvSingleFrameBackend.h")
        val yuvShader = source("src/main/cpp/vulkan/shaders/yuv_single_frame_isp.comp")
        assertTrue(yuvHeader.contains("float vibrance = 0.0f;"))
        assertFalse(yuvHeader.contains("float hue = 0.0f;"))
        assertTrue(yuvShader.contains("float vibranceControl;"))
        assertTrue(yuvShader.contains("float protection = clamp(1.0 - currentSaturation"))
        assertFalse(yuvShader.contains("hueControl"))
    }
}
