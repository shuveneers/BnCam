package com.bncam.core.engine

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class RawWarmBufferResolutionContractTest {
    private fun appRoot(): File = sequenceOf(File("."), File("app"))
        .firstOrNull { File(it, "src/main/java/com/bncam/core/engine/BnCameraManager.kt").isFile }
        ?: error("Cannot locate app module")

    private fun source(relative: String): String = File(appRoot(), relative).readText()

    @Test
    fun `RAW warm producer uses a still quality capture contract`() {
        val manager = source("src/main/java/com/bncam/core/engine/BnCameraManager.kt")
        val resolver = manager.substringAfter("private fun resolveWarmRepeatingRequestTemplate")
            .substringBefore("private fun createCaptureSession")
        val session = manager.substringAfter("private fun createCaptureSession")
            .substringBefore("private fun submitCaptureRequest")

        assertTrue(resolver.contains("CameraDevice.TEMPLATE_ZERO_SHUTTER_LAG"))
        assertTrue(resolver.contains("CaptureRequest.CONTROL_CAPTURE_INTENT_ZERO_SHUTTER_LAG"))
        assertTrue(resolver.contains("CameraDevice.TEMPLATE_STILL_CAPTURE"))
        assertTrue(resolver.contains("CaptureRequest.CONTROL_CAPTURE_INTENT_STILL_CAPTURE"))
        assertTrue(session.contains("camera.createCaptureRequest(warmTemplate.template)"))
        assertTrue(session.contains("CONTROL_CAPTURE_INTENT, warmTemplate.captureIntent"))
    }

    @Test
    fun `RAW cadence is bounded by selected full resolution stream duration before YUV 60 fps policy`() {
        val manager = source("src/main/java/com/bncam/core/engine/BnCameraManager.kt")
        val fpsPolicy = manager.substringAfter("private fun applyOptimalAeTargetFpsRange")
            .substringBefore("private fun submitRepeatingRequestWithProvenance")

        assertTrue(fpsPolicy.contains("if (isRawActive && identity != null)"))
        assertTrue(fpsPolicy.contains("getOutputMinFrameDuration(identity.bufferFormat, selectedSize)"))
        assertTrue(fpsPolicy.contains("strategy=RAW_STREAM_MIN_FRAME_DURATION"))
        assertTrue(
            fpsPolicy.indexOf("if (isRawActive && identity != null)") <
                fpsPolicy.indexOf("val variable60Range")
        )
    }


    @Test
    fun `RAW pipeline keeps the largest standard sensor aspect output as its canonical stream`() {
        val manager = source("src/main/java/com/bncam/core/engine/BnCameraManager.kt")
        val identity = manager.substringAfter("private suspend fun buildPipelineIdentity")
            .substringBefore("private fun decidePipelineReset")

        assertTrue(identity.contains("val standardSizes = standardMap?.getOutputSizes(effectiveFormat)"))
        assertTrue(identity.contains("val selectedRawExtents = standardFullFov.ifEmpty"))
        assertTrue(identity.contains("availableSizes = selectByExtent(standardSizes, selectedRawExtents)"))
        assertTrue(identity.contains("maxByOrNull { it.width.toLong() * it.height.toLong() }"))
        assertTrue(identity.contains("STANDARD_RAW_FULL_FOV"))
    }

    @Test
    fun `producer reset rebuilds and refines RAW runtime profile for the new generation`() {
        val manager = source("src/main/java/com/bncam/core/engine/BnCameraManager.kt")
        val reset = manager.substringAfter("val resetGeneration = pipelineGeneration")
            .substringBefore("private data class WarmRepeatingRequestTemplate")

        assertTrue(reset.contains("RawPipelineRuntimeProfileFactory.buildAndPublish"))
        assertTrue(reset.contains("sessionGeneration = resetGeneration"))
        assertTrue(reset.contains("RawPipelineRuntimeProfileFactory.refinePublishedLayoutFromImage"))
    }

    @Test
    fun `RAW preview uses runtime geometry only when generation format and dimensions all match`() {
        val renderer = source("src/main/java/com/bncam/ui/screens/capture/RawPreviewRenderer.kt")

        assertTrue(renderer.contains("runtimeGeometryMatchesRequest"))
        assertTrue(renderer.contains("runtimeProfile.sessionGeneration == request.config.pipelineGeneration"))
        assertTrue(renderer.contains("runtimeProfile.format == request.config.source.imageFormat"))
        assertTrue(renderer.contains("activeGeometry.bufferWidth == request.sourceWidth"))
        assertTrue(renderer.contains("activeGeometry.bufferHeight == request.sourceHeight"))
        assertTrue(renderer.contains("val requestGeometry = if (runtimeGeometryMatchesRequest) activeGeometry else null"))
    }
}
