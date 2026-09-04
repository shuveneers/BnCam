package com.bncam.core.capture

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class DefaultRawApi36AuthoritySourceContractTest {
    private val appDir: File = sequenceOf(File("."), File("app"))
        .firstOrNull { File(it, "src/main/cpp/CMakeLists.txt").isFile }
        ?: error("Cannot locate app module from ${File(".").absolutePath}")

    private fun manager(): String =
        File(appDir, "src/main/java/com/bncam/core/engine/BnCameraManager.kt").readText()

    @Test
    fun `api36 trust consumes exact repeating request result truth`() {
        val source = manager()
        val truth = source.substringAfter("private fun updateDefaultRawExposureRealizationTruth")
            .substringBefore("private fun resetRawFlickerAuthority")

        assertTrue(truth.contains("defaultRawApi36AuthorityTracker.observe("))
        assertTrue(truth.contains("snapshot.submissionType == CameraRequestSubmissionType.REPEATING"))
        assertTrue(truth.contains("CaptureResult.CONTROL_AE_STATE_SEARCHING"))
        assertTrue(truth.contains("SENSOR_INFO_SENSITIVITY_RANGE"))
    }

    @Test
    fun `api36 min iso search reboots reference while realization failure can reject route`() {
        val source = manager()
        val truth = source.substringAfter("private fun updateDefaultRawExposureRealizationTruth")
            .substringBefore("private fun resetRawFlickerAuthority")

        assertTrue(truth.contains("API36_AE_SEARCHING_AT_MIN_ISO"))
        assertTrue(truth.contains("API36_PRIORITY_NOT_REALIZED"))
        assertTrue(truth.contains("restartDefaultRawAeReference(reason, preserveRealizationTruth = true)"))
    }

    @Test
    fun `advertised capability is not sufficient once runtime route is rejected`() {
        val source = manager()
        val exposure = source.substringAfter("private fun applyExposurePolicy")
            .substringBefore("private fun cancelFocusTimingJobs")

        assertTrue(exposure.contains("priorityModeAllowed = priorityModeSupported"))
        assertTrue(exposure.contains("defaultRawApi36AuthorityTracker.isAllowed(pipelineGeneration)"))
        assertTrue(exposure.contains("if (priorityModeAllowed)"))
        assertTrue(exposure.contains("fallback=AE_LUMA_ANCHOR_BOOTSTRAP"))
        assertTrue(exposure.contains("fallback=MANUAL_LINEAR_LUMA_FEEDBACK"))
    }
}
