package com.bncam.ui.screens.capture

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RawPreviewPhase1SourceContractTest {
    private val appDir: File = sequenceOf(File("."), File("app"))
        .firstOrNull { File(it, "src/main/cpp/CMakeLists.txt").isFile }
        ?: error("Cannot locate app module from ${File(".").absolutePath}")

    @Test
    fun fenceFailuresQuarantineGpuBackingButReturnCpuSlot() {
        val renderer = source("src/main/java/com/bncam/ui/screens/capture/RawPreviewRenderer.kt")
        assertTrue(renderer.contains("gl_fence_create_failed_gpu_backing_quarantined"))
        assertTrue(renderer.contains("gl_fence_poll_failed_gpu_backing_quarantined"))
        assertTrue(renderer.contains("returnOutputSlot(slot, \"gl_fence_create_failed_gpu_backing_quarantined\")"))
        assertTrue(renderer.contains("returnOutputSlot(slot, \"gl_fence_poll_failed_gpu_backing_quarantined\")"))
    }

    @Test
    fun rendererHasExplicitFiniteSlotOwnershipAndDropReasons() {
        val renderer = source("src/main/java/com/bncam/ui/screens/capture/RawPreviewRenderer.kt")
        val reasons = source("src/main/java/com/bncam/ui/screens/capture/RawPreviewDropReason.kt")
        assertTrue(renderer.contains("RawPreviewOutputSlotLedger(OUTPUT_SLOT_COUNT)"))
        assertTrue(renderer.contains("slotHealth={"))
        assertTrue(renderer.contains("cpuFallbackAvailable="))
        assertTrue(renderer.contains("generation=\${activeConfig?.pipelineGeneration ?: -1}"))
        assertTrue(renderer.contains("dropReasons={"))
        listOf(
            "INPUT_QUEUE_OVERFLOW",
            "NO_OUTPUT_SLOT",
            "STALE_GENERATION",
            "RETAIN_FAILED",
            "NATIVE_RENDER_FAILED",
            "GPU_OUTPUT_IMPORT_FAILED",
            "GL_INTEROP_FAILED",
            "GL_FENCE_CREATE_FAILED",
            "GL_FENCE_POLL_FAILED"
        ).forEach { assertTrue("missing $it", reasons.contains(it)) }
    }

    @Test
    fun queueRemainsBoundedLatestFrameWinsAndDuplicatesStayRejected() {
        val renderer = source("src/main/java/com/bncam/ui/screens/capture/RawPreviewRenderer.kt")
        assertTrue(renderer.contains("const val MAX_PENDING_REQUESTS = 1"))
        assertTrue(renderer.contains("private var pendingRequest: Request? = null"))
        assertTrue(renderer.contains("pendingRequest.also { pendingRequest = request }"))
        assertTrue(renderer.contains("RawPreviewDropReason.PREVIEW_DROP_REPLACED_BY_NEWER"))
        assertTrue(renderer.contains("latestOfferedSensorTimestampNs"))
        assertTrue(renderer.contains("sensorTimestampNs <= previous"))
        assertTrue(renderer.contains("duplicateOfferRejected"))
        assertTrue(renderer.contains("latestOfferedSensorTimestampNs.set(Long.MIN_VALUE)"))
        assertTrue(renderer.contains("clearPendingRequests()"))
        assertTrue(renderer.contains("RawPreviewDropReason.STALE_GENERATION"))
    }

    @Test
    fun staleGenerationCannotBecomeDisplayOwner() {
        val manager = source("src/main/java/com/bncam/core/engine/BnCameraManager.kt")
        val handoff = manager.substringAfter("private val rawPreviewRenderer = RawPreviewRenderer")
        assertTrue(handoff.contains("frame.pipelineGeneration != pipelineGeneration"))
        assertTrue(handoff.contains("frame.pipelineGeneration != targetViewfinderGeneration"))
        assertTrue(handoff.contains("frame.close()"))
        assertTrue(handoff.contains("return@rawPreviewFrame"))
    }

    @Test
    fun rendererConstructorPreservesExistingTrailingLambdaCallsite() {
        val renderer = source("src/main/java/com/bncam/ui/screens/capture/RawPreviewRenderer.kt")
        val manager = source("src/main/java/com/bncam/core/engine/BnCameraManager.kt")
        val fenceIndex = renderer.indexOf("private val fenceBackend: RawPreviewFenceBackend")
        val frameIndex = renderer.indexOf("private val onFrame: (RawPreviewFrame) -> Unit")
        assertTrue("fence backend must precede trailing onFrame lambda", fenceIndex >= 0 && frameIndex > fenceIndex)
        assertTrue(manager.contains("RawPreviewRenderer rawPreviewFrame@{ frame ->"))
    }

    @Test
    fun allFenceCallsUseInjectableBoundary() {
        val renderer = source("src/main/java/com/bncam/ui/screens/capture/RawPreviewRenderer.kt")
        val view = source("src/main/java/com/bncam/ui/screens/capture/FocusPeakingView.kt")
        assertTrue(renderer.contains("private val fenceBackend: RawPreviewFenceBackend"))
        assertTrue(renderer.contains("fenceBackend.pollFence(handle)"))
        assertTrue(renderer.contains("fenceBackend.destroyFence"))
        assertTrue(renderer.contains("createGlFence = fenceBackend::createFence"))
        assertTrue(view.contains("frame.closeAfterGlSampled()"))
        assertFalse(renderer.contains("ImageUtils.pollRawPreviewGlFence"))
        assertFalse(renderer.contains("ImageUtils.destroyRawPreviewGlFence"))
        assertFalse(view.contains("ImageUtils.createRawPreviewGlFence()"))
        assertFalse(view.contains("PlatformRawPreviewFenceBackend.createFence()"))
    }

    private fun source(relative: String): String = File(appDir, relative).readText()
}
