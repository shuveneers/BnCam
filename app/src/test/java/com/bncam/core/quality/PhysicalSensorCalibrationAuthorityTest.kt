package com.bncam.core.quality

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PhysicalSensorCalibrationAuthorityTest {
    @Test
    fun `active physical id wins only when exact child result exists`() {
        val decision = resolvePhysicalResultRoute("tele", setOf("wide", "tele"))
        assertEquals("tele", decision.physicalCameraId)
        assertEquals("ACTIVE_PHYSICAL_ID_EXACT_RESULT", decision.authority)
        assertTrue(decision.deterministic)
    }

    @Test
    fun `single physical result without authority no longer becomes fallback`() {
        val decision = resolvePhysicalResultRoute(null, setOf("wide"))
        assertNull(decision.physicalCameraId)
        assertEquals("PHYSICAL_AUTHORITY_UNSPECIFIED", decision.authority)
        assertFalse(decision.deterministic)
    }

    @Test
    fun `stale active physical id fails closed`() {
        val decision = resolvePhysicalResultRoute("ultrawide", setOf("wide", "tele"))
        assertNull(decision.physicalCameraId)
        assertEquals("PHYSICAL_METADATA_UNAVAILABLE", decision.authority)
        assertFalse(decision.deterministic)
    }

    @Test
    fun `missing physical result fails closed`() {
        val decision = resolvePhysicalResultRoute("tele", emptySet())
        assertNull(decision.physicalCameraId)
        assertEquals("PHYSICAL_METADATA_UNAVAILABLE", decision.authority)
        assertFalse(decision.deterministic)
    }

    @Test
    fun `registry contains no logical metadata substitution`() {
        val appDir = sequenceOf(File("."), File("app"))
            .firstOrNull {
                File(it, "src/main/java/com/bncam/core/quality/PhysicalSensorProfileRegistry.kt").isFile
            }
            ?: error("Unable to locate app module")
        val registry = File(
            appDir,
            "src/main/java/com/bncam/core/quality/PhysicalSensorProfileRegistry.kt"
        ).readText()

        assertTrue("SensorAuthorityUnavailableException" in registry)
        assertTrue("FRAME_SNAPSHOT_EXACT_PHYSICAL_RESULT" in registry)
        assertTrue("FRAME_SNAPSHOT_STANDALONE_RESULT" in registry)
        assertTrue("recentFrameResultsByTimestamp" in registry)
        assertTrue("logicalMetadataFallbackUsed = false" in registry)
        assertTrue("metadata.cameraId" in registry)
        assertTrue("PHYSICAL_RESULT_FRAME_NUMBER_MISMATCH" in registry)
        assertTrue("PHYSICAL_RESULT_SEQUENCE_ID_MISMATCH" in registry)
        assertTrue("FOREIGN_SENSOR_CAPTURE_RESULT" in registry)
        assertFalse("LOGICAL_FALLBACK" in registry)
        assertFalse("SOLE_PHYSICAL_RESULT" in registry)
        assertFalse("physical ?: result" in registry)
        assertFalse("physicalResultIds.single()" in registry)
    }
}
