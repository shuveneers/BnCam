package com.bncam.core.quality

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class Phase11GCaptureViewfinderParitySourceContractTest {
    private val appDir: File = sequenceOf(File("."), File("app"))
        .firstOrNull { File(it, "src/main/cpp/IspCore.cpp").isFile }
        ?: error("Cannot locate app module from ${File(".").absolutePath}")

    private fun source(relative: String): String = File(appDir, relative).readText()

    @Test
    fun `capture full preview and fast preview share the same automatic tone authority order`() {
        val capture = source("src/main/cpp/vulkan/shaders/spectra_tone_resident.comp")
        val full = source("src/main/cpp/vulkan/shaders/raw_preview.comp")
        val fast = source("src/main/cpp/vulkan/shaders/raw_preview_image.comp")

        val captureRaw = capture.substringAfter("// RAW production order:")
            .substringBefore("} else {")
        assertOrdered(captureRaw,
            "applyRawGlobalSceneExposureAndGtm(rgb)",
            "applyFllfLocalExposure(gid, rgb)",
            "pbrNeutralToneMapping(rgb)",
            "pc.presenceReserved1",
            "applyToneLookLut(rgb)",
            "applyExplicitProfileToneContrast(rgb)",
            "applyProfileColor(rgb")

        val fullRender = full.substringAfter("void renderPass() {").substringBefore("void main()")
        assertOrdered(fullRender,
            "previewAutomaticExposureMultiplier()",
            "applyPreviewGtm(rgb)",
            "applyPreviewFllf(gid, rgb)",
            "pbrNeutralToneMapping(rgb)",
            "previewProfileExposureMultiplier()",
            "applyPreviewToneLook(rgb)",
            "applyExplicitLiveToneContrast(rgb)",
            "applyProfile(rgb")

        val fastRender = fast.substringAfter("void renderPass() {").substringBefore("void main()")
        assertOrdered(fastRender,
            "autoExposureMultiplier()",
            "applyPreviewGtm(rgb)",
            "applyPreviewFllf(gid, rgb)",
            "khronosNeutral(rgb)",
            "profileExposureMultiplier()",
            "applyProfileToneLook(rgb)",
            "applyProfileColorPreview(rgb")
    }

    @Test
    fun `preview scene exposure and GTM numerically mirror capture policies`() {
        val preview = source("src/main/cpp/vulkan/shaders/preview_scene_exposure.glsl")
        val captureExposure = source("src/main/cpp/GlobalSceneExposurePolicy.h")
        val captureGtm = source("src/main/cpp/GlobalToneMappingPolicy.h")

        assertTrue(preview.contains("0.148 + 0.018 * shadowPressure + 0.006 * dynamicRangePressure"))
        assertTrue(captureExposure.contains("0.148f + 0.018f * shadowPressure + 0.006f * dynamicRangePressure"))
        assertTrue(preview.contains("const float maxPositiveEv = 1.25"))
        assertTrue(captureExposure.contains("constexpr float kMaxPositiveEv = 1.25f"))
        assertTrue(preview.contains("const float maxNegativeEv = -0.50"))
        assertTrue(captureExposure.contains("constexpr float kMaxNegativeEv = -0.50f"))
        assertTrue(preview.contains("previewSceneSafeLog2(1.20 / robustHigh)"))
        assertTrue(captureExposure.contains("globalSceneSafeLog2(1.20f / robustHigh)"))

        assertTrue(preview.contains("0.15 * previewSceneSmoothstep(0.48, 0.82, p90)"))
        assertTrue(captureGtm.contains("0.15f * gtmSmoothstep(0.48f, 0.82f, p90)"))
        assertTrue(preview.contains("0.86 - 0.16 * highlightPressure - 0.025 * rangePressure"))
        assertTrue(captureGtm.contains("0.86f - 0.16f * out.highlightPressure - 0.025f * rangePressure"))
        assertTrue(preview.contains("0.55 + 0.30 * highlightPressure + 0.08 * rangePressure"))
        assertTrue(captureGtm.contains("0.55f + 0.30f * out.highlightPressure + 0.08f * rangePressure"))
    }

    @Test
    fun `preview no longer has duplicate global or spatial brightness owners`() {
        val backend = source("src/main/cpp/vulkan/VulkanRawPreviewBackend.cpp")
        val full = source("src/main/cpp/vulkan/shaders/raw_preview.comp")
        val fast = source("src/main/cpp/vulkan/shaders/raw_preview_image.comp")
        val isp = source("src/main/cpp/IspCore.cpp")

        assertTrue(isp.contains("request.adaptiveExposureEnabled = false"))
        assertFalse(backend.contains("push.cfaAndMode = packedMode(5u)"))
        assertFalse(backend.contains("push.cfaAndMode = packedMode(6u)"))
        assertTrue(backend.contains("push.cfaAndMode = packedMode(3u)"))
        assertFalse(full.contains("applyPreviewBroadShadowPlacement("))
        assertFalse(fast.contains("logSceneShape("))
        assertFalse(full.contains("sensorRgb *= exp2(spatialExposureEvAtPreview(gid));"))
        listOf(full, fast).forEach { shader ->
            assertFalse(shader.contains("mode == 5u"))
            assertFalse(shader.contains("mode == 6u"))
        }
    }

    @Test
    fun `full and fast preview retain capture style camera characterization and display shoulder`() {
        val capture = source("src/main/cpp/vulkan/shaders/spectra_tone_resident.comp")
        val full = source("src/main/cpp/vulkan/shaders/raw_preview.comp")
        val fast = source("src/main/cpp/vulkan/shaders/raw_preview_image.comp")

        for (shader in listOf(full, fast)) {
            assertTrue(shader.contains("applyCalibratedDngHueSatMap"))
            assertTrue(shader.contains("protectPreviewSignedLowerGamut"))
            assertTrue(shader.contains("const float startCompression = 0.88"))
            assertTrue(shader.contains("const float shoulderPrepStart = 0.72"))
        }
        assertTrue(capture.contains("const float startCompression = 0.88"))
        assertTrue(capture.contains("const float shoulderPrepStart = 0.72"))
        // Explicit profile contrast response and lightweight Pop response use the capture coefficients.
        assertTrue(full.contains("exp2(clamp(control, -1.0, 1.0) * 0.75)"))
        assertTrue(fast.contains("exp2(clamp(pc.profileContrast, -1.0, 1.0) * 0.75)"))
        for (shader in listOf(full, fast)) {
            assertTrue(shader.contains("float popEv = clamp(evDetail * 0.50 * authority, -0.18, 0.18)"))
            assertTrue(shader.contains("0.055 * control * structure * toneWindow * chromaEvidence"))
        }
    }

    @Test
    fun `preview uses developed black white domain and atomic exact frame WB CCM pair`() {
        val manager = source("src/main/java/com/bncam/core/engine/BnCameraManager.kt")
        val renderer = source("src/main/java/com/bncam/ui/screens/capture/RawPreviewRenderer.kt")

        assertTrue(manager.contains("RawPreviewCalibrationTransform.developedLevelsInSourceDomain(previewRawContract)"))
        assertTrue(manager.contains("exactPreviewColorPair"))
        assertTrue(manager.contains("COLOR_CORRECTION_GAINS"))
        assertTrue(manager.contains("COLOR_CORRECTION_TRANSFORM"))
        assertTrue(manager.contains("Exact Camera2 gains and transform are an atomic pair"))
        assertTrue(renderer.contains("updateExactFrameCamera2ColorPair"))
        assertTrue(renderer.contains("hasExactFrameColorPair"))
        assertTrue(renderer.contains("camera2PriorWbGains"))
    }

    @Test
    fun `preview logs display parity observables directly comparable with capture`() {
        val renderer = source("src/main/java/com/bncam/ui/screens/capture/RawPreviewRenderer.kt")
        val isp = source("src/main/cpp/IspCore.cpp")

        listOf("displayP50=", "displayP95=", "displayClipping=").forEach { token ->
            assertTrue(renderer.contains(token))
            assertTrue(isp.contains(token))
        }
        assertTrue(renderer.contains("gtmHighlightPressure="))
        assertTrue(isp.contains("gtmHighlightPressure="))
        assertTrue(renderer.contains("legacySpatialExposure=RETIRED_PHASE11G"))
    }

    @Test
    fun `phase11A asynchronous presentation remains non blocking`() {
        val backend = source("src/main/cpp/vulkan/VulkanRawPreviewBackend.cpp")
        val execute = backend.substringAfter("RawPreviewGpuResult VulkanRawPreviewBackend::execute(")
            .substringBefore("RawPreviewGpuResult VulkanRawPreviewBackend::pollCompletion(")
        val poll = backend.substringAfter("RawPreviewGpuResult VulkanRawPreviewBackend::pollCompletion(")
            .substringBefore("bool VulkanRawPreviewBackend::waitForIdle(")
        assertTrue(execute.contains("vkQueueSubmit"))
        assertFalse(execute.contains("vkWaitForFences(device"))
        assertTrue(poll.contains("vkGetFenceStatus"))
        assertFalse(poll.contains("vkWaitForFences(device"))
    }

    private fun assertOrdered(text: String, vararg tokens: String) {
        var previous = -1
        tokens.forEach { token ->
            val index = text.indexOf(token)
            assertTrue("Missing $token", index >= 0)
            assertTrue("$token is out of order", index > previous)
            previous = index
        }
    }
}
