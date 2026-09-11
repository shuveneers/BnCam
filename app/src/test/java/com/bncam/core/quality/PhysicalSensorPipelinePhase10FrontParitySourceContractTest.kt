package com.bncam.core.quality

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PhysicalSensorPipelinePhase10FrontParitySourceContractTest {
    private fun appDir(): File = sequenceOf(File("."), File("app"))
        .firstOrNull {
            File(it, "src/main/java/com/bncam/core/quality/SensorAuthority.kt").isFile
        }
        ?: error("Unable to locate app module")

    private fun source(path: String): String = File(appDir(), path).readText()

    @Test
    fun `standalone cameras use the same sensor authority contract as physical children`() {
        val authority = source("src/main/java/com/bncam/core/quality/SensorAuthority.kt")
        val registry = source("src/main/java/com/bncam/core/quality/PhysicalSensorProfileRegistry.kt")

        assertTrue("SensorAuthorityType.STANDALONE" in authority)
        assertTrue("sensorAuthorityId == cameraDeviceId" in authority)
        assertTrue("physicalCameraId == null" in authority)
        assertTrue("authorityType = SensorAuthorityType.STANDALONE" in registry)
        assertTrue("LOGICAL_PARENT_WITHOUT_PHYSICAL_AUTHORITY" in registry)
        assertTrue("FRAME_SNAPSHOT_STANDALONE_RESULT" in registry)
        assertFalse("logical metadata fallback" in registry.lowercase())
    }

    @Test
    fun `front identity never selects a separate raw isp tuning path`() {
        val render = source("src/main/java/com/bncam/core/quality/RenderQualityConfig.kt")
        val calibration = source("src/main/java/com/bncam/core/quality/SensorCalibration.kt")
        val single = source("src/main/java/com/bncam/core/runners/SingleFrameRunner.kt")
        val multi = source("src/main/java/com/bncam/core/runners/MultiFrameRunner.kt")

        listOf(render, calibration).forEach { processingSource ->
            assertFalse("LENS_FACING_FRONT" in processingSource)
            assertFalse("isFrontCamera" in processingSource)
            assertFalse("frontFacing" in processingSource)
            assertFalse("lens == MAIN" in processingSource)
            assertFalse("lens == TELE" in processingSource)
            assertFalse("lens == FRONT" in processingSource)
        }

        assertTrue("PhysicalSensorProfileRegistry.resolveCurrentCalibrationInput" in single)
        assertTrue("PhysicalSensorProfileRegistry.resolveCurrentCalibrationInput" in multi)
        assertTrue("RenderQualityConfig.load(" in single)
        assertTrue("RenderQualityConfig.load(" in multi)
        listOf(single, multi).forEach { runner ->
            assertTrue("RAW JPEG requires valid per-frame or calibrated white-balance metadata." in runner)
            assertTrue("RAW JPEG requires a validated Camera2 color transform." in runner)
        }
    }

    @Test
    fun `capture diagnostics prove standalone front provenance explicitly`() {
        val logger = source("src/main/java/com/bncam/core/debug/ShotLogger.kt")
        val single = source("src/main/java/com/bncam/core/runners/SingleFrameRunner.kt")
        val multi = source("src/main/java/com/bncam/core/runners/MultiFrameRunner.kt")

        listOf(
            "Authority Type",
            "Logical Metadata Fallback",
            "Foreign Sensor Metadata Used",
            "RAW Source ID",
            "CaptureResult Source ID",
            "Characteristics Source ID",
            "Calibration Source ID",
            "RAW/Metadata Timestamp Match",
            "Raw Processing Safe"
        ).forEach { label -> assertTrue(label in logger) }

        listOf(single, multi).forEach { runner ->
            assertTrue("sensorAuthorityId = sensorIdentity?.sensorAuthorityId" in runner)
            assertTrue("physicalCameraId = sensorIdentity?.physicalLabel" in runner)
            assertTrue("it.logicalMetadataFallbackUsed || it.foreignSensorMetadataUsed" in runner)
        }
        assertTrue("sensorAuthorityTypeLabel(entry)" in logger)
        assertTrue("LOGICAL_METADATA_FALLBACK_FORBIDDEN" in logger)
        assertTrue("FOREIGN_SENSOR_METADATA_FORBIDDEN" in logger)
    }
}
