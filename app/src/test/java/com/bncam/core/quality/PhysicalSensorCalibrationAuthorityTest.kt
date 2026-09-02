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
    fun `single physical result is deterministic fallback`() {
        val decision = resolvePhysicalResultRoute(null, setOf("wide"))
        assertEquals("wide", decision.physicalCameraId)
        assertEquals("SOLE_PHYSICAL_RESULT", decision.authority)
        assertTrue(decision.deterministic)
    }

    @Test
    fun `multiple physical children without active id never choose alphabetically`() {
        val decision = resolvePhysicalResultRoute(null, setOf("tele", "wide"))
        assertNull(decision.physicalCameraId)
        assertEquals("AMBIGUOUS_MULTIPLE_PHYSICAL_RESULTS_LOGICAL_FALLBACK", decision.authority)
        assertFalse(decision.deterministic)
    }

    @Test
    fun `stale active id with multiple children fails closed to logical`() {
        val decision = resolvePhysicalResultRoute("ultrawide", setOf("wide", "tele"))
        assertNull(decision.physicalCameraId)
        assertEquals("AMBIGUOUS_ACTIVE_ID_NOT_IN_RESULTS_LOGICAL_FALLBACK", decision.authority)
        assertFalse(decision.deterministic)
    }

    @Test
    fun `render config consumes central calibration input before resolver`() {
        val appDir = sequenceOf(File("."), File("app"))
            .firstOrNull { File(it, "src/main/java/com/bncam/core/quality/RenderQualityConfig.kt").isFile }
            ?: error("Unable to locate app module")
        val config = File(appDir, "src/main/java/com/bncam/core/quality/RenderQualityConfig.kt").readText()
        val registry = File(appDir, "src/main/java/com/bncam/core/quality/PhysicalSensorProfileRegistry.kt").readText()

        assertTrue("PhysicalSensorProfileRegistry.resolveCurrentCalibrationInput(" in config)
        assertTrue("physicalCameraId = calibrationInput.physicalCameraId" in config)
        assertTrue("characteristics = calibrationInput.characteristics" in config)
        assertTrue("captureResult = calibrationInput.captureResult" in config)
        assertFalse("physicalCameraId = null" in config.substringAfter("SensorCalibrationResolver.resolve(").substringBefore(")\n"))
        assertTrue("FRAME_SNAPSHOT_EXACT_PHYSICAL_RESULT" in registry)
        assertTrue("recentFrameRoutesByTimestamp" in registry)
        assertTrue("AMBIGUOUS_MULTIPLE_PHYSICAL_RESULTS_LOGICAL_FALLBACK" in registry)
        assertFalse("physicalResultIds.sorted().firstOrNull()" in registry)
    }
}
