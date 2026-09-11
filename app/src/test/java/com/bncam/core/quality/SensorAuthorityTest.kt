package com.bncam.core.quality

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SensorAuthorityTest {
    @Test
    fun `physical child frame is safe only when every source matches authority`() {
        val sensor = SensorIdentity(
            sensorAuthorityId = "tele",
            cameraDeviceId = "logical",
            physicalCameraId = "tele",
            authorityType = SensorAuthorityType.PHYSICAL_CHILD
        )
        val capture = CaptureIdentity(
            sensorIdentity = sensor,
            frameNumber = 42L,
            sensorTimestampNs = 123456L,
            captureSequenceId = 7
        )
        val frame = FrameIdentity(
            captureIdentity = capture,
            rawSourceId = "tele",
            captureResultSourceId = "tele",
            characteristicsSourceId = "tele",
            calibrationSourceId = "tele",
            rawSensorTimestampNs = 123456L
        )

        assertTrue(frame.rawMetadataTimestampMatch)
        assertTrue(frame.sourcesMatchAuthority)
        assertTrue(frame.safeForRawProcessing)
        assertEquals("NONE", frame.rejectionReason())
    }

    @Test
    fun `foreign metadata is rejected`() {
        val sensor = SensorIdentity(
            sensorAuthorityId = "main",
            cameraDeviceId = "logical",
            physicalCameraId = "main",
            authorityType = SensorAuthorityType.PHYSICAL_CHILD
        )
        val frame = FrameIdentity(
            captureIdentity = CaptureIdentity(sensor, 12L, 999L),
            rawSourceId = "main",
            captureResultSourceId = "logical",
            characteristicsSourceId = "main",
            calibrationSourceId = "main",
            rawSensorTimestampNs = 999L
        )

        assertFalse(frame.sourcesMatchAuthority)
        assertFalse(frame.safeForRawProcessing)
        assertEquals("CAPTURE_RESULT_SOURCE_MISMATCH", frame.rejectionReason())
    }

    @Test
    fun `timestamp mismatch is rejected`() {
        val sensor = SensorIdentity(
            sensorAuthorityId = "0",
            cameraDeviceId = "0",
            physicalCameraId = null,
            authorityType = SensorAuthorityType.STANDALONE
        )
        val frame = FrameIdentity(
            captureIdentity = CaptureIdentity(sensor, 3L, 1000L),
            rawSourceId = "0",
            captureResultSourceId = "0",
            characteristicsSourceId = "0",
            calibrationSourceId = "0",
            rawSensorTimestampNs = 1001L
        )

        assertFalse(frame.rawMetadataTimestampMatch)
        assertFalse(frame.safeForRawProcessing)
        assertEquals("RAW_METADATA_TIMESTAMP_MISMATCH", frame.rejectionReason())
    }
}
