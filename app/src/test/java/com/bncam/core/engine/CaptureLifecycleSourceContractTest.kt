package com.bncam.core.engine

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CaptureLifecycleSourceContractTest {
    private val appDir: File = sequenceOf(File("."), File("app"))
        .firstOrNull { File(it, "src/main/java/com/bncam/core/engine/BnCameraManager.kt").isFile }
        ?: error("Cannot locate app module")

    @Test
    fun requestOnlyChangesDoNotInvalidateSessionGeneration() {
        val source = File(appDir, "src/main/java/com/bncam/core/engine/BnCameraManager.kt").readText()
        val metering = source.substringAfter("fun setMeteringStyle").substringBefore("fun updatePreviewRepeatingRequest")
        val tapFocus = source.substringAfter("fun tapToFocusAt").substringBefore("fun setManualFocus")
        assertFalse(metering.contains("FrameGenerationId.increment"))
        assertFalse(tapFocus.contains("FrameGenerationId.increment"))
        assertTrue(source.contains("private fun finishCaptureAttempt"))
        assertTrue(source.contains("captureAttempts.finish"))
    }

    @Test
    fun camera2SubmissionsAndSelectedFrameMeteringUseImmutableProvenance() {
        val manager =
            File(appDir, "src/main/java/com/bncam/core/engine/BnCameraManager.kt")
                .readText()
        val ring =
            File(appDir, "src/main/java/com/bncam/core/buffer/FrameRingBuffer.kt")
                .readText()
        val single =
            File(appDir, "src/main/java/com/bncam/core/runners/SingleFrameRunner.kt")
                .readText()
        val directRepeatingSubmissions =
            Regex("""\.setRepeatingRequest\(""").findAll(manager).count()
        val directOneShotSubmissions =
            Regex("""\.capture\(""").findAll(manager).count()

        assertEquals(1, directRepeatingSubmissions)
        assertEquals(1, directOneShotSubmissions)
        assertTrue(manager.contains("builder.setTag(prepared.tag)"))
        assertTrue(manager.contains("controlRequestEpochTracker.commit(prepared)"))
        assertTrue(manager.contains("tag = request.tag"))
        assertTrue(manager.contains("requestProvenance ="))
        assertTrue(ring.contains("provenance.snapshot.identity == provenance.identity"))
        assertTrue(single.contains("SelectedFrameProvenanceValidator.verify"))
        assertTrue(single.contains("selectedRequestSnapshot?.state?.aeRegions"))
        assertTrue(single.contains("MeteringExactTruth.canReportAppliedExact"))
        assertFalse(single.contains("requestedAeRegions: Array"))
        assertFalse(
            single.contains(
                "currentCaptureRequest?.get(CaptureRequest.CONTROL_AE_REGIONS)"
            )
        )
    }

    @Test
    fun singleFrameMotionFreshnessAndReadinessHaveIndependentTruthSources() {
        val manager =
            File(appDir, "src/main/java/com/bncam/core/engine/BnCameraManager.kt")
                .readText()
        val runner =
            File(appDir, "src/main/java/com/bncam/core/runners/SingleFrameRunner.kt")
                .readText()
        val motionPolicy =
            File(
                appDir,
                "src/main/java/com/bncam/core/runners/SingleFrameSelectionPolicy.kt"
            ).readText()
        val readinessPolicy =
            File(
                appDir,
                "src/main/java/com/bncam/core/capture/WarmBufferReadinessPolicy.kt"
            ).readText()

        val motionScorer = motionPolicy.substringAfter("object FrameMotionScorer")
        assertFalse(motionScorer.contains("sharpness"))
        assertFalse(runner.contains("sharpnessBase * (1.0 - exposureRisk)"))
        assertTrue(runner.contains("FrameMotionScorer.score"))
        assertTrue(runner.contains("GENUINE_NEAR_ZSL_WINDOW_MS"))
        assertTrue(manager.contains("WarmBufferReadinessPolicy.captureRoute"))
        assertTrue(manager.contains("WarmBufferReadinessPolicy.streamHealth"))
        assertTrue(readinessPolicy.contains("STREAM_HEALTH"))
        assertTrue(readinessPolicy.contains("CAPTURE_ROUTE_"))
        assertFalse(manager.contains("val requiredFreshFrames = if"))
    }

    @Test
    fun hardwareBufferLifetimeIsBoundToOwnedOpenImage() {
        val manager = File(appDir, "src/main/java/com/bncam/core/engine/BnCameraManager.kt").readText()
        val ring = File(appDir, "src/main/java/com/bncam/core/buffer/FrameRingBuffer.kt").readText()
        val collector = File(appDir, "src/main/java/com/bncam/core/capture/ShutterCandidateCollector.kt").readText()
        val runner = File(appDir, "src/main/java/com/bncam/core/runners/SingleFrameRunner.kt").readText()
        val addImageBody = ring.substringAfter("fun addImage").substringBefore("fun addMetadata")
        assertFalse(addImageBody.contains("image.close()"))
        assertTrue(ring.contains("var image: Image? = null"))
        assertTrue(addImageBody.contains("pair.image = image"))
        assertTrue(addImageBody.contains("return true"))
        assertTrue(manager.contains("val imageOwner = CloseOnce { image.close() }"))
        assertTrue(manager.contains("if (!transferredToRing)"))
        assertTrue(ring.contains("try { image?.close()"))
        assertTrue(collector.contains("imageTimestamp != frame.timestamp"))
        assertTrue(collector.contains("metadataTimestamp != imageTimestamp"))
        assertTrue(runner.contains("selectedImageTimestampNs != selectedFrameTimestampNs"))
        assertTrue(runner.contains("selectedMetadataTimestampNs != selectedFrameTimestampNs"))
    }

    @Test
    fun asynchronousQrAnalysisDoesNotRetainRingOwnedImage() {
        val manager = File(appDir, "src/main/java/com/bncam/core/engine/BnCameraManager.kt").readText()
        val scanQrCode = manager.substringAfter("private fun scanQrCode")
            .substringBefore("private fun trackObjectsInFrame")
        assertTrue(scanQrCode.contains("ImageUtils.yuv420ToNv21(image)"))
        assertTrue(scanQrCode.contains("InputImage.fromByteArray"))
        assertFalse(scanQrCode.contains("InputImage.fromMediaImage"))
        assertTrue(scanQrCode.contains("scanner.close()"))
    }

    @Test
    fun startupAndResetNeverUseSyntheticOrStaleYuvReader() {
        val manager = File(appDir, "src/main/java/com/bncam/core/engine/BnCameraManager.kt").readText()
        val screen = File(appDir, "src/main/java/com/bncam/ui/screens/capture/CameraScreen.kt").readText()
        assertTrue(screen.contains("collectAsState(initial = null)"))
        assertTrue(screen.contains("val startupProfileId = persistedProfileId ?: latestActiveProfile.value.id"))
        assertTrue(screen.contains("getProfileFrameSourceFlow(startupProfileId).first()"))
        assertFalse(screen.contains("getProfileFrameSourceFlow(activeProfile.id).collectAsState(initial = \"YUV\")"))
        assertFalse(manager.contains("ImageReader REUSE:"))
        assertTrue(manager.contains("imageReaderMaxImages(requestedIdentity.maxImages)"))
        assertTrue(manager.contains("expectedFormat = requestedIdentity.bufferFormat"))
        assertTrue(manager.contains("BUFFER_FRAME_FORMAT_MISMATCH"))
    }

    @Test
    fun singleRawProcessingAndSaveAreDetachedWhileMultiFailureIsolationRemains() {
        val single = File(appDir, "src/main/java/com/bncam/core/runners/SingleFrameRunner.kt").readText()
        val multi = File(appDir, "src/main/java/com/bncam/core/runners/MultiFrameRunner.kt").readText()
        val processingQueue = File(appDir, "src/main/java/com/bncam/core/output/CaptureProcessingQueue.kt").readText()
        val shotLogger = File(appDir, "src/main/java/com/bncam/core/debug/ShotLogger.kt").readText()
        assertTrue(single.contains("JPEG_RENDER_FAILED_CONTINUING_DNG"))
        assertTrue(single.contains("JPEG_RESERVATION_FAILED_CONTINUING_DNG"))
        assertTrue(single.contains("JPEG_SAVE_FAILED_CONTINUING_DNG"))
        assertTrue(single.contains("if (jpegPublicUri == null)"))
        assertTrue(single.contains("PublicationPolicyResolver.resolveUris"))
        assertTrue(single.contains("CaptureProcessingQueue.tryReserve"))
        assertTrue(single.contains("CaptureProcessingQueue.submit"))
        assertTrue(single.contains("rawMaterializationDispatcher"))
        assertTrue(single.contains("originalHardwareBufferReleased=true"))
        assertTrue(single.contains("forkForDeferredWork"))
        assertFalse(single.contains("CaptureSaveQueue.enqueueAndAwait"))
        assertTrue(shotLogger.contains("fun forkForDeferredWork()"))
        assertTrue(multi.contains("JPEG_RESERVATION_FAILED_CONTINUING_DNG"))
        assertTrue(multi.contains("DNG_RESERVATION_FAILED_CONTINUING_JPEG"))
        assertTrue(multi.contains("JPEG_SAVE_FAILED_CONTINUING_DNG"))
        assertTrue(multi.contains("DNG_SAVE_FAILED_CONTINUING_JPEG"))
        assertTrue(multi.contains("if (!jpegSaved && !dngSaved)"))
        assertTrue(multi.contains("CaptureSaveQueue.enqueueAndAwait"))
        assertTrue(processingQueue.contains("job.reservation.markProcessing()"))
        assertFalse(multi.contains("workReservation.markProcessing()"))
        assertTrue(
            multi.contains(
                "orientation = androidx.exifinterface.media.ExifInterface.ORIENTATION_NORMAL"
            )
        )
        assertTrue(
            multi.contains(
                "orientation = exifOrientation,\n" +
                    "                                dngMergeStats = dngMergeStats"
            )
        )
    }
}
