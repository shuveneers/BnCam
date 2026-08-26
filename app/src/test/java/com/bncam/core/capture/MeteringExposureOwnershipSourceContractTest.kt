package com.bncam.core.capture

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MeteringExposureOwnershipSourceContractTest {
    private val appDir: File = sequenceOf(File("."), File("app"))
        .firstOrNull { File(it, "src/main/cpp/CMakeLists.txt").isFile }
        ?: error("Cannot locate app module from ${File(".").absolutePath}")

    private fun source(relative: String): String = File(appDir, relative).readText()

    @Test
    fun `metering remains a Camera2 control concern and does not mutate RAW preview`() {
        val manager = source("src/main/java/com/bncam/core/engine/BnCameraManager.kt")
        val metering = manager.substringAfter("fun setMeteringStyle")
            .substringBefore("private fun stabilizeRawPreviewAutoWb")

        assertFalse(metering.contains("rawPreviewRenderer.updateExposureTruthMode"))
        assertFalse(metering.contains("renderRawPreview"))
        assertTrue(metering.contains("CaptureRequest.CONTROL_AE_REGIONS"))
    }

    @Test
    fun `logical AE owner is always written and physical route is additive`() {
        val manager = source("src/main/java/com/bncam/core/engine/BnCameraManager.kt")
        val metering = manager.substringAfter("fun applyMeteringPolicy")
            .substringBefore("private fun Rect.clampedInside")

        assertTrue(metering.contains("builder.set(CaptureRequest.CONTROL_AE_REGIONS"))
        assertTrue(metering.contains("builder.setPhysicalCameraKey("))
        assertTrue(metering.contains("physicalMirror="))
        assertFalse(metering.contains("Keep the logical domain neutral"))
    }

    @Test
    fun `explicit metering cannot be replaced by a focus tap`() {
        val manager = source("src/main/java/com/bncam/core/engine/BnCameraManager.kt")
        val tap = manager.substringAfter("private fun tapToFocusAtOwned(")
            .substringBefore("private fun releaseTouchAeToStandardMeteringForTracking")

        assertTrue(tap.contains("resolvedMeteringMode == MeteringMode.AUTO_DEFAULT_AE"))
        assertTrue(tap.contains("Focus taps own AF"))
        assertTrue(tap.contains("applyMeteringPolicy(request)"))
    }

    @Test
    fun `single manual axis keeps AE alive when a supported priority backend exists`() {
        val manager = source("src/main/java/com/bncam/core/engine/BnCameraManager.kt")
        val exposure = manager.substringAfter("private fun applyExposurePolicy")
            .substringBefore("private fun cancelFocusTimingJobs")

        assertTrue(exposure.contains("applyAePriorityKeepingMetering"))
        assertTrue(manager.contains("CONTROL_AE_PRIORITY_MODE_SENSOR_SENSITIVITY_PRIORITY"))
        assertTrue(manager.contains("CONTROL_AE_PRIORITY_MODE_SENSOR_EXPOSURE_TIME_PRIORITY"))
        assertTrue(exposure.contains("meteringOwner=camera2_ae"))
    }
}
