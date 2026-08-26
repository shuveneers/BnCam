package com.bncam.core.performance

import java.io.File
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class Phase9PreviewCapturePrioritySourceContractTest {
    private val appDir = generateSequence(File(System.getProperty("user.dir"))) { it.parentFile }
        .firstOrNull { File(it, "src/main/java/com/bncam/ui/screens/capture/RawPreviewRenderer.kt").isFile }
        ?: error("Unable to locate app module")

    private fun source(path: String) = File(appDir, path).readText()

    @Test
    fun `raw preview remains offerable while still capture is active`() {
        val renderer = source("src/main/java/com/bncam/ui/screens/capture/RawPreviewRenderer.kt")
        assertFalse(renderer.contains("capturePriorityOwnerId"))
        assertFalse(renderer.contains("capturePrioritySuppressedOffers"))
        assertFalse(renderer.contains("droppedPending = pendingRequest.getAndSet(null)"))
        assertTrue(renderer.contains("val replaced = pendingRequest.getAndSet(request)"))
        assertTrue(renderer.contains("scheduleDrain()"))
        assertFalse(renderer.contains("PREVIEW_MAX_WIDTH / 2"))
        assertFalse(renderer.contains("PREVIEW_MAX_HEIGHT / 2"))
    }

    @Test
    fun `capture lifecycle never suppresses raw preview`() {
        val manager = source("src/main/java/com/bncam/core/engine/BnCameraManager.kt")
        assertFalse(manager.contains("rawPreviewRenderer.beginCapturePriority"))
        assertFalse(manager.contains("rawPreviewRenderer.endCapturePriority"))
        assertFalse(manager.contains("rawPreviewRenderer.clearCapturePriority"))
        assertTrue(manager.contains("capturePreviewContinuityTracker.begin("))
        assertTrue(manager.contains("RAW CAPTURE PREVIEW CONTINUITY"))
        assertTrue(manager.contains("reconcileCaptureWorkSnapshot"))
    }
}
