package com.bncam.ui.screens.capture

import java.io.File
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class Phase7CaptureFeedbackContractTest {
    private fun source(path: String): String {
        val roots = listOf(File("."), File("app"))
        return roots.asSequence().map { File(it, path) }.firstOrNull { it.isFile }?.readText()
            ?: error("Missing source file: $path")
    }

    @Test
    fun viewfinderDoesNotAnimateSyntheticQueueProgress() {
        val camera = source("src/main/java/com/bncam/ui/screens/capture/CameraScreen.kt")
        assertTrue(camera.contains("CaptureThumbnailFeedback("))
        assertFalse(camera.contains("latestSnapshot?.progress"))
        assertFalse(camera.contains("currentProgress"))
        assertFalse(camera.contains("thumbScaleTrigger"))
    }

    @Test
    fun feedbackIsDrivenByRealQueueStatesAndPublicationSequence() {
        val feedback = source("src/main/java/com/bncam/ui/screens/capture/CaptureThumbnailFeedback.kt")
        listOf("QUEUED", "PROCESSING", "SAVING", "PUBLISHED", "FAILED").forEach { state ->
            assertTrue(feedback.contains("CaptureWorkState.$state"), "Missing queue state $state")
        }
        assertTrue(feedback.contains("current.shotSequenceId != lastAnimatedPublishedSequence"))
        assertTrue(feedback.contains("it.jpegUri ?: it.thumbnailUri"))
        assertTrue(feedback.contains("immediateShutterStartedNs > snapshotStartedNs"))
        assertTrue(feedback.contains("publishedCaptureStartedNs == snapshotStartedNs"))
        assertTrue(feedback.contains("if (feedback.busy)"))
        assertTrue(feedback.contains("rememberInfiniteTransition"))
        assertFalse(feedback.contains("estimatedTotalMs"))
        assertFalse(feedback.contains("progress.coerce"))
    }
}
