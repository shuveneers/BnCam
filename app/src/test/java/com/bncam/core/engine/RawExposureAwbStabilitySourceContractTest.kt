package com.bncam.core.engine

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RawExposureAwbStabilitySourceContractTest {
    private fun appRoot(): File = sequenceOf(File("."), File("app"))
        .firstOrNull { File(it, "src/main/java/com/bncam/core/engine/BnCameraManager.kt").isFile }
        ?: error("Cannot locate app module")

    private fun source(relative: String): String = File(appRoot(), relative).readText()

    @Test
    fun `raw preview and jpeg exposure ceilings expand only under low light evidence`() {
        val previewBuffer = source("src/main/cpp/vulkan/shaders/raw_preview.comp")
        val previewImage = source("src/main/cpp/vulkan/shaders/raw_preview_image.comp")
        val isp = source("src/main/cpp/IspCore.cpp")

        listOf(previewBuffer, previewImage).forEach { shader ->
            assertTrue(shader.contains("if (lowLight)"))
            assertTrue(shader.contains("deepShadowAuthority"))
            assertTrue(shader.contains("localizedHighlightPenalty"))
            assertTrue(shader.contains("float extendedMaxGain = raw10 ? 6.0 : 6.5"))
            assertTrue(shader.contains("float smoothedGain = mix(previousGain, rawTargetGain, 0.18)"))
        }
        assertTrue(isp.contains("if (isRawBayer && lowLightScene && lowRawClipping)"))
        assertTrue(isp.contains("const float extendedMaxGain = isRaw10 ? 6.0f : 6.5f"))
        assertTrue(isp.contains("broadHighlightPenalty"))
        // Compile-contract regression guards: Phase 13 added legacySharpenAmount before the
        // covariance/generation fields, so positional aggregate initialization is unsafe.
        assertTrue(isp.contains("IspCore::resolveSpectraIsoAdaptiveState"))
        assertTrue(isp.contains("request.legacySharpenAmount = 0.0f;"))
        assertTrue(isp.contains("request.generationId = spatialGeneration;"))
    }

    @Test
    fun `auto white balance remains rate limited without a user lock mechanism`() {
        val manager = source("src/main/java/com/bncam/core/engine/BnCameraManager.kt")
        val settings = source("src/main/java/com/bncam/data/settings/SettingsRepository.kt")
        val viewfinder = source("src/main/java/com/bncam/ui/screens/settings/ViewfinderScreen.kt")

        assertTrue(manager.contains("stabilizeRawPreviewAutoWb"))
        assertTrue(manager.contains("maxStepFraction"))
        assertTrue(manager.contains("CaptureRequest.CONTROL_AWB_LOCK, false"))
        assertFalse(manager.contains("whiteBalanceLockRequested"))
        assertFalse(manager.contains("fun setWhiteBalanceLock"))
        assertFalse(settings.contains("whiteBalanceLockFlow"))
        assertFalse(settings.contains("setWhiteBalanceLock"))
        assertFalse(viewfinder.contains("White balance lock"))
    }
}
