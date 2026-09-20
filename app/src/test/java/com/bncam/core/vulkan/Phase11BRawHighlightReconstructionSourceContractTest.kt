package com.bncam.core.vulkan

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class Phase11BRawHighlightReconstructionSourceContractTest {
    private val appDir: File = sequenceOf(File("."), File("app"))
        .firstOrNull { File(it, "src/main/cpp/vulkan/VulkanRawPreviewBackend.cpp").isFile }
        ?: error("Cannot locate app module from ${File(".").absolutePath}")

    private fun source(relative: String): String = File(appDir, relative).readText()

    @Test
    fun `both live RAW kernels reconstruct clipped CFA samples before demosaic`() {
        listOf(
            "src/main/cpp/vulkan/shaders/raw_preview.comp",
            "src/main/cpp/vulkan/shaders/raw_preview_image.comp"
        ).forEach { relative ->
            val shader = source(relative)
            val reconstruct = shader.indexOf("float reconstructRawHighlightDirect(")
            val demosaic = minOf(
                shader.indexOf("vec3 malvarAt(").takeIf { it >= 0 } ?: Int.MAX_VALUE,
                shader.indexOf("vec3 demosaicAt(").takeIf { it >= 0 } ?: Int.MAX_VALUE
            )
            assertTrue("reconstruction must exist in $relative", reconstruct >= 0)
            assertTrue("reconstruction must precede demosaic in $relative", reconstruct < demosaic)
            assertTrue(shader.contains("RAW_HIGHLIGHT_CLIP_THRESHOLD = 0.995"))
            assertTrue(shader.contains("addHighlightSupport"))
            assertTrue(shader.contains("unclippedGreenCross"))
            assertTrue(shader.contains("ratioSum / ratioWeight"))
            assertTrue(shader.contains("RAW_HIGHLIGHT_RECONSTRUCT_CAP = 1.35"))
        }
    }

    @Test
    fun `highlight recovery is evidence based and contains no magenta suppression hack`() {
        val shaders = listOf(
            source("src/main/cpp/vulkan/shaders/raw_preview.comp"),
            source("src/main/cpp/vulkan/shaders/raw_preview_image.comp")
        ).joinToString("\n")

        assertTrue(shaders.contains("if (valueWeight < 1.50) return center"))
        assertTrue(shaders.contains("if (color != 1u && ratioWeight >= 1.50)"))
        assertTrue(shaders.contains("reconstructed = estimate > center + 0.002"))
        assertTrue(shaders.contains("commonScaleReconstructedHighlight"))
        assertTrue(shaders.contains("return sensorRgb / peak"))
        assertFalse(shaders.contains("magenta suppress"))
        assertFalse(shaders.contains("MAGENTA_SUPPRESS"))
        assertFalse(shaders.contains("highlightDesaturate"))
    }


    @Test
    fun `clean RAW sites keep the realtime one-fetch fast path and common scaling stays before WB CCM`() {
        listOf(
            "src/main/cpp/vulkan/shaders/raw_preview.comp" to "float rawAtSource(",
            "src/main/cpp/vulkan/shaders/raw_preview_image.comp" to "float rawNormalizedLocal("
        ).forEach { (relative, rawFunction) ->
            val shader = source(relative)
            assertTrue(shader.contains("if (!sensorClipped) return center"))
            val rawStart = shader.indexOf(rawFunction)
            val rawEnd = shader.indexOf("\n}", rawStart)
            val rawBody = shader.substring(rawStart, rawEnd)
            assertTrue(rawBody.contains("reconstructRawHighlightDirect(lx, ly"))
            assertFalse("clean RAW accessor must not reconstruct all four Bayer-cell sites", rawBody.contains("cellPeak"))

            val commonScale = shader.indexOf("commonScaleReconstructedHighlight(", shader.indexOf("void renderPass()"))
            val colorTransform = shader.indexOf("colorTransform(", commonScale)
            assertTrue("common RGB scale must be in the sensor-linear path before WB/CCM", commonScale >= 0 && commonScale < colorTransform)
        }
    }

    @Test
    fun `Phase 11B telemetry distinguishes sensor WB and CCM clipping`() {
        val backend = source("src/main/cpp/vulkan/VulkanRawPreviewBackend.cpp")
        val header = source("src/main/cpp/vulkan/VulkanRawPreviewBackend.h")
        val native = source("src/main/cpp/native-lib.cpp")
        val renderer = source("src/main/java/com/bncam/ui/screens/capture/RawPreviewRenderer.kt")

        assertTrue(backend.contains("PREVIEW_HIGHLIGHT_TELEMETRY_START_WORD == 31499u"))
        assertTrue(backend.contains("PREVIEW_ANALYSIS_NV21_START_WORD == 31512u"))
        listOf(
            "rawRClipSampleCount",
            "rawGClipSampleCount",
            "rawBClipSampleCount",
            "highlightReconstructedSampleCount",
            "postWbClipSampleCount",
            "postCcmClipSampleCount"
        ).forEach { field ->
            assertTrue("backend header missing $field", header.contains(field))
            assertTrue("native result missing $field", native.contains(field))
        }
        assertTrue(renderer.contains("HIGHLIGHT_RECON_DIAGNOSTICS_START_INDEX = 626"))
        assertTrue(renderer.contains("highlightReconstructionActivated"))
        assertTrue(renderer.contains("rawClipFraction="))
        assertTrue(renderer.contains("rawClipRgb="))
        assertTrue(renderer.contains("postWbClipSamples="))
        assertTrue(renderer.contains("postCcmClipSamples="))
    }

    @Test
    fun `RAW10 and RAW SENSOR share the same reconstruction authority`() {
        val full = source("src/main/cpp/vulkan/shaders/raw_preview.comp")
        val fast = source("src/main/cpp/vulkan/shaders/raw_preview_image.comp")
        listOf(full, fast).forEach { shader ->
            assertTrue(shader.contains("const uint RAW_SENSOR_FORMAT = 32u"))
            assertTrue(shader.contains("const uint RAW10_FORMAT = 37u"))
            assertTrue(shader.contains("if (sourceFormat() == RAW10_FORMAT)"))
            assertTrue(shader.contains("reconstructRawHighlightDirect"))
        }
    }
}
