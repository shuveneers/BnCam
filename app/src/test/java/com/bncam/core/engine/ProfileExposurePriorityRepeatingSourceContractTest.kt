package com.bncam.core.engine

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ProfileExposurePriorityRepeatingSourceContractTest {
    private fun manager(): String = File(
        System.getProperty("user.dir"),
        "src/main/java/com/bncam/core/engine/BnCameraManager.kt"
    ).readText()

    @Test
    fun profilePriorityOwnsRepeatingExposureWithoutSessionRebuild() {
        val source = manager()
        val refresh = source.substringAfter("private fun refreshActiveProfileExposurePreferences(")
            .substringBefore("/** Invalidate only the RAW preview calibration")
        val apply = source.substringAfter("private fun applyExposurePolicy(builder: CaptureRequest.Builder)")
            .substringBefore("private fun cancelFocusTimingJobs()")

        assertTrue(refresh.contains("sessionTransitionScope.launch"))
        assertTrue(refresh.contains("enqueuePreviewControl(\"profile_exposure_priority\""))
        assertTrue(apply.contains("CaptureRequest.CONTROL_AE_MODE_OFF"))
        assertTrue(apply.contains("CaptureRequest.SENSOR_SENSITIVITY, profilePlan.sensitivityIso!!"))
        assertTrue(apply.contains("CaptureRequest.SENSOR_EXPOSURE_TIME, profilePlan.exposureTimeNs!!"))
        assertFalse(refresh.contains("createCaptureSession"))
        assertFalse(refresh.contains("stopRepeating"))
        assertFalse(apply.contains("createCaptureSession"))
        assertFalse(apply.contains("stopRepeating"))
    }

    @Test
    fun bootstrapAcceptsOnlyFreshRepeatingAeProvenance() {
        val source = manager()
        val callback = source.substringAfter("if (profileExposureAwaitingAeBaseline &&")
            .substringBefore("val afState = result.get(CaptureResult.CONTROL_AF_STATE)")

        assertTrue(callback.contains("controlRequestEpochTracker.resolveTag(request.tag, sessionGeneration)"))
        assertTrue(callback.contains("requestSnapshot?.submissionType == CameraRequestSubmissionType.REPEATING"))
        assertTrue(callback.contains("requestSnapshot.identity.controlRequestEpoch >= profileExposureBootstrapMinControlEpoch"))
        assertTrue(callback.contains("requestAeMode != CaptureRequest.CONTROL_AE_MODE_OFF"))
        assertTrue(callback.contains("profileExposureAeBaselineIso = measuredIso"))
        assertTrue(callback.contains("profileExposureAeBaselineExposureNs = measuredExposureNs"))
    }

    @Test
    fun liveEvUsesOriginalAeBaselineAndManualOverrideWins() {
        val source = manager()
        val resolver = source.substringAfter("private fun resolveProfileExposurePriorityPlan(")
            .substringBefore("private fun applyExposurePolicy(builder: CaptureRequest.Builder)")
        val apply = source.substringAfter("private fun applyExposurePolicy(builder: CaptureRequest.Builder)")
            .substringBefore("private fun cancelFocusTimingJobs()")
        val setExposure = source.substringAfter("fun setExposure(exposureEV: Float, cameraId: String)")
            .substringBefore("// ========================================================\n        // MANUAL EXPOSURE")

        assertTrue(resolver.contains("measuredIso = profileExposureAeBaselineIso.takeIf { baselineReady }"))
        assertTrue(resolver.contains("measuredExposureNs = profileExposureAeBaselineExposureNs.takeIf { baselineReady }"))
        assertTrue(resolver.contains("additionalEvBias = currentEvOffset"))
        assertFalse(resolver.contains("measuredIso = recentResult"))
        assertTrue(setExposure.contains("Keep the original fresh AE"))
        assertTrue(apply.contains("val explicitManual = requestedManualIso != null || requestedManualExposureNs != null"))
        assertTrue(apply.contains("if (!explicitManual) resolveProfileExposurePriorityPlan(characteristics) else null"))
    }
}
