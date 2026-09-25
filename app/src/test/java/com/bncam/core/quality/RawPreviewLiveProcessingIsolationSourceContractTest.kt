package com.bncam.core.quality

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RawPreviewLiveProcessingIsolationSourceContractTest {
    private val appDir: File = sequenceOf(File("."), File("app"))
        .firstOrNull { File(it, "src/main/java/com/bncam/core/engine/BnCameraManager.kt").isFile }
        ?: error("Cannot locate app module")

    @Test
    fun `raw viewfinder has no capture or profile noise reduction transport`() {
        val manager = File(appDir, "src/main/java/com/bncam/core/engine/BnCameraManager.kt").readText()
        val renderer = File(appDir, "src/main/java/com/bncam/ui/screens/capture/RawPreviewRenderer.kt").readText()
        val imageUtils = File(appDir, "src/main/java/com/bncam/core/engine/ImageUtils.kt").readText()

        val configBlock = manager
            .substringAfter("val config = RawPreviewRenderConfig(")
            .substringBefore("rotationDegrees = rawPreviewRotationDegrees")

        assertTrue(configBlock.contains("profileDetailAmount = 0f"))
        assertTrue(configBlock.contains("profileToneExposure = quality.profileToneTuning.exposure"))
        assertTrue(configBlock.contains("profileSaturation = quality.profileColorTuning.saturation"))
        assertFalse(configBlock.contains("profileNr"))
        assertFalse(renderer.contains("profileNrLuminance"))
        assertFalse(renderer.contains("profileNrColor"))
        val rawFunction = imageUtils
            .substringAfter("fun renderRawPreview(")
            .substringBefore("fun processYuv")
        assertFalse(rawFunction.contains("profileNr"))
    }
}
