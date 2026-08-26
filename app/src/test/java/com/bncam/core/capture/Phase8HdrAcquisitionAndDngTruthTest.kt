package com.bncam.core.capture

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class Phase8HdrAcquisitionAndDngTruthTest {
    private val appDir: File = sequenceOf(File("."), File("app"))
        .firstOrNull { File(it, "src/main/java/com/bncam/core/engine/BnCameraManager.kt").isFile }
        ?: error("Cannot locate app module")

    @Test fun `hdr enhanced acquisition owns exact deliberate post shutter camera2 burst`() {
        val manager = File(appDir, "src/main/java/com/bncam/core/engine/BnCameraManager.kt").readText()
        val acquisition = manager.substringAfter("private suspend fun acquireHdrEnhancedBurst(")
            .substringBefore("private suspend fun acquireComputationalHdrBracket(")

        assertTrue(acquisition.contains("submitBurstWithSharedProvenance("))
        assertTrue(acquisition.contains("awaitAndLeaseExactRequestFrame("))
        assertTrue(acquisition.contains("ringBuffer.addMetadata("))
        assertTrue(acquisition.contains("CaptureResult.SENSOR_EXPOSURE_TIME"))
        assertTrue(acquisition.contains("CaptureResult.SENSOR_SENSITIVITY"))
        assertTrue(acquisition.contains("Channel<TotalCaptureResult?>"))
        assertTrue(acquisition.contains("captureFrameNumber = result.frameNumber"))
        assertFalse(acquisition.contains("stopRepeating("))
        assertFalse(acquisition.contains("createCaptureSession("))
    }

    @Test fun `hdr enhanced processing consumes exact acquired leases instead of near zsl reselection`() {
        val runner = File(appDir, "src/main/java/com/bncam/core/runners/MultiFrameRunner.kt").readText()
        assertTrue(runner.contains("hdrEnhancedOrderedFrames?.map { it.lease.pair }"))
        assertTrue(runner.contains("hdrEnhancedOrderedFrames?.map { it.lease }"))
        assertTrue(runner.contains("val hdrEnhancedActive = hdrEnhancedOrderedFrames != null"))
        assertTrue(runner.contains("val computationalHdrActive = hdrEnhancedActive || legacyBracketActive"))
    }

    @Test fun `raw hdr failure is anchor only rather than cpu pseudo hdr`() {
        val runner = File(appDir, "src/main/java/com/bncam/core/runners/MultiFrameRunner.kt").readText()
        assertTrue(runner.contains("Vulkan HDR RAW fusion failed; rendering the exact anchor only. No CPU HDR fallback was used."))
        assertTrue(runner.contains("frameCap = 1"))
        assertTrue(runner.contains("enableComputationalHdr = false"))
    }

    @Test fun `hdr jpeg and pristine dng masters cannot be shared`() {
        val runner = File(appDir, "src/main/java/com/bncam/core/runners/MultiFrameRunner.kt").readText()
        val factory = File(appDir, "src/main/java/com/bncam/core/capture/CaptureRecipeFactory.kt").readText()
        assertTrue(runner.contains("jpegFusionFrameCount == dngMasterFrameCount &&\n                                !hdrRequestedForShot"))
        assertTrue(factory.contains("if (computationalHdrRouteEnabled) return 1"))
        assertFalse(runner.contains("debugGroup = \"DNG Publication Master RAW\",\n                                    enableComputationalHdr = true"))
    }

    @Test fun `manager dispatches hdr enhanced through dedicated authority`() {
        val manager = File(appDir, "src/main/java/com/bncam/core/engine/BnCameraManager.kt").readText()
        val authority = File(appDir, "src/main/java/com/bncam/core/capture/CaptureAuthorityResolver.kt").readText()
        assertTrue(authority.contains("computationalHdrRouteEnabled -> CaptureStrategy.HDR_ENHANCED"))
        assertTrue(authority.contains("CaptureStrategy.HDR_ENHANCED -> \"HdrEnhancedRunner\""))
        assertTrue(manager.contains("CaptureStrategy.HDR_ENHANCED ->"))
        assertTrue(manager.contains("HdrEnhancedRunner(context, cameraManager)"))
        assertTrue(manager.contains("acquireDeliberateBurst = {"))
        assertTrue(manager.contains("acquireHdrEnhancedBurst("))
    }
}
