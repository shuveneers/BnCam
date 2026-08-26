package com.bncam.ui.screens.capture

import java.io.File
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ViewfinderWhiteBalanceRoutingContractTest {
    private fun source(path: String): String {
        val roots = listOf(File("."), File("app"))
        return roots.asSequence().map { File(it, path) }.firstOrNull { it.isFile }?.readText()
            ?: error("Missing source file: $path")
    }

    @Test
    fun `yuv white balance follows viewfinder source rather than warm buffer format`() {
        val manager = source("src/main/java/com/bncam/core/engine/BnCameraManager.kt")
        val section = manager.substringAfter("private fun updateLiveWhiteBalanceDisplayCompensation(")
            .substringBefore("private fun applyLiveWhiteBalancePolicy")
        assertTrue(section.contains("effectiveViewfinderSource == ViewfinderEffectiveSource.YUV"))
        assertTrue(section.contains("targetViewfinderSource == ViewfinderEffectiveSource.YUV"))
        assertFalse(section.contains("activePipelineIdentity.bufferFormat"))
    }


    @Test
    fun `yuv white balance uses physical capture metadata for a physical preview route`() {
        val manager = source("src/main/java/com/bncam/core/engine/BnCameraManager.kt")
        val section = manager.substringAfter("private fun updateLiveWhiteBalanceDisplayCompensation(")
            .substringBefore("private fun applyLiveWhiteBalancePolicy")
        assertTrue(section.contains("result: TotalCaptureResult"))
        assertTrue(section.contains("val calibrationResult = previewCaptureResult(result, identity.physicalCameraId)"))
        assertTrue(section.contains("val base = calibrationResult.get(CaptureResult.COLOR_CORRECTION_GAINS)"))
        assertFalse(section.contains("val base = result.get(CaptureResult.COLOR_CORRECTION_GAINS)"))
    }

    @Test
    fun `cached same generation hal gains let kelvin drag update yuv immediately`() {
        val manager = source("src/main/java/com/bncam/core/engine/BnCameraManager.kt")
        val commit = manager.substringAfter("private fun commitLiveWhiteBalanceTarget(")
            .substringBefore("fun setViewfinderWhiteBalance(")
        assertTrue(commit.contains("latestLiveWhiteBalanceBaseGeneration == pipelineGeneration"))
        assertTrue(commit.contains("publishLiveWhiteBalanceDisplayCompensation(baseGains, targetSensorGains)"))
        assertTrue(manager.contains("latestLiveWhiteBalanceBaseGeneration = sessionGeneration"))
        assertTrue(manager.contains("nowMs - lastLiveWhiteBalanceDisplayUpdateMs < 16L"))
    }
}
