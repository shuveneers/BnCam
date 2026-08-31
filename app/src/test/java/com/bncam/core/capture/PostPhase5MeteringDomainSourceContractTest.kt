package com.bncam.core.capture

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PostPhase5MeteringDomainSourceContractTest {
    private fun source(path: String): String {
        val direct = File(path)
        if (direct.exists()) return direct.readText()
        return File("app/$path").readText()
    }

    @Test
    fun `standard ae metering uses independent logical and physical coordinate domains`() {
        val manager = source("src/main/java/com/bncam/core/engine/BnCameraManager.kt")
        val method = manager.substringAfter("fun applyMeteringPolicy(builder: CaptureRequest.Builder)")
            .substringBefore("private fun Rect.clampedInside")

        assertTrue(method.contains("val logicalPlan = CameraMeteringPolicy.plan"))
        assertTrue(method.contains("val physicalPlan = CameraMeteringPolicy.plan"))
        assertTrue(method.contains("val logicalBounds = resolveMeteringCoordinateBounds(chars, builder)"))
        assertTrue(method.contains("val physicalBounds = physicalChars?.let { resolveMeteringCoordinateBounds(it, builder) }"))
        assertTrue(method.contains("mapMeteringRegions(logicalPlan, logicalBounds, logicalMaxAeRegions)"))
        assertTrue(method.contains("mapMeteringRegions(physicalPlan, physicalBounds, physicalMaxAeRegions)"))
        assertFalse(method.contains("val meteringBounds = physicalChars"))
    }

    @Test
    fun `physical tap ae rectangle is never copied into logical ae region`() {
        val manager = source("src/main/java/com/bncam/core/engine/BnCameraManager.kt")
        val method = manager.substringAfter("fun applyMeteringPolicy(builder: CaptureRequest.Builder)")
            .substringBefore("private fun Rect.clampedInside")
        val physicalTap = method.substringAfter("touchPhysicalId != null && touchPhysicalId == activePhysicalId")
            .substringBefore("} else if (touchPhysicalId == null")

        assertTrue(physicalTap.contains("builder.set(CaptureRequest.CONTROL_AE_REGIONS, null)"))
        assertTrue(physicalTap.contains("builder.setPhysicalCameraKey("))
        assertTrue(physicalTap.contains("arrayOf(touchRegion)"))
    }

    @Test
    fun `ae metering coordinate domain follows distortion correction contract and validates result echo`() {
        val manager = source("src/main/java/com/bncam/core/engine/BnCameraManager.kt")

        assertTrue(manager.contains("SENSOR_INFO_PRE_CORRECTION_ACTIVE_ARRAY_SIZE"))
        assertTrue(manager.contains("DISTORTION_CORRECTION_MODE_OFF"))
        assertTrue(manager.contains("SENSOR_INFO_ACTIVE_ARRAY_SIZE_MAXIMUM_RESOLUTION"))
        assertTrue(manager.contains("validateMeteringResultEcho(result)"))
        assertTrue(manager.contains("AE_REGION_ECHO_OK"))
        assertTrue(manager.contains("AE_REGION_ECHO_MISMATCH"))
    }
}
