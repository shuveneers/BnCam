package com.bncam.core.sensors

import com.bncam.core.capture.HdrAeCompensationBounds
import com.bncam.core.capture.HdrExposureBracketPlanner
import com.bncam.core.capture.HdrExposureControlMode
import com.bncam.core.capture.HdrManualSensorBounds
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class PostPhase10HdrPhoneAssistanceContractTest {
    private fun source(path: String): String = File(System.getProperty("user.dir"), path).readText()

    @Test
    fun hdrFallsBackToAeWhenManualCapabilityCannotProduceUsefulSceneBracket() {
        val plan = HdrExposureBracketPlanner.plan(
            baseExposureTimeNs = 1_000_000L,
            baseSensitivityIso = 100,
            manualBounds = HdrManualSensorBounds(
                exposureTimeMinNs = 800_000L,
                exposureTimeMaxNs = 1_200_000L,
                sensitivityIsoMin = 100,
                sensitivityIsoMax = 100,
                maxFrameDurationNs = 1_200_000L
            ),
            aeBounds = HdrAeCompensationBounds(
                minIndex = -8,
                maxIndex = 8,
                evPerIndex = 0.25f
            ),
            motionHigh = false
        )

        assertTrue(plan.enabled)
        assertEquals(HdrExposureControlMode.AE_COMPENSATION, plan.controlMode)
        assertTrue(plan.fallbackReason.contains("ae_compensation_used"))
    }

    @Test
    fun phoneAssistanceHasLiveListenerExactMetadataAndSingleMultiRawWiring() {
        val manager = source("src/main/java/com/bncam/core/engine/BnCameraManager.kt")
        assertTrue(manager.contains("phoneAssistanceSensorsFlow.collectLatest"))
        assertTrue(manager.contains("phoneAssistanceSensorHelper.startListening()"))
        assertTrue(manager.contains("phoneAssistanceSensorHelper.stopListening()"))

        val helper = source("src/main/java/com/bncam/core/sensors/ColorSensorHelper.kt")
        assertTrue(helper.contains("captureResult.physicalCameraResults.values"))
        assertTrue(helper.contains("Camera2VendorIlluminantAdapter.extract"))
        assertTrue(helper.contains("return getLatestReading(nowElapsedRealtimeNs)"))

        val adapter = source("src/main/java/com/bncam/core/sensors/AuxiliarySensorAdapter.kt")
        assertTrue(adapter.contains("CAMERA2_VENDOR_ILLUMINANT"))
        assertTrue(adapter.contains("color_temperature"))
        assertTrue(adapter.contains("colour_temperature"))

        val single = source("src/main/java/com/bncam/core/runners/SingleFrameRunner.kt")
        val multi = source("src/main/java/com/bncam/core/runners/MultiFrameRunner.kt")
        assertTrue(single.contains("colorSensorHelper.getBestReading(captureMetadata)"))
        assertTrue(multi.contains("phoneAssistanceHelper.getBestReading(anchorMetadata)"))
        assertTrue(multi.contains("colorSensorContributionWeight = colorSensorContributionWeight"))
    }

    @Test
    fun hdrAcquisitionHasBaselineFallbackAndVisibleAnchorOnlyWarning() {
        val manager = source("src/main/java/com/bncam/core/engine/BnCameraManager.kt")
        assertTrue(manager.contains("HDR_BRACKET_ABORT reason=no_exact_baseline_frame"))
        assertTrue(manager.contains("userShutterTimestampNs = 0L"))

        val runner = source("src/main/java/com/bncam/core/runners/MultiFrameRunner.kt")
        assertTrue(runner.contains("Computational HDR was enabled but an exact exposure bracket could not be acquired"))
        assertTrue(runner.contains("computationalHdrActive = hdrRequestedForShot && hdrBracket != null"))
    }
}
