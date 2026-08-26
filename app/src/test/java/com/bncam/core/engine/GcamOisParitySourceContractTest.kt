package com.bncam.core.engine

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GcamOisParitySourceContractTest {
    private fun source(path: String): String = File(path).readText()

    @Test
    fun hiddenPhysicalOisUsesSelectedLensCapabilityAndLogicalRequest() {
        val policy = source("app/src/main/java/com/bncam/core/engine/OisRoutePolicy.kt")
        val resolver = source("app/src/main/java/com/bncam/core/engine/OisResolver.kt")
        val manager = source("app/src/main/java/com/bncam/core/engine/BnCameraManager.kt")

        assertTrue(policy.contains("if (hiddenPhysicalRoute)"))
        assertTrue(policy.contains("return Route.HIDDEN_STANDARD"))
        assertTrue(resolver.contains("under-reported physical OIS capability"))
        assertTrue(resolver.contains("verifiedPhysicalCameraId"))
        assertTrue(manager.contains("activePipelineIdentity?.physicalCameraId"))
        assertTrue(manager.contains("CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE_ON"))
        assertTrue(manager.contains("CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_OFF"))
    }


    @Test
    fun everySubmissionReassertsMechanicalOisAfterAfAeAndVendorMutations() {
        val manager = source("app/src/main/java/com/bncam/core/engine/BnCameraManager.kt")
        assertTrue(manager.contains("enforceOpticalStabilizationBeforeSubmission(builder, reason)"))
        assertTrue(manager.contains("OIS_SUBMISSION_ENFORCED"))
        assertTrue(manager.contains("CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE_ON"))
        assertTrue(manager.contains("CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_OFF"))
    }

    @Test
    fun requestProvenanceRecordsOisAndEisTruth() {
        val provenance = source("app/src/main/java/com/bncam/core/capture/ControlRequestProvenance.kt")
        assertTrue(provenance.contains("lensOpticalStabilizationMode="))
        assertTrue(provenance.contains("videoStabilizationMode="))
    }

    @Test
    fun failedDigitalDampingExperimentIsNotInProductionViewfinder() {
        val cameraScreen = source("app/src/main/java/com/bncam/ui/screens/capture/CameraScreen.kt")
        val peaking = source("app/src/main/java/com/bncam/ui/screens/capture/FocusPeakingView.kt")
        val settings = source("app/src/main/java/com/bncam/ui/screens/settings/ViewfinderScreen.kt")

        assertFalse(cameraScreen.contains("ViewfinderDamping"))
        assertFalse(cameraScreen.contains("DAMPING_FRAME"))
        assertFalse(peaking.contains("viewfinderDamping"))
        assertFalse(settings.contains("Viewfinder damping"))
    }
}
