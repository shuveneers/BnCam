package com.bncam.core.engine

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

class PhysicalSensorAdaptiveExposureSourceContractTest {
    private fun appDir(): File = sequenceOf(File("."), File("app"))
        .firstOrNull {
            File(it, "src/main/java/com/bncam/core/engine/BnCameraManager.kt").isFile
        }
        ?: error("Unable to locate app module")

    private fun managerSource(): String =
        File(appDir(), "src/main/java/com/bncam/core/engine/BnCameraManager.kt").readText()

    @Test
    fun `standard auto is one Camera2 AE owner for every stream`() {
        val manager = managerSource()
        val apply = manager.substringAfter("private fun applyExposurePolicy")
            .substringBefore("private fun cancelFocusTimingJobs")

        assertTrue("authorityDecision.camera2OwnsCompleteExposure" in apply)
        assertTrue("owner=CAMERA2_HAL" in apply)
        assertTrue("defaultRawPriority=false" in apply)
        assertTrue("builder.set(CaptureRequest.SENSOR_SENSITIVITY, null)" in apply)
        assertTrue("builder.set(CaptureRequest.SENSOR_EXPOSURE_TIME, null)" in apply)
        assertTrue("builder.set(CaptureRequest.SENSOR_FRAME_DURATION, null)" in apply)
    }

    @Test
    fun `hidden default raw exposure controller is retired`() {
        val manager = managerSource()
        assertTrue("private fun shouldRequestDefaultRawShutterMotionAnalysis(): Boolean = false" in manager)
        assertTrue("STANDARD_AUTO_CAMERA2_AE" in manager)
    }

    @Test
    fun `new physical sensor starts a clean exposure epoch`() {
        val manager = managerSource()
        val reset = manager.substringAfter("private fun resetExposureAdaptationForPipeline")
            .substringBefore("fun notifyViewfinderLiveTuningChanged")

        assertTrue("clearDefaultRawShutterPriorityState(resetMotion = true)" in reset)
        assertTrue("latestExposureStatistics = null" in reset)
        assertTrue("lastCaptureResult = null" in reset)
        assertTrue("lastCaptureResultGeneration = -1" in reset)
        assertTrue("resetRawFlickerAuthority" in reset)
        assertTrue("HARD_START_SENSOR_EPOCH" in manager)
        assertTrue("PHYSICAL_PIPELINE_RESET" in manager)
    }

    @Test
    fun `manual baseline cannot leak from a previous sensor generation`() {
        val manager = managerSource()
        val resolver = manager.substringAfter("private fun resolveExposurePlan")
            .substringBefore("private fun resolveProfileExposurePriorityPlan")

        assertTrue("lastCaptureResultGeneration == pipelineGeneration" in resolver)
    }

    @Test
    fun `physical sensor characteristics are used for explicit exposure bounds`() {
        val manager = managerSource()
        val apply = manager.substringAfter("private fun applyExposurePolicy")
            .substringBefore("private fun cancelFocusTimingJobs")

        assertTrue("val physicalSensorId = identity?.physicalCameraId ?: deviceId" in apply)
        assertTrue("cameraManager.getCameraCharacteristics(physicalSensorId)" in apply)
        assertTrue("val characteristics = sensorCharacteristics" in apply)
    }

    @Test
    fun `ae cadence is intersected with exact physical sensor capability`() {
        val manager = managerSource()
        val cadence = manager.substringAfter("private fun applyOptimalAeTargetFpsRange")
            .substringBefore("private fun applyFocusStrategy")

        assertTrue("val physicalSensorId = identity?.physicalCameraId" in cadence)
        assertTrue("val sensorCharacteristics" in cadence)
        assertTrue("physicalCompatibleRanges" in cadence)
        assertTrue("fpsAuthority=" in cadence)
    }
}
