package com.bncam.core.quality

import java.io.File
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RawPreviewPerceptualQualitySourceContractTest {
    private fun source(path: String): String {
        val roots = listOf(File("."), File("app"))
        return roots.asSequence().map { File(it, path) }.firstOrNull { it.isFile }?.readText()
            ?: error("Missing source file: $path")
    }

    @Test
    fun rawPreviewUsesTruthfulLuminanceAndEdgeAwareDetailWithoutExtraFullFramePass() {
        val shaders = listOf(
            source("src/main/cpp/vulkan/shaders/raw_preview.comp"),
            source("src/main/cpp/vulkan/shaders/raw_preview_image.comp")
        )
        shaders.forEach { shader ->
            assertTrue(shader.contains("float directionalGreen ="))
            assertTrue(shader.contains("float zipperAuthority = mix(0.58, 0.90"))
            assertTrue(shader.contains("previewGreenStructureAt(int(sampleCoordinate.x), int(sampleCoordinate.y))"))
            assertTrue(shader.contains("float sensorLuma = previewGreenStructureAt(x, y)"))
            assertTrue(shader.contains("vec3 calibratedLumaRgb = colorTransform(vec3(sensorLuma))"))
            assertTrue(shader.contains("float calibratedLuma = max(1.0e-4, dot(calibratedLumaRgb"))
            assertTrue(shader.contains("float centreAxisWeight = mix(6.5, 1.6, noiseBlend)"))
            assertTrue(shader.contains("float centreRecovery = 0.24 * structure * cleanAuthority"))
            assertTrue(shader.contains("float authority = 0.27 * cleanSignal"))
            assertFalse(shader.contains("vec3 rgb = colorTransform(vec3(raw));\n    float luma = clamp(dot(rgb"))
            assertFalse(shader.contains("previewQualityFullFramePass"))
        }
    }
}
