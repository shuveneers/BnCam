package com.bncam.core.capture

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WhiteLevelExposureAuthoritySourceContractTest {
    private val appDir: File = sequenceOf(File("."), File("app"))
        .firstOrNull { File(it, "src/main/cpp/CMakeLists.txt").isFile }
        ?: error("Cannot locate app module from ${File(".").absolutePath}")

    @Test
    fun warmBufferPreservesCamera2WhiteAuthorityWithoutInventingAFormatWhite() {
        val pairing = source("src/main/java/com/bncam/core/capture/WarmBufferPairingCoordinator.kt")
        val record = source("src/main/java/com/bncam/core/quality/CaptureFrameRecord.kt")

        assertTrue(pairing.contains("CaptureResult.SENSOR_DYNAMIC_WHITE_LEVEL"))
        assertTrue(pairing.contains("CameraCharacteristics.SENSOR_INFO_WHITE_LEVEL"))
        assertTrue(pairing.contains("else -> \"unavailable\""))
        assertFalse(pairing.contains("SENSOR_INFO_WHITE_LEVEL) ?: 1023"))
        assertTrue(record.contains("val frameWhiteLevel: Int?"))
    }

    @Test
    fun rawSensorBootstrapNeverTreatsTheSixteenBitContainerAsSensorWhite() {
        val manager = source("src/main/java/com/bncam/core/engine/BnCameraManager.kt")
        val bootstrap = manager
            .substringAfter("private fun buildBootstrapRawPreviewConfig(")
            .substringBefore("private fun previewCaptureResult(")

        assertTrue(bootstrap.contains("CaptureResult.SENSOR_DYNAMIC_WHITE_LEVEL"))
        assertTrue(bootstrap.contains("CameraCharacteristics.SENSOR_INFO_WHITE_LEVEL"))
        assertTrue(bootstrap.contains("source != ViewfinderEffectiveSource.RAW10"))
        assertTrue(bootstrap.contains("RAW_PREVIEW_BOOTSTRAP_WHITE_UNAVAILABLE"))
        assertFalse(bootstrap.contains("else 65535"))
    }

    @Test
    fun rawNearClipMeteringIsMeasuredAfterDevelopedBlackWhiteNormalization() {
        val shader = source("src/main/cpp/vulkan/shaders/raw_preview.comp")
        val manager = source("src/main/java/com/bncam/core/engine/BnCameraManager.kt")

        assertTrue(shader.contains("pc.whiteLevel - black"))
        assertTrue(shader.contains("if (raw >= 0.995) atomicAdd(statsBuffer[RAW_NEAR_CLIP_COUNT_INDEX], 1u)"))
        assertTrue(manager.contains("frame.rawNearClipSampleCount"))
        assertTrue(manager.contains("rawNearClipFraction = statistics.rawNearClipFraction ?: statistics.maximumDisplayClipFraction"))
        assertTrue(manager.contains("RAW_PREVIEW_WHITE_AUTHORITY"))
    }

    private fun source(relative: String): String = File(appDir, relative).readText()
}
