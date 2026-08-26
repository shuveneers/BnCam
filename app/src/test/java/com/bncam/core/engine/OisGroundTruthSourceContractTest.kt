package com.bncam.core.engine

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OisGroundTruthSourceContractTest {
    private val appDir: File = sequenceOf(File("."), File("app"))
        .firstOrNull { File(it, "src/main/java/com/bncam/core/engine/BnCameraManager.kt").isFile }
        ?: error("Cannot locate app module from ${File(".").absolutePath}")

    @Test
    fun viewfinderRequestsStandardOisTelemetryWhenSupported() {
        val manager = source("src/main/java/com/bncam/core/engine/BnCameraManager.kt")
        assertTrue(manager.contains("STATISTICS_INFO_AVAILABLE_OIS_DATA_MODES"))
        assertTrue(manager.contains("STATISTICS_OIS_DATA_MODE_ON"))
        assertTrue(manager.contains("OIS_DATA_TELEMETRY"))
        assertFalse(manager.contains("EISThresholdFix"))
    }

    @Test
    fun frameAnalysisReadsLogicalAndPhysicalOisTruth() {
        val runner = source("src/main/java/com/bncam/core/runners/SingleFrameRunner.kt")
        assertTrue(runner.contains("physicalCameraResults[physicalCameraId]"))
        assertTrue(runner.contains("STATISTICS_OIS_SAMPLES"))
        assertTrue(runner.contains("oisShiftRmsPx"))
        assertTrue(runner.contains("oisShiftPeakPx"))
    }

    private fun source(relative: String): String = File(appDir, relative).readText()
}
