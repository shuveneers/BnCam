package com.bncam.core.engine

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ProfileExposurePriorityLiveAdaptationSourceContractTest {
    private fun manager(): String = File(
        System.getProperty("user.dir"),
        "src/main/java/com/bncam/core/engine/BnCameraManager.kt"
    ).readText()

    @Test
    fun priorityEnablesStatisticsWithoutRequiringHistogramUi() {
        val source = manager()
        val gate = source.substringAfter("private fun needsProfileExposureStatistics()")
            .substringBefore("private fun maybeAdaptProfileExposurePriority")
        assertTrue(gate.contains("activeProfileExposurePreferences.requiresAeBaseline()"))
        assertTrue(gate.contains("requestedManualIso == null"))
        assertTrue(gate.contains("histogramAnalysisEnabled || needsProfileExposureStatistics()"))
    }

    @Test
    fun liveControllerIsBoundedNonBlockingAndUsesCachedSensorBounds() {
        val source = manager()
        val controller = source.substringAfter("private fun maybeAdaptProfileExposurePriority")
            .substringBefore("private fun publishLiveExposureStatistics")
        assertTrue(controller.contains("250_000_000L"))
        assertTrue(controller.contains("ProfileExposurePriorityPlanner.adaptToLuma"))
        assertTrue(controller.contains("activeProfileExposureBounds ?: return"))
        assertTrue(controller.contains("enqueuePreviewControl(\"profile_exposure_live\""))
        assertFalse(controller.contains("runBlocking"))
        assertFalse(controller.contains("delay("))
        assertFalse(controller.contains("getCameraCharacteristics"))
        assertFalse(controller.contains("createCaptureSession"))
        assertFalse(controller.contains("stopRepeating"))
    }

    @Test
    fun statisticsPublishFeedsControllerButHistogramUiRemainsOptional() {
        val source = manager()
        val publish = source.substringAfter("private fun publishLiveExposureStatistics")
            .substringBefore("private data class PreviewNv21")
        assertTrue(publish.contains("latestExposureStatistics = statistics"))
        assertTrue(publish.contains("maybeAdaptProfileExposurePriority(statistics)"))
        assertTrue(publish.contains("if (histogramAnalysisEnabled)"))
    }
}
