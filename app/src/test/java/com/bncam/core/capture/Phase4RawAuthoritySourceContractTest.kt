package com.bncam.core.capture

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class Phase4RawAuthoritySourceContractTest {
    private fun source(path: String): String = File(path).readText()

    @Test fun singleFrameRawAndDngUseExactAuthorityInput() {
        val runner = source("app/src/main/java/com/bncam/core/runners/SingleFrameRunner.kt")
        assertTrue("val exactRawAuthorityInput" in runner)
        assertTrue("characteristics = rawAuthorityCharacteristics" in runner)
        assertTrue("captureResult = rawAuthorityCaptureResult" in runner)
        assertTrue("metadata = rawAuthorityCaptureResult" in runner)
        assertTrue("RAW_PUBLICATION_INTEGRITY_BLOCKED" in runner)
        assertTrue("RAW Publication Integrity" in runner)
    }

    @Test fun multiFrameAnchorAndDngUseExactAuthorityInput() {
        val runner = source("app/src/main/java/com/bncam/core/runners/MultiFrameRunner.kt")
        assertTrue("val anchorRawAuthorityInput" in runner)
        assertTrue("characteristics = rawAuthorityCharacteristics" in runner)
        assertTrue("captureResult = rawAuthorityCaptureResult" in runner)
        assertTrue("metadata = rawAuthorityCaptureResult" in runner)
        assertTrue("RAW_PUBLICATION_INTEGRITY_BLOCKED" in runner)
    }

    @Test fun dngWriterFailsClosedOnAuthorityMismatch() {
        val writer = source("app/src/main/java/com/bncam/core/isp/raw10/DngWriter.kt")
        assertTrue("RawDngAuthorityContract.evaluate" in writer)
        assertTrue("DNG_AUTHORITY_BLOCKED" in writer)
        assertTrue("metadata.cameraId" in writer)
        assertTrue("frameTimestampMatches" in writer)
        assertTrue("DNG Authority=EXACT_FRAME_SENSOR_AUTHORITY" in writer)
    }

    @Test fun phase4DoesNotAddSensorIdSpecificProductionBranches() {
        val files = listOf(
            "app/src/main/java/com/bncam/core/capture/RawPublicationIntegrityGate.kt",
            "app/src/main/java/com/bncam/core/isp/raw10/RawDngAuthorityContract.kt",
            "app/src/main/java/com/bncam/core/isp/raw10/DngWriter.kt"
        )
        val joined = files.joinToString("\n") { source(it) }
        assertFalse("sensorAuthorityId == \"2\"" in joined)
        assertFalse("sensorAuthorityId == \"4\"" in joined)
        assertFalse("sensorAuthorityId == \"5\"" in joined)
    }
}
