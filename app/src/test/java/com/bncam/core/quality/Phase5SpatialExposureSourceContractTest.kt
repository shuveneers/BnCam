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
    fun `raw finalize spatial exposure is retired while phase11f owns global development placement`() {
        val isp = source("src/main/cpp/IspCore.cpp")
        val backend = source("src/main/cpp/vulkan/VulkanSpectraRawFinalizeBackend.cpp")
        val shader = source("src/main/cpp/vulkan/shaders/spectra_raw_finalize.comp")
        val globalPolicy = source("src/main/cpp/GlobalSceneExposurePolicy.h")

        // Compatibility implementation remains buildable, but production IspCore does not enable it.
        assertTrue(isp.contains("request.adaptiveExposureEnabled = false"))
        assertTrue(isp.contains("automaticPostDemosaicExposureOwner=GLOBAL_SCENE_EXPOSURE_PLAN_PHASE11F"))
        assertTrue(isp.contains("automaticGlobalSceneEv=") && isp.contains("globalSceneExposurePlan.appliedEv"))
        assertTrue(globalPolicy.contains("single automatic JPEG-development exposure owner"))
        assertTrue(backend.contains("if (request.adaptiveExposureEnabled)"))
        assertTrue(shader.contains("outputMosaic[index] = sceneClamp(value * exp2(ev));"))
        assertTrue(shader.contains("EXP_SUMMARY_POS_FRAC"))
        assertTrue(shader.contains("EXP_SUMMARY_NEU_FRAC"))
        assertTrue(shader.contains("EXP_SUMMARY_NEG_FRAC"))
    }

    @Test
    fun `raw preview retires duplicate spatial exposure and uses lightweight FLLF`() {
        val manager = source("src/main/java/com/bncam/core/engine/BnCameraManager.kt")
        val backend = source("src/main/cpp/vulkan/VulkanRawPreviewBackend.cpp")
        val full = source("src/main/cpp/vulkan/shaders/raw_preview.comp")
        val fast = source("src/main/cpp/vulkan/shaders/raw_preview_image.comp")
        val renderer = source("src/main/java/com/bncam/ui/screens/capture/RawPreviewRenderer.kt")

        assertTrue(manager.contains("val previewExposureGain = 1.0f"))
        assertTrue(manager.contains("physicalGreenNoiseSo"))
        assertTrue(manager.contains("CaptureResult.SENSOR_NOISE_PROFILE"))

        assertTrue(backend.contains("push.cfaAndMode = packedMode(3u)"))
        assertFalse(backend.contains("push.cfaAndMode = packedMode(5u)"))
        assertFalse(backend.contains("push.cfaAndMode = packedMode(6u)"))
        assertTrue(backend.contains("VkBufferMemoryBarrier localToneBarrier"))

        listOf(full, fast).forEach { shader ->
            assertTrue(shader.contains("else if (mode == 3u) buildPreviewFllfBase"))
            assertFalse(shader.contains("mode == 5u"))
            assertFalse(shader.contains("mode == 6u"))
            assertFalse(shader.contains("sensorRgb *= exp2(spatialExposureEvAtPreview(gid));"))
        }
        assertFalse(full.contains("applyPreviewBroadShadowPlacement("))
        assertFalse(fast.contains("logSceneShape("))
        assertTrue(renderer.contains("legacySpatialExposure=RETIRED_PHASE11G"))
    }

    @Test
    fun `preview exposure ABI is compact and fixed`() {
        val backend = source("src/main/cpp/vulkan/VulkanRawPreviewBackend.cpp")
        val native = source("src/main/cpp/native-lib.cpp")
        val renderer = source("src/main/java/com/bncam/ui/screens/capture/RawPreviewRenderer.kt")

        assertTrue(backend.contains("PREVIEW_HIGHLIGHT_TELEMETRY_START_WORD == 31499u"))
        assertTrue(backend.contains("PREVIEW_ANALYSIS_NV21_START_WORD == 31512u"))
        assertTrue(native.contains("SPATIAL_EXPOSURE_DIAGNOSTICS_COUNT = 12"))
        assertTrue(renderer.contains("result.getOrElse(613)"))
        assertTrue(renderer.contains("result.getOrElse(624)"))
    }

    @Test
    fun `cpu adaptive preview exposure remains compile guarded fallback only`() {
        val preview = source("src/main/cpp/RawPreview.cpp")
        assertTrue(preview.contains("#if defined(BNCAM_ENABLE_RAW_PREVIEW_CPU_REFERENCE)"))
        assertTrue(preview.contains("PreviewExposureResult resolvePreviewExposure("))
        assertTrue(preview.countSubstring("resolvePreviewExposure(") == 2)
        assertTrue(preview.contains("#endif  // BNCAM_ENABLE_RAW_PREVIEW_CPU_REFERENCE"))
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
