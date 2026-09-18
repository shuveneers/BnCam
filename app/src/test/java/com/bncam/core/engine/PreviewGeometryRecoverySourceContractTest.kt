package com.bncam.core.engine

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PreviewGeometryRecoverySourceContractTest {
    private fun appRoot(): File = sequenceOf(File("."), File("app"))
        .firstOrNull { File(it, "src/main/java/com/bncam/core/engine/BnCameraManager.kt").isFile }
        ?: error("Cannot locate app module from ${File(".").absolutePath}")

    private fun manager(): String =
        File(appRoot(), "src/main/java/com/bncam/core/engine/BnCameraManager.kt").readText()

    private fun focusView(): String =
        File(appRoot(), "src/main/java/com/bncam/ui/screens/capture/FocusPeakingView.kt").readText()

    @Test
    fun `preview rejection uses preview geometry recovery before returning failure`() {
        val source = manager()
        val branch = source.indexOf("configureFailureDiagnosis.kind == SessionOutputFailureKind.PREVIEW_OUTPUT_REJECTED")
        val genericFailure = source.indexOf("SESSION_CONFIGURE_FAILED", branch)
        assertTrue(branch >= 0)
        assertTrue(genericFailure > branch)
        val recovery = source.substring(branch, genericFailure)
        assertTrue(recovery.contains("PREVIEW_OUTPUT_CONFIGURE_FAILED"))
        assertTrue(recovery.contains("retryConfigureFailureWithPreviewGeometry("))
        assertFalse(recovery.contains("advanceStreamRuntimeFallback("))
    }

    @Test
    fun `failed session close acknowledgement precedes surface geometry mutation`() {
        val source = manager()
        val branch = source.indexOf("PREVIEW_OUTPUT_CONFIGURE_FAILED")
        val closeAwait = source.indexOf("awaitCaptureSessionClosed(failedSessionTicket)", branch)
        val retry = source.indexOf("retryConfigureFailureWithPreviewGeometry(", closeAwait)
        assertTrue(branch >= 0)
        assertTrue(closeAwait > branch)
        assertTrue(retry > closeAwait)
    }

    @Test
    fun `surface texture remains owned by FocusPeakingView`() {
        val managerSource = manager()
        val viewSource = focusView()
        assertTrue(managerSource.contains("FocusPeakingView.requestPreviewSurfaceBufferGeometry("))
        assertTrue(viewSource.contains("target.setDefaultBufferSize(width, height)"))
        val helperStart = managerSource.indexOf("private suspend fun applyPreviewGeometryRecoveryToSurface(")
        val helperEnd = managerSource.indexOf("private suspend fun retryConfigureFailureWithPreviewGeometry(", helperStart)
        val helper = managerSource.substring(helperStart, helperEnd)
        assertFalse(helper.contains("setDefaultBufferSize("))
    }

    @Test
    fun `preview recovery keeps capture format and capture geometry unchanged`() {
        val source = manager()
        val start = source.indexOf("private fun nextPreviewGeometryRecoveryCandidate(")
        val end = source.indexOf("private suspend fun applyPreviewGeometryRecoveryToSurface(", start)
        val helper = source.substring(start, end)
        assertTrue(helper.contains("captureFormat = identity.bufferFormat"))
        assertTrue(helper.contains("captureSize = android.util.Size(identity.width, identity.height)"))
        assertFalse(helper.contains("imageReader ="))
        assertFalse(helper.contains("activePipelineIdentity ="))
    }

    @Test
    fun `recovery is full fov bounded and HAL preflighted`() {
        val source = manager()
        assertTrue(source.contains("CameraStreamGeometryPolicy.fullFovCandidates("))
        assertTrue(source.contains("CameraSessionPreflight.validateRegularSession("))
        assertTrue(source.contains("PreviewGeometryRecoveryPolicy.MAX_ATTEMPTS_PER_GENERATION"))
        assertTrue(source.contains("StreamCandidateValidationStatus.SESSION_VALIDATED"))
    }
}
