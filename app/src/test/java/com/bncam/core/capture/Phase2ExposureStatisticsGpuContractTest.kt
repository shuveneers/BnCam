package com.bncam.core.capture

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class Phase2ExposureStatisticsGpuContractTest {
    private val appDir: File = sequenceOf(File("."), File("app"))
        .firstOrNull { File(it, "src/main/cpp/CMakeLists.txt").isFile }
        ?: error("Cannot locate app module from ${File(".").absolutePath}")

    private fun source(relative: String): String = File(appDir, relative).readText()

    @Test
    fun `YUV live histogram and clipping analysis are Vulkan primary`() {
        val cmake = source("src/main/cpp/CMakeLists.txt")
        val shader = source("src/main/cpp/vulkan/shaders/yuv_exposure_statistics.comp")
        val runtime = source("src/main/cpp/vulkan/VulkanRuntime.cpp")
        val native = source("src/main/cpp/native-lib.cpp")
        val manager = source("src/main/java/com/bncam/core/engine/BnCameraManager.kt")

        assertTrue(cmake.contains("VulkanYuvExposureStatisticsBackend.cpp"))
        assertTrue(cmake.contains("yuv_exposure_statistics.comp"))
        assertTrue(cmake.contains("bncam_yuv_exposure_statistics_shader"))
        assertTrue(runtime.contains("yuvExposureStatisticsBackend_.execute"))
        assertTrue(native.contains("executeYuvExposureStatistics(request)"))
        assertTrue(manager.contains("source = \"YUV_VULKAN_SAMPLED\""))

        assertTrue(shader.contains("const uint LUMA64 = 0u"))
        assertTrue(shader.contains("const uint RED64 = 64u"))
        assertTrue(shader.contains("const uint GREEN64 = 128u"))
        assertTrue(shader.contains("const uint BLUE64 = 192u"))
        assertTrue(shader.contains("RED_CLIP_COUNT"))
        assertTrue(shader.contains("HIGHLIGHT_X_WEIGHTED"))
    }

    @Test
    fun `CPU production work only gathers YUV samples and keeps full analysis as explicit failsafe`() {
        val manager = source("src/main/java/com/bncam/core/engine/BnCameraManager.kt")
        val gpuPrimary = manager.substringAfter("private fun calculateExposureStatisticsVulkan")
            .substringBefore("private fun calculateExposureStatisticsCpuFailsafe")

        assertTrue(gpuPrimary.contains("packed.putInt"))
        assertTrue(gpuPrimary.contains("ImageUtils.analyzeYuvExposureStatistics"))
        assertFalse(gpuPrimary.contains("0.2126f"))
        assertFalse(gpuPrimary.contains("298 * c"))
        assertTrue(manager.contains("source = \"YUV_CPU_FAILSAFE\""))
    }

    @Test
    fun `RAW preview telemetry expansion remains compatible with old interop slots`() {
        val native = source("src/main/cpp/native-lib.cpp")
        val renderer = source("src/main/java/com/bncam/ui/screens/capture/RawPreviewRenderer.kt")

        assertTrue(native.contains("constexpr int BASE_RESULT_SIZE = 49"))
        assertTrue(native.contains("jint expandedValues[EXPANDED_RESULT_SIZE]"))
        assertTrue(native.contains("DISPLAY_R64_START"))
        assertTrue(native.contains("RAW_NEAR_CLIP_INDEX"))
        assertTrue(renderer.contains("result.getOrElse(48)"))
    }
}
