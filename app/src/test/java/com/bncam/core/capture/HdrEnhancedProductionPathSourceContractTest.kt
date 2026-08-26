package com.bncam.core.capture

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HdrEnhancedProductionPathSourceContractTest {
    private val appDir: File = sequenceOf(File("."), File("app"))
        .firstOrNull { File(it, "src/main/java/com/bncam/core/engine/BnCameraManager.kt").isFile }
        ?: error("Cannot locate app module")

    @Test fun `production hdr enhanced dispatch is typed and dedicated`() {
        val authority = File(appDir, "src/main/java/com/bncam/core/capture/CaptureAuthorityResolver.kt").readText()
        val manager = File(appDir, "src/main/java/com/bncam/core/engine/BnCameraManager.kt").readText()
        assertTrue(authority.contains("computationalHdrRouteEnabled -> CaptureStrategy.HDR_ENHANCED"))
        assertTrue(manager.contains("CaptureStrategy.HDR_ENHANCED ->"))
        assertTrue(manager.contains("HdrEnhancedRunner(context, cameraManager)"))
        assertTrue(manager.contains("acquireDeliberateBurst = {"))
    }

    @Test fun `deliberate burst leaves repeating preview session intact`() {
        val manager = File(appDir, "src/main/java/com/bncam/core/engine/BnCameraManager.kt").readText()
        val acquisition = manager.substringAfter("private suspend fun acquireHdrEnhancedBurst(")
            .substringBefore("private suspend fun acquireComputationalHdrBracket(")
        assertTrue(acquisition.contains("submitBurstWithSharedProvenance("))
        assertTrue(manager.contains("session.captureBurst(requests, callback, handler)"))
        assertTrue(manager.contains("CameraRequestSubmissionType.BURST"))
        assertFalse(acquisition.contains("stopRepeating("))
        assertFalse(acquisition.contains("abortCaptures("))
        assertFalse(acquisition.contains("createCaptureSession("))
    }

    @Test fun `exact burst frames are leased while capture results arrive`() {
        val manager = File(appDir, "src/main/java/com/bncam/core/engine/BnCameraManager.kt").readText()
        val acquisition = manager.substringAfter("private suspend fun acquireHdrEnhancedBurst(")
            .substringBefore("private suspend fun acquireComputationalHdrBracket(")
        assertTrue(acquisition.contains("Channel<TotalCaptureResult?>"))
        assertTrue(acquisition.contains("launch(Dispatchers.IO)"))
        assertTrue(acquisition.contains("awaitAndLeaseExactRequestFrame("))
        assertTrue(acquisition.contains("captureSequenceId = submittedBurst.sequenceId"))
        assertTrue(acquisition.contains("captureFrameNumber = result.frameNumber"))
    }

    @Test fun `hdr authority does not report fake processing completion before backend`() {
        val runner = File(appDir, "src/main/java/com/bncam/core/runners/HdrEnhancedRunner.kt").readText()
        assertFalse(runner.contains("HDR_ENHANCED_ALIGNMENT_COMPLETE"))
        assertFalse(runner.contains("HDR_ENHANCED_TEMPORAL_MERGE_COMPLETE"))
        assertFalse(runner.contains("HDR_ENHANCED_ISP_COMPLETE"))
        assertFalse(runner.contains("HDR_ENHANCED_JPEG_COMPLETE"))
        assertTrue(runner.contains("processingBackend=MultiFrameRunner/VulkanRawMultiFrameBackend"))
    }
}
