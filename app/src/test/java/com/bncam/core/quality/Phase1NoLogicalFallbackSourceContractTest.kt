package com.bncam.core.quality

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class Phase1NoLogicalFallbackSourceContractTest {
    private fun appDir(): File = sequenceOf(File("."), File("app"))
        .firstOrNull {
            File(it, "src/main/java/com/bncam/core/quality/SensorCalibration.kt").isFile
        }
        ?: error("Unable to locate app module")

    @Test
    fun `sensor calibration never guesses a physical child`() {
        val source = File(
            appDir(),
            "src/main/java/com/bncam/core/quality/SensorCalibration.kt"
        ).readText()

        assertFalse("physicalCameraId ?: resolvePhysicalCameraId(captureResult)" in source)
        assertFalse("private fun resolvePhysicalCameraId" in source)
        assertFalse("physicalResults.keys.sorted().firstOrNull()" in source)
    }

    @Test
    fun `camera manager never falls back from missing physical result to parent`() {
        val source = File(
            appDir(),
            "src/main/java/com/bncam/core/engine/BnCameraManager.kt"
        ).readText()
        val helper = source
            .substringAfter("private fun previewCaptureResult(")
            .substringBefore("fun setPreviewOrientationCorrection")

        assertTrue("): CaptureResult?" in helper)
        assertTrue("PHYSICAL_METADATA_UNAVAILABLE" in helper)
        assertFalse(".getOrNull() ?: result" in helper)
        assertTrue("skipping WB observation" in source)
        val wbObservation = source
            .substringAfter("val calibrationResult = previewCaptureResult(result, identity.physicalCameraId)")
            .substringBefore("val convergence = when (awbState)")
        assertFalse("?: result.get(CaptureResult.CONTROL_AWB_STATE)" in wbObservation)
        assertFalse("physicalMetadata ?: metadata" in source)
        assertFalse("physicalReported ?: result.get(CaptureResult.STATISTICS_SCENE_FLICKER)" in source)
        assertTrue("logical parent not substituted" in source)
        assertTrue("timestamp = sensorMetadataSnapshot?.sensorTimestampNs ?: timestamp" in source)
        assertTrue("timestamp = sensorMetadataSnapshot?.sensorTimestampNs ?: sensorTimestamp" in source)
        assertTrue("CAPTURE_REJECT_SENSOR_AUTHORITY" in source)
    }

    @Test
    fun `shot diagnostics expose exact raw sensor authority`() {
        val logger = File(
            appDir(),
            "src/main/java/com/bncam/core/debug/ShotLogger.kt"
        ).readText()
        val single = File(
            appDir(),
            "src/main/java/com/bncam/core/runners/SingleFrameRunner.kt"
        ).readText()
        val multi = File(
            appDir(),
            "src/main/java/com/bncam/core/runners/MultiFrameRunner.kt"
        ).readText()

        listOf(
            "Sensor Authority ID",
            "Camera Device ID",
            "Physical Camera ID",
            "RAW Source ID",
            "CaptureResult Source ID",
            "Characteristics Source ID",
            "Calibration Source ID",
            "Capture Sequence ID",
            "RAW/Metadata Timestamp Match",
            "Fallback Used",
            "Raw Processing Safe"
        ).forEach { label -> assertTrue(label in logger) }
        assertTrue("frameIdentityForRaw(frame.timestamp)" in single)
        assertTrue("frameIdentityForRaw(frame.timestamp)" in multi)
    }
}
