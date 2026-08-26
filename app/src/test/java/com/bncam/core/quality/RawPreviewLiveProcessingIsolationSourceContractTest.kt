package com.bncam.core.quality

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class RawPreviewLiveProcessingIsolationSourceContractTest {
    private val appDir: File = sequenceOf(File("."), File("app"))
        .firstOrNull { File(it, "src/main/java/com/bncam/core/engine/BnCameraManager.kt").isFile }
        ?: error("Cannot locate app module")

    @Test
    fun `raw viewfinder skips capture sharpening and noise reduction`() {
        val manager = File(appDir, "src/main/java/com/bncam/core/engine/BnCameraManager.kt").readText()
        val configBlock = manager
            .substringAfter("val config = RawPreviewRenderConfig(")
            .substringBefore("rotationDegrees = rawPreviewRotationDegrees")

        assertTrue(configBlock.contains("profileDetailAmount = 0f"))
        assertTrue(configBlock.contains("profileNrLuminance = 0f"))
        assertTrue(configBlock.contains("profileNrColor = 0f"))
        assertTrue(configBlock.contains("profileToneExposure = quality.profileToneTuning.exposure"))
        assertTrue(configBlock.contains("profileSaturation = quality.profileColorTuning.saturation"))
    }
}
