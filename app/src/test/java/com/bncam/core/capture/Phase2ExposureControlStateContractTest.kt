package com.bncam.core.capture

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class Phase2ExposureControlStateContractTest {
    private val appDir: File = sequenceOf(File("."), File("app"))
        .firstOrNull { File(it, "src/main/cpp/CMakeLists.txt").isFile }
        ?: error("Cannot locate app module from ${File(".").absolutePath}")

    private fun source(relative: String): String = File(appDir, relative).readText()

    @Test
    fun `live exposure statistics are histogram diagnostics and do not drive Camera2 AE`() {
        val manager = source("src/main/java/com/bncam/core/engine/BnCameraManager.kt")
        val publish = manager.substringAfter("private fun publishLiveExposureStatistics")
            .substringBefore("private data class PreviewNv21")
        val exposureStats = source("src/main/java/com/bncam/core/capture/ExposureStatistics.kt")

        assertTrue(publish.contains("Standard AE metering is fully Camera2-region driven"))
        assertFalse(publish.contains("CONTROL_AE_EXPOSURE_COMPENSATION"))
        assertFalse(publish.contains("CameraAutoExposurePolicy"))
        assertFalse(exposureStats.contains("object CameraAutoExposurePolicy"))
    }

    @Test
    fun `Camera2 EV compensation rounds symmetrically and only applies explicit user EV`() {
        val manager = source("src/main/java/com/bncam/core/engine/BnCameraManager.kt")
        val metering = manager.substringAfter("fun applyMeteringPolicy")
            .substringBefore("private fun Rect.clampedInside")

        assertTrue(metering.contains("stepEv.isFinite() && stepEv > 0f"))
        assertTrue(metering.contains("(currentEvOffset / stepEv).roundToInt()"))
        assertTrue(metering.contains("hiddenAutoEv=disabled"))
        assertFalse(metering.contains("liveMeteringAutoEv"))
    }

    @Test
    fun `YUV Vulkan exposure statistics only run for explicitly requested analysis`() {
        val manager = source("src/main/java/com/bncam/core/engine/BnCameraManager.kt")
        val needsStats = manager.substringAfter("private fun needsLiveExposureStatistics")
            .substringBefore("private fun publishLiveExposureStatistics")

        assertTrue(needsStats.contains("histogramAnalysisEnabled"))
        assertFalse(needsStats.contains("MeteringMode"))
    }

    @Test
    fun `metering updates remain repeating request controls and do not rebuild camera session`() {
        val manager = source("src/main/java/com/bncam/core/engine/BnCameraManager.kt")
        val setMetering = manager.substringAfter("fun setMeteringStyle")
            .substringBefore("private fun clearTouchAeOverride")

        assertTrue(setMetering.contains("updatePreviewRepeatingRequest"))
        assertFalse(setMetering.contains("createCaptureSession"))
        assertFalse(setMetering.contains("startCameraAndZsl"))
        assertFalse(setMetering.contains("resetPipeline"))
    }
}
