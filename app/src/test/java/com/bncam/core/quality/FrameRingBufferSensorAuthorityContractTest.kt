package com.bncam.core.quality

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FrameRingBufferSensorAuthorityContractTest {
    @Test
    fun `raw ring completion requires exact sensor authority`() {
        val appDir = sequenceOf(File("."), File("app"))
            .firstOrNull {
                File(it, "src/main/java/com/bncam/core/buffer/FrameRingBuffer.kt").isFile
            }
            ?: error("Unable to locate app module")
        val source = File(
            appDir,
            "src/main/java/com/bncam/core/buffer/FrameRingBuffer.kt"
        ).readText()

        assertTrue("hasCompleteProvenance" in source)
        assertTrue("hasExactSensorAuthority" in source)
        assertTrue("frameIdentityForRaw(pair.timestamp)" in source)
        assertTrue("RAW_PAIR_REJECTED_SENSOR_AUTHORITY" in source)
        assertTrue("identity.debugText()" in source)
        assertTrue("requiresExactSensorAuthority(pair.format)" in source)
        assertTrue("pair.sensorMetadataSnapshot?.exposureTimeNs" in source)
        assertTrue("pair.sensorMetadataSnapshot?.sensitivityIso" in source)
        assertFalse("PHYSICAL_METADATA_UNAVAILABLE_LOGICAL_FALLBACK" in source)
    }
}
