package com.bncam.core.performance

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Source-level guardrails for the Phase 1A Single YUV lifecycle correction. */
class Phase1ASingleYuvLifecycleContractTest {
    private val appDir: File = sequenceOf(File("."), File("app"))
        .firstOrNull { File(it, "src/main/java/com/bncam/core/runners/SingleFrameRunner.kt").isFile }
        ?: error("Cannot locate app module")

    @Test
    fun singleYuvUsesDurableAsynchronousPublicationContract() {
        val runner = File(
            appDir,
            "src/main/java/com/bncam/core/runners/SingleFrameRunner.kt"
        ).readText()

        assertFalse(runner.contains("Single frame YUV produced no output URI."))
        assertTrue(runner.contains("val yuvReservation = CaptureProcessingQueue.tryReserve"))
        assertTrue(runner.contains("val yuvAnchorLease = requireNotNull(anchorLease)"))
        assertTrue(runner.contains("val yuvSubmitted = CaptureProcessingQueue.submit"))
        assertTrue(runner.contains("anchorLease = null"))
        assertTrue(runner.contains("sourceFrameOwner=PROCESSING_QUEUE"))
        assertTrue(runner.contains("CaptureSubmissionResult.Submitted"))
    }

    @Test
    fun yuvHardwareBufferLifetimeBelongsToProcessingWorkerAfterSubmission() {
        val runner = File(
            appDir,
            "src/main/java/com/bncam/core/runners/SingleFrameRunner.kt"
        ).readText()

        val yuvSection = runner.substringAfter(
            "val yuvReservation = CaptureProcessingQueue.tryReserve"
        )
        val submitSection = yuvSection.substringBefore("return@withContext")
        assertTrue(submitSection.contains("finally {"))
        assertTrue(submitSection.contains("yuvAnchorLease.release()"))
        assertTrue(submitSection.contains("anchorLease = null"))
        assertTrue(runner.contains("if (!yuvWorkHandedOff)"))
        assertTrue(runner.contains("anchorLease?.release()"))
        assertFalse(runner.contains("candidateFramesOwnershipTransferred"))
    }

    @Test
    fun captureExecutionIsNotOwnedByForgottenComposeScope() {
        val screen = File(
            appDir,
            "src/main/java/com/bncam/ui/screens/capture/CameraScreen.kt"
        ).readText()

        val trigger = screen.substringAfter("val triggerCaptureSequence =")
            .substringBefore("// EVENT BUS LISTENER")
        assertTrue(screen.contains("lifecycleOwner.lifecycleScope"))
        assertTrue(trigger.contains("captureScope.launch"))
        assertFalse(trigger.contains("coroutineScope.launch"))
        assertTrue(trigger.contains("CancellationException"))
        assertTrue(trigger.contains("Capture failed without terminating the camera UI"))
    }
}
