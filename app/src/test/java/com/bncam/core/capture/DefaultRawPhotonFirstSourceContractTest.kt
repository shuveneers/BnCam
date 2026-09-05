package com.bncam.core.capture

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class DefaultRawPhotonFirstSourceContractTest {
    private val appDir: File = sequenceOf(File("."), File("app"))
        .firstOrNull { File(it, "src/main/cpp/CMakeLists.txt").isFile }
        ?: error("Cannot locate app module from ${File(".").absolutePath}")

    private fun source(path: String): String = File(appDir, path).readText()

    @Test
    fun `default raw production owns shutter and iso after ae bootstrap`() {
        val tracker = source("src/main/java/com/bncam/core/capture/DefaultRawApi36AuthorityTracker.kt")
        val manager = source("src/main/java/com/bncam/core/engine/BnCameraManager.kt")
        val exposure = manager.substringAfter("private fun applyExposurePolicy")
            .substringBefore("private fun cancelFocusTimingJobs")

        assertTrue(tracker.contains("private val priorityEnabled: Boolean = false"))
        assertTrue(exposure.contains("fallback=MANUAL_LINEAR_LUMA_FEEDBACK"))
        assertTrue(exposure.contains("CaptureRequest.CONTROL_AE_MODE_OFF"))
        assertTrue(exposure.contains("CaptureRequest.SENSOR_EXPOSURE_TIME, fallbackPlan.exposureTimeNs"))
        assertTrue(exposure.contains("CaptureRequest.SENSOR_SENSITIVITY, fallbackPlan.sensitivityIso"))
    }

    @Test
    fun `photon first planner is not release throttled against bootstrap shutter`() {
        val policy = source("src/main/java/com/bncam/core/capture/DefaultRawShutterPriorityPolicy.kt")
        assertTrue(policy.contains("photon_first_longest_safe_shutter_minimizes_gain"))
        assertTrue(!policy.contains("MAX_RELEASE_STEP_EV"))
        assertTrue(!policy.contains("nextLongerAlignedExposureNs"))
    }

    @Test
    fun `motion authority separates camera and subject blur budgets`() {
        val motion = source("src/main/java/com/bncam/core/capture/RawPreviewMotionMeter.kt")
        assertTrue(motion.contains("cameraBlurBudgetPixels"))
        assertTrue(motion.contains("sceneBlurBudgetPixels"))
        assertTrue(motion.contains("exposureAuthorityMinConfidence"))
    }
}
