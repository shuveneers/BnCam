package com.bncam.ui.screens.capture

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RawPreviewArchitectureContractTest {
    private val appDir: File = sequenceOf(File("."), File("app"))
        .firstOrNull { File(it, "src/main/cpp/CMakeLists.txt").isFile }
        ?: error("Cannot locate app module from ${File(".").absolutePath}")

    @Test
    fun rawPreviewBorrowsTheAuthoritativeWarmBufferAndAcquiresNativeOwnership() {
        val manager = source("src/main/java/com/bncam/core/engine/BnCameraManager.kt")
        val ring = source("src/main/java/com/bncam/core/buffer/FrameRingBuffer.kt")
        val native = source("src/main/cpp/native-lib.cpp")

        assertTrue(ring.contains("withBorrowedCompleteFrame"))
        assertTrue(ring.contains("withBorrowedImageFrame"))
        assertTrue(manager.contains("withBorrowedImageFrame"))
        assertFalse(manager.contains("offerRawPreviewForTimestamp"))
        assertTrue(native.contains("AHardwareBuffer_acquire"))
        assertTrue(native.contains("retainRawPreviewHardwareBufferNative"))
        assertTrue(native.contains("releaseRawPreviewHardwareBufferNative"))
        assertFalse(manager.contains("rawPreviewImageReader"))
        assertFalse(manager.contains("previewRawImageReader"))
    }

    @Test
    fun previewIsLatestFrameWinsDownscaledAndDoesNotRunCaptureJpeg() {
        val renderer = source("src/main/java/com/bncam/ui/screens/capture/RawPreviewRenderer.kt")
        val previewNative = source("src/main/cpp/RawPreview.cpp")

        assertTrue(renderer.contains("pendingRequest.getAndSet"))
        assertTrue(renderer.contains("latestOfferedSensorTimestampNs"))
        assertTrue(renderer.contains("duplicateOfferRejected"))
        assertFalse(renderer.contains("MIN_FRAME_INTERVAL_MS"))
        assertTrue(renderer.contains("RAW_PREVIEW_DROPPED_BUSY"))
        assertTrue(previewNative.contains("PREVIEW_MAX_WIDTH"))
        assertTrue(previewNative.contains("PREVIEW_MAX_HEIGHT"))
        assertTrue(previewNative.contains("executeSpectraResidentDemosaic"))
        assertTrue(previewNative.contains("executeSpectraResidentAwbCcm"))
        assertTrue(previewNative.contains("PREVIEW_MAX_WIDTH = 1024"))
        assertTrue(previewNative.contains("vulkanRgb = std::move(color.outputRgb)"))
        assertFalse(previewNative.contains("wrapped.clone()"))
        assertFalse(previewNative.contains("cv::rotate"))
        assertFalse(previewNative.contains("renderRawBaselineJpeg"))
        assertFalse(previewNative.contains("imencode"))
    }

    @Test
    fun uniqueSensorFramesAreSubmittedImmediatelyAndDriveAnAdaptiveSurfaceRate() {
        val manager = source("src/main/java/com/bncam/core/engine/BnCameraManager.kt")
        val view = source("src/main/java/com/bncam/ui/screens/capture/FocusPeakingView.kt")

        assertTrue(manager.contains("The Image callback is the single owner of live-preview submission"))
        assertTrue(view.contains("RawSourceFrameRateEstimator"))
        assertTrue(view.contains("Submitting as soon as Vulkan completes"))
        assertFalse(view.contains("Choreographer.getInstance().postFrameCallback"))
        assertTrue(view.contains("FRAME_RATE_COMPATIBILITY_FIXED_SOURCE"))
        assertTrue(view.contains("getRawPreviewEglDisplayPresentTime"))
    }

    @Test
    fun displayedRawTextureRemainsTheSnapshotSourceAndSettingIsNotAPipelineKey() {
        val camera = source("src/main/java/com/bncam/ui/screens/capture/CameraScreen.kt")
        val view = source("src/main/java/com/bncam/ui/screens/capture/FocusPeakingView.kt")

        assertTrue(camera.contains("viewfinderStreamFlow"))
        assertTrue(camera.contains("configureViewfinderStream"))
        assertTrue(view.contains("GL_TEXTURE_2D"))
        assertTrue(view.contains("glTexSubImage2D"))
        assertTrue(view.contains("RawPreviewTextureTransform.coordinates"))
        assertTrue(view.contains("captureSnapshot"))
        val pipelineKeyBlock = camera.substringAfter("val requestedPipelineKey =").substringBefore("if (requestedPipelineKey")
        assertFalse(pipelineKeyBlock.contains("viewfinderStream"))
    }

    @Test
    fun rawDisplayZoomIsAppliedOnlyInTheRawTextureCoordinatePath() {
        val camera = source("src/main/java/com/bncam/ui/screens/capture/CameraScreen.kt")
        val view = source("src/main/java/com/bncam/ui/screens/capture/FocusPeakingView.kt")

        assertTrue(view.contains("fun setRawDisplayZoom(zoom: Float)"))
        assertTrue(camera.contains("digitalZoom = { currentZoomLevelState.floatValue }"))
        assertTrue(camera.contains("view.setRawDisplayZoom(digitalZoom())"))
        assertTrue(view.contains("digitalZoom = rawDisplayZoom"))
        assertTrue(view.contains("if (useRaw) {\n                RawPreviewTextureTransform.coordinates"))
        assertFalse(view.contains("yuvTextureCoordinates(yuvOrientationCorrectionDegrees, rawDisplayZoom"))
    }

    @Test
    fun selectedBufferPrimesRawPreviewFromTheExistingWarmGeneration() {
        val manager = source("src/main/java/com/bncam/core/engine/BnCameraManager.kt")

        assertTrue(manager.contains("primeRawViewfinderFromWarmBuffer"))
        assertTrue(manager.contains("ringBuffer.latestImageTimestamp(generation)"))
        assertTrue(manager.contains("RAW_VIEWFINDER_WARM_PRIME"))
        assertTrue(manager.contains("rawPreviewRenderer.offerBorrowedHardwareBuffer"))
        assertFalse(manager.contains("RAW_VIEWFINDER_WARM_PRIME_FORCE_CAPTURE"))
    }


    @Test
    fun selectedBufferKeepsCanonicalWarmRawUntilCustomInputIsProvenAndRetainsCalibrationAcrossDisplaySwitches() {
        val manager = source("src/main/java/com/bncam/core/engine/BnCameraManager.kt")

        assertTrue(manager.contains("customRawPreviewInputReadyGeneration"))
        assertTrue(manager.contains("if (customInputProven) return"))
        assertTrue(manager.contains("preserveResidentRawConfig = canPreserveResidentRawConfig"))
        assertTrue(manager.contains("val keepResidentRawConfig = preserveResidentRawConfig"))
        assertTrue(manager.contains("targetViewfinderSource != source || targetViewfinderGeneration != generation"))
        assertFalse(manager.contains("if (customRawPreviewBinding?.generation == generation && customRawPreviewReader != null) return"))
    }

    @Test
    fun rawPreviewBootstrapsBeforeAsyncProfileRefinementAndReportsFailures() {
        val manager = source("src/main/java/com/bncam/core/engine/BnCameraManager.kt")
        val renderer = source("src/main/java/com/bncam/ui/screens/capture/RawPreviewRenderer.kt")

        val bootstrap = manager.indexOf("buildBootstrapRawPreviewConfig(")
        val refinement = manager.indexOf("rawPreviewScope.launch", bootstrap)
        assertTrue(bootstrap >= 0)
        assertTrue(refinement > bootstrap)
        assertTrue(manager.contains("RAW_PREVIEW_BOOTSTRAP"))
        assertTrue(renderer.contains("retainFailures"))
        assertTrue(renderer.contains("renderFailures"))
    }

    @Test
    fun rawSensorUsesDevelopedAuthorityAndRatioPreservingHighlightHeadroom() {
        val manager = source("src/main/java/com/bncam/core/engine/BnCameraManager.kt")
        val previewNative = source("src/main/cpp/RawPreview.cpp")

        assertTrue(manager.contains("RawPreviewCalibrationTransform.developedLevelsInSourceDomain"))
        assertTrue(manager.contains("RAW_PREVIEW_WHITE_AUTHORITY"))
        assertTrue(previewNative.contains("const float commonScale = 1.0f / maximumChannel"))
        assertTrue(previewNative.contains("r *= commonScale"))
        assertTrue(previewNative.contains("g *= commonScale"))
        assertTrue(previewNative.contains("b *= commonScale"))
    }

    @Test
    fun lensSwitchKeepsPersistentGlEndpointUntilTargetGenerationIsDisplayReady() {
        val camera = source("src/main/java/com/bncam/ui/screens/capture/CameraScreen.kt")
        val view = source("src/main/java/com/bncam/ui/screens/capture/FocusPeakingView.kt")
        val manager = source("src/main/java/com/bncam/core/engine/BnCameraManager.kt")

        assertTrue(camera.contains("val previewSurfaceOwnerKey = \"persistent_camera_viewfinder\""))
        assertTrue(camera.contains("remember(previewSurfaceOwnerKey) { mutableStateOf<SurfaceTexture?>(null) }"))
        assertFalse(camera.contains("remember(cameraId) { mutableStateOf<SurfaceTexture?>(null) }"))
        assertFalse(camera.contains("key(cameraId)"))
        val transition = camera.substringAfter("// 1B. CAMERA TARGET TRANSITION").substringBefore("// 2. SOFT RESET")
        assertFalse(transition.contains("clearPreviewForCameraTransition()"))
        assertTrue(manager.contains("FIRST_VALID_RAW_VULKAN_FRAME"))
        assertTrue(manager.contains("FIRST_VALID_YUV_SURFACE_FRAME"))
        assertTrue(view.contains("surfaceTexture?.updateTexImage()"))
        assertTrue(view.contains("onYuvFrameAvailable?.invoke(surfaceTimestampNs)"))
    }

    private fun source(relative: String): String = File(appDir, relative).readText()
}
