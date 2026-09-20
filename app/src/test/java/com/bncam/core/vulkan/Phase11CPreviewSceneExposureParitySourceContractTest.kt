package com.bncam.core.vulkan

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class Phase11CPreviewSceneExposureParitySourceContractTest {
    private val appDir: File = sequenceOf(File("."), File("app"))
        .firstOrNull { File(it, "src/main/cpp/vulkan/VulkanRawPreviewBackend.cpp").isFile }
        ?: error("Cannot locate app module from ${File(".").absolutePath}")

    private fun source(relative: String): String = File(appDir, relative).readText()

    @Test
    fun `full and fast RAW preview share one scene exposure policy source`() {
        val shared = source("src/main/cpp/vulkan/shaders/preview_scene_exposure.glsl")
        val full = source("src/main/cpp/vulkan/shaders/raw_preview.comp")
        val fast = source("src/main/cpp/vulkan/shaders/raw_preview_image.comp")
        val cmake = source("src/main/cpp/CMakeLists.txt")

        assertTrue(shared.contains("struct PreviewSceneExposurePlan"))
        assertTrue(shared.contains("resolvePreviewSceneExposurePolicy("))
        assertTrue(shared.contains("requestedEv"))
        assertTrue(shared.contains("highlightLimitedEv"))
        assertTrue(shared.contains("appliedEv"))
        listOf(full, fast).forEach { shader ->
            assertTrue(shader.contains("#include \"preview_scene_exposure.glsl\""))
            assertTrue(shader.contains("resolvePreviewSceneExposurePolicy("))
            assertTrue(shader.contains("histogramPercentile(total, 0.35)") ||
                shader.contains("percentileFromPreviewHistogram(total, 0.35)"))
            assertTrue(shader.contains("0.50"))
            assertTrue(shader.contains("0.75"))
            assertTrue(shader.contains("0.90"))
            assertTrue(shader.contains("0.95"))
            assertTrue(shader.contains("0.99"))
        }
        assertTrue(cmake.contains("-I \"${'$'}{CMAKE_CURRENT_SOURCE_DIR}/vulkan/shaders\""))
        assertTrue(cmake.contains("vulkan/shaders/preview_scene_exposure.glsl"))
    }

    @Test
    fun `fast image and full preview meter in the same calibrated luminance domain`() {
        val full = source("src/main/cpp/vulkan/shaders/raw_preview.comp")
        val fast = source("src/main/cpp/vulkan/shaders/raw_preview_image.comp")

        assertTrue(full.contains("vec3 calibratedRgb = colorTransform(sensorRgb);"))
        assertTrue(full.contains("dot(calibratedRgb, vec3(0.2126, 0.7152, 0.0722))"))
        assertTrue(fast.contains("vec3 meteredCalibratedRgb = colorTransform(meteredSensorRgb);"))
        assertTrue(fast.contains("dot(meteredCalibratedRgb, vec3(0.2126, 0.7152, 0.0722))"))
        listOf(full, fast).forEach { shader ->
            assertTrue(shader.contains("uint(abs(pc.histogramStride))"))
        }
        // Retired split targets must not survive in the active exposure passes.
        val fullExposure = full.substringAfter("void exposurePass()").substringBefore("float profileContrast(")
        val fastExposure = fast.substringAfter("void exposurePass()").substringBefore("void physicalAwbPass()")
        assertFalse(fullExposure.contains("targetMidtone = lowLight ? 0.112 : 0.125"))
        assertFalse(fastExposure.contains("target = lowLight ? 0.095 : 0.115"))
    }

    @Test
    fun `preview exposure is display only and temporally smoothed in EV space`() {
        val shared = source("src/main/cpp/vulkan/shaders/preview_scene_exposure.glsl")
        val manager = source("src/main/java/com/bncam/core/engine/BnCameraManager.kt")

        assertTrue(shared.contains("previewSceneSafeLog2(sceneKey / midtone)"))
        assertTrue(shared.contains("float highlightHeadroomEv"))
        assertTrue(shared.contains("float clipLimitedEv"))
        assertTrue(shared.contains("float noiseLimitedEv"))
        assertTrue(shared.contains("float alpha = deltaEv > 0.0 ? 0.22 : 0.45"))
        assertTrue(shared.contains("never changes Camera2 sensor exposure"))
        assertTrue(manager.contains("val previewExposureGain = 1.0f"))
        assertTrue(manager.contains("PreviewSceneExposurePolicy"))
        assertTrue(manager.contains("Camera2 sensor exposure is unchanged here"))
    }

    @Test
    fun `Phase 11C telemetry has explicit requested limited and applied EV without breaking older indices`() {
        val backend = source("src/main/cpp/vulkan/VulkanRawPreviewBackend.cpp")
        val header = source("src/main/cpp/vulkan/VulkanRawPreviewBackend.h")
        val native = source("src/main/cpp/native-lib.cpp")
        val renderer = source("src/main/java/com/bncam/ui/screens/capture/RawPreviewRenderer.kt")

        assertTrue(backend.contains("PREVIEW_HIGHLIGHT_TELEMETRY_START_WORD == 31499u"))
        assertTrue(backend.contains("PREVIEW_SCENE_EXPOSURE_START_WORD == 31505u"))
        assertTrue(backend.contains("PREVIEW_ANALYSIS_NV21_START_WORD == 31512u"))
        listOf(
            "previewRequestedEv",
            "previewHighlightLimitedEv",
            "previewAppliedEv",
            "previewSceneKey",
            "previewHighlightHeadroomEv",
            "previewSceneRangeEv"
        ).forEach { field ->
            assertTrue(header.contains(field))
            assertTrue(native.contains(field))
            assertTrue(renderer.contains(field))
        }
        assertTrue(renderer.contains("ASYNC_ANALYSIS_READBACK_INDEX = 625"))
        assertTrue(renderer.contains("HIGHLIGHT_RECON_DIAGNOSTICS_START_INDEX = 626"))
        assertTrue(renderer.contains("PREVIEW_SCENE_EXPOSURE_DIAGNOSTICS_START_INDEX = 632"))
    }

    @Test
    fun `temporal exposure state is GPU resident and independent of the analysis sidecar`() {
        val backend = source("src/main/cpp/vulkan/VulkanRawPreviewBackend.cpp")
        val header = source("src/main/cpp/vulkan/VulkanRawPreviewBackend.h")
        val full = source("src/main/cpp/vulkan/shaders/raw_preview.comp")
        val fast = source("src/main/cpp/vulkan/shaders/raw_preview_image.comp")

        assertTrue(header.contains("PersistentBuffer previewExposureState_;"))
        assertFalse(header.contains("previousExposureGain_"))
        assertTrue(backend.contains("VkBufferMemoryBarrier exposureStateBarrier"))
        assertTrue(backend.contains("exposureStateBarrier.srcAccessMask = VK_ACCESS_SHADER_WRITE_BIT"))
        assertTrue(backend.contains("VK_ACCESS_SHADER_READ_BIT | VK_ACCESS_SHADER_WRITE_BIT"))
        assertFalse(backend.contains("previousExposureGain_"))
        listOf(full, fast).forEach { shader ->
            assertTrue(shader.contains("binding = 6) buffer PreviewExposureState"))
            assertTrue(shader.contains("previousGain = uintBitsToFloat(previewExposureState[0])"))
            assertTrue(shader.contains("previewExposureState[0] = floatBitsToUint(exp2(exposurePlan.appliedEv))"))
        }
    }

    @Test
    fun `Phase 11A non blocking completion remains intact`() {
        val backend = source("src/main/cpp/vulkan/VulkanRawPreviewBackend.cpp")
        val execute = backend.substringAfter("RawPreviewGpuResult VulkanRawPreviewBackend::execute(")
            .substringBefore("RawPreviewGpuResult VulkanRawPreviewBackend::pollCompletion(")
        val poll = backend.substringAfter("RawPreviewGpuResult VulkanRawPreviewBackend::pollCompletion(")
            .substringBefore("bool VulkanRawPreviewBackend::waitForIdle(")
        assertFalse(execute.contains("vkWaitForFences(device"))
        assertTrue(execute.contains("vkQueueSubmit"))
        assertTrue(poll.contains("vkGetFenceStatus"))
        assertFalse(poll.contains("vkWaitForFences(device"))
    }
}
