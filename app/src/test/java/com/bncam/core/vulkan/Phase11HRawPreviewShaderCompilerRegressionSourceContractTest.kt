package com.bncam.core.vulkan

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class Phase11HRawPreviewShaderCompilerRegressionSourceContractTest {
    private val appDir: File = sequenceOf(File("."), File("app"))
        .firstOrNull { File(it, "src/main/cpp/vulkan/shaders/raw_preview.comp").isFile }
        ?: error("Cannot locate app module from ${File(".").absolutePath}")

    private fun source(relative: String): String = File(appDir, relative).readText()

    @Test
    fun `fast RAW shader does not redeclare function parameter y`() {
        val fast = source("src/main/cpp/vulkan/shaders/raw_preview_image.comp")
        val body = fast.substringAfter("float previewCalibratedExposedGtmLumaAt(int x, int y) {")
            .substringBefore("\n}")
        assertFalse(body.contains("float y ="))
        assertTrue(body.contains("float calibratedLuma ="))
    }

    @Test
    fun `retired spatial exposure code is absent from both active RAW preview shaders`() {
        val full = source("src/main/cpp/vulkan/shaders/raw_preview.comp")
        val fast = source("src/main/cpp/vulkan/shaders/raw_preview_image.comp")
        for (shader in listOf(full, fast)) {
            assertFalse(shader.contains("void spatialExposureEvidencePass("))
            assertFalse(shader.contains("void spatialExposureResolvePass("))
            assertFalse(shader.contains("float spatialExposureEvAtPreview("))
            assertFalse(shader.contains("void spatialEvidencePass("))
            assertFalse(shader.contains("void spatialResolvePass("))
            assertFalse(shader.contains("mode == 5u"))
            assertFalse(shader.contains("mode == 6u"))
            // Keep the reserved statistics range so all post-spatial telemetry indices remain ABI-stable.
            assertTrue(shader.contains("SPATIAL_EXPOSURE_SUMMARY_START_INDEX"))
            assertTrue(shader.contains("HIGHLIGHT_TELEMETRY_START_INDEX = SPATIAL_EXPOSURE_SUMMARY_START_INDEX"))
        }
    }

    @Test
    fun `full RAW shader stays below the pre overflow source complexity envelope`() {
        val full = source("src/main/cpp/vulkan/shaders/raw_preview.comp")
        // The failing NDK 28 shader was 1695 lines and overflowed shaderc's optimized SPIR-V ID space.
        // Retiring the unreachable mode-5/6 implementation should keep a substantial margin while -O stays enabled.
        assertTrue(full.lineSequence().count() < 1550)
        assertTrue(full.contains("else if (mode == 3u) buildPreviewFllfBase"))
        assertTrue(full.contains("else if (mode == 4u) physicalAwbPass"))
    }
}
