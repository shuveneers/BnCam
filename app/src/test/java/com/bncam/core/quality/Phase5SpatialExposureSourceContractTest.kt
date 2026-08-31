package com.bncam.core.quality

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class Phase5SpatialExposureSourceContractTest {
    private val appDir: File = sequenceOf(File("."), File("app"))
        .firstOrNull { File(it, "src/main/cpp/IspCore.cpp").isFile }
        ?: error("Cannot locate app module")

    private fun source(relative: String): String = File(appDir, relative).readText()

    @Test
    fun `raw capture exposure has one Vulkan spatial owner`() {
        val isp = source("src/main/cpp/IspCore.cpp")
        val backend = source("src/main/cpp/vulkan/VulkanSpectraRawFinalizeBackend.cpp")
        val shader = source("src/main/cpp/vulkan/shaders/spectra_raw_finalize.comp")

        assertTrue(isp.contains("const float automaticExposureGain = isRawBayer ? 1.0f"))
        assertTrue(isp.contains("DISABLED_PHASE5_EXPOSURE_SINGLE_OWNER"))
        assertTrue(backend.contains("push.mode = 3u;"))
        assertTrue(backend.contains("push.mode = 4u;"))
        assertTrue(backend.contains("push.mode = 5u;"))
        assertTrue(shader.contains("outputMosaic[index] = sceneClamp(value * exp2(ev));"))
        assertTrue(shader.contains("EXP_SUMMARY_POS_FRAC"))
        assertTrue(shader.contains("EXP_SUMMARY_NEU_FRAC"))
        assertTrue(shader.contains("EXP_SUMMARY_NEG_FRAC"))
    }

    @Test
    fun `raw preview shares physical signed exposure ownership`() {
        val manager = source("src/main/java/com/bncam/core/engine/BnCameraManager.kt")
        val backend = source("src/main/cpp/vulkan/VulkanRawPreviewBackend.cpp")
        val shader = source("src/main/cpp/vulkan/shaders/raw_preview.comp")

        assertTrue(manager.contains("val previewExposureGain = 1.0f"))
        assertFalse(manager.contains("1.2f + 0.8f * (sensitivityIso.coerceAtMost(1600)"))
        assertTrue(manager.contains("physicalGreenNoiseSo"))
        assertTrue(manager.contains("CaptureResult.SENSOR_NOISE_PROFILE"))
        assertTrue(backend.contains("(5u << 8u)"))
        assertTrue(backend.contains("(6u << 8u)"))
        assertFalse(backend.contains("(3u << 8u)"))
        assertTrue(shader.contains("sensorRgb *= exp2(spatialExposureEvAtPreview(gid));"))
        assertTrue(shader.indexOf("sensorRgb *= exp2(spatialExposureEvAtPreview(gid));") <
            shader.indexOf("vec3 rgb = colorTransform(sensorRgb);"))
    }

    @Test
    fun `preview exposure ABI is compact and fixed`() {
        val backend = source("src/main/cpp/vulkan/VulkanRawPreviewBackend.cpp")
        val native = source("src/main/cpp/native-lib.cpp")
        val renderer = source("src/main/java/com/bncam/ui/screens/capture/RawPreviewRenderer.kt")

        assertTrue(backend.contains("PREVIEW_ANALYSIS_NV21_START_WORD == 31499u"))
        assertTrue(native.contains("SPATIAL_EXPOSURE_DIAGNOSTICS_COUNT = 12"))
        assertTrue(renderer.contains("result.getOrElse(613)"))
        assertTrue(renderer.contains("result.getOrElse(624)"))
    }

    @Test
    fun `cpu adaptive exposure is failure reference only`() {
        val isp = source("src/main/cpp/IspCore.cpp")
        assertTrue(isp.contains("Failure/reference only"))
        assertTrue(isp.countSubstring("bncam::raw_exposure::resolve(") == 1)
    }

    private fun String.countSubstring(needle: String): Int {
        if (needle.isEmpty()) return 0
        var count = 0
        var start = 0
        while (true) {
            val index = indexOf(needle, start)
            if (index < 0) return count
            count++
            start = index + needle.length
        }
    }
}
