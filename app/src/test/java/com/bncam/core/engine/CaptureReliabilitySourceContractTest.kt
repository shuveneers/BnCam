package com.bncam.core.engine

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CaptureReliabilitySourceContractTest {
    private fun appRoot(): File = sequenceOf(File("."), File("app"))
        .firstOrNull { File(it, "src/main/java/com/bncam/core/engine/BnCameraManager.kt").isFile }
        ?: error("Cannot locate app module")

    private fun source(relative: String): String = File(appRoot(), relative).readText()

    @Test
    fun `profile-only changes do not become capture-time session reset authority`() {
        val manager = source("src/main/java/com/bncam/core/engine/BnCameraManager.kt")
        val readiness = manager.substringAfter("private fun pipelineReadinessReason")
            .substringBefore("private suspend fun waitForPipelineReadyForCapture")
        val skippedReset = manager.substringAfter("if (!decision.required) {")
            .substringBefore("val rawPreviewSessionOnlyChange")

        assertFalse(readiness.contains("PROFILE_MISMATCH"))
        assertTrue(skippedReset.contains("activePipelineIdentity = requestedIdentity"))
        assertTrue(skippedReset.indexOf("activePipelineIdentity = requestedIdentity") <
            skippedReset.indexOf("updatePreviewRepeatingRequest()"))
    }

    @Test
    fun `capture readiness never waits for OIS result echo`() {
        val manager = source("src/main/java/com/bncam/core/engine/BnCameraManager.kt")
        val gate = manager.substringAfter("private suspend fun waitForPipelineReadyForCapture")
            .substringBefore("private fun stopWarmBufferWatchdog")

        assertTrue(gate.contains("OIS result echo is quality evidence, not shutter admission authority"))
        assertTrue(gate.contains("capture proceeds in degraded-ready mode"))
        assertFalse(gate.contains("Waiting for stabilization"))
    }

    @Test
    fun `multiframe candidate selection and leasing are atomic`() {
        val runner = source("src/main/java/com/bncam/core/runners/MultiFrameRunner.kt")
        val selection = runner.substringAfter("val normalSelectionCount")
            .substringBefore("val hdrExposureScales")

        assertTrue(selection.contains("queryAndLeaseCandidates("))
        assertFalse(selection.contains("queryCandidates("))
        assertFalse(selection.contains("leaseFrames(burstFrames)"))
    }

    @Test
    fun `execute capture has defensive acquisition finalization`() {
        val manager = source("src/main/java/com/bncam/core/engine/BnCameraManager.kt")
        val capture = manager.substringAfter("suspend fun executeCapture(")
            .substringBefore("// ========================================================\n    // HARDWARE CONFIG TO NATIVE BRIDGE")

        assertTrue(capture.contains("captureAttempts.ownsAcquisition(attemptId)"))
        assertTrue(capture.contains("CAPTURE_ORPHAN_FINALIZE"))
        assertTrue(capture.contains("finishCaptureAttempt(attemptId, null, orphanReason)"))
    }

    @Test
    fun `camera UI serializes only shutter dispatch not background processing`() {
        val screen = source("src/main/java/com/bncam/ui/screens/capture/CameraScreen.kt")
        assertTrue(screen.contains("var shutterDispatchInFlight by remember { mutableStateOf(false) }"))
        assertTrue(screen.contains("!isCountingDown && !shutterDispatchInFlight"))
        assertTrue(screen.contains("shutterDispatchInFlight = true"))
        assertTrue(screen.contains("shutterDispatchInFlight = false"))
    }
    @Test
    fun `multiframe shutter admission needs one anchor not the quality target`() {
        val readiness = source("src/main/java/com/bncam/core/capture/WarmBufferReadinessPolicy.kt")
        val multi = readiness.substringAfter("CaptureMode.MULTI -> {")
            .substringBefore("            }\n        }")

        assertTrue(multi.contains("minOf(requestedTarget, 1)"))
        assertFalse(multi.contains("warmStartFloor"))
    }

    @Test
    fun `multiframe early rejection becomes a terminal debug attempt`() {
        val runner = source("src/main/java/com/bncam/core/runners/MultiFrameRunner.kt")
        assertTrue(runner.contains("finalizeRejectedBeforeAsyncOwnership"))
        assertTrue(runner.contains("MULTI_FRAME_RING_BUFFER_EMPTY"))
        assertTrue(runner.contains("MULTI_FRAME_ANCHOR_INCOMPLETE"))
        assertTrue(runner.contains("MULTI_FRAME_QUEUE_FULL"))
        assertTrue(runner.contains("MULTI_FRAME_QUEUE_SUBMISSION_REJECTED"))
    }

    @Test
    fun `successful debug retains recent failed shutter admission evidence`() {
        val aggregator = source("src/main/java/com/bncam/core/debug/DiagnosticsAggregator.kt")
        val logger = source("src/main/java/com/bncam/core/debug/ShotLogger.kt")

        assertTrue(aggregator.contains("fun recentCaptureAdmissionFailures("))
        assertTrue(aggregator.contains("previous_capture_running"))
        assertTrue(logger.contains("DiagnosticsAggregator.recentCaptureAdmissionFailures()"))
        assertTrue(logger.contains("Prior Shutter Admission"))
    }

    @Test
    fun `no-rebuild profile refresh restarts warm-buffer watchdog`() {
        val manager = source("src/main/java/com/bncam/core/engine/BnCameraManager.kt")
        val skippedReset = manager.substringAfter("if (!decision.required) {")
            .substringBefore("val rawPreviewSessionOnlyChange")

        assertTrue(skippedReset.contains("updatePreviewRepeatingRequest()"))
        assertTrue(skippedReset.contains("startWarmBufferWatchdog(pipelineGeneration)"))
        assertTrue(skippedReset.indexOf("updatePreviewRepeatingRequest()") <
            skippedReset.indexOf("startWarmBufferWatchdog(pipelineGeneration)"))
    }

    @Test
    fun `warm-buffer watchdog does not mistake retained stale frames for a live producer`() {
        val manager = source("src/main/java/com/bncam/core/engine/BnCameraManager.kt")
        val watchdog = manager.substringAfter("private fun startWarmBufferWatchdog")
            .substringBefore("private fun closeCustomRawPreviewReader")

        assertTrue(watchdog.contains("freshMetadataCompleteFrameCount("))
        assertTrue(watchdog.contains("health.validCompleteFrameCount > 0 && freshCompleteFrames > 0"))
        assertTrue(watchdog.contains("val staleProducer = health.validCompleteFrameCount > 0 && freshCompleteFrames == 0"))
        assertTrue(watchdog.contains("freshAtEscalation == 0"))
        assertFalse(watchdog.contains("ringBuffer.completeFrameCount() == 0"))
    }

    @Test
    fun `normal multiframe pins a pre-shutter anchor before readiness wait`() {
        val manager = source("src/main/java/com/bncam/core/engine/BnCameraManager.kt")
        val capture = manager.substringAfter("suspend fun executeCapture(")
            .substringBefore("// ========================================================\n    // HARDWARE CONFIG TO NATIVE BRIDGE")
        val pinIndex = capture.indexOf("MULTI_SHUTTER_ANCHOR_PIN")
        val gateIndex = capture.indexOf("waitForPipelineReadyForCapture(")
        val runnerIndex = capture.indexOf("preleasedShutterAnchor = preleasedNormalMultiAnchor")

        assertTrue(pinIndex >= 0)
        assertTrue(gateIndex > pinIndex)
        assertTrue(runnerIndex > gateIndex)
        assertTrue(capture.contains("preleasedNormalMultiAnchor?.let { orphanLease ->"))
    }

    @Test
    fun `multiframe excludes the pinned anchor from atomic support leasing`() {
        val ring = source("src/main/java/com/bncam/core/buffer/FrameRingBuffer.kt")
        val runner = source("src/main/java/com/bncam/core/runners/MultiFrameRunner.kt")

        assertTrue(ring.contains("excludeFrameVersions: Set<Long> = emptySet()"))
        assertTrue(ring.contains("expectedFormat: Int? = null"))
        assertTrue(ring.contains("it.frameVersion !in excludeFrameVersions"))
        assertTrue(ring.contains("expectedFormat == null || it.format == expectedFormat"))
        assertTrue(runner.contains("validPreleasedAnchor"))
        assertTrue(runner.contains("excludeFrameVersions = validPreleasedAnchor"))
        assertTrue(runner.contains("normalLeasedCandidates + validPreleasedAnchor"))
    }

    @Test
    fun `temporary preview attaches by exact shutter timestamp not latest work`() {
        val queue = source("src/main/java/com/bncam/core/output/CaptureProcessingQueue.kt")
        val screen = source("src/main/java/com/bncam/ui/screens/capture/CameraScreen.kt")

        assertTrue(queue.contains("snapshotForCaptureStartedNs(captureStartedNs: Long)"))
        assertTrue(screen.contains("snapshotForCaptureStartedNs(userShutterTimestampNs)"))
        assertFalse(screen.contains("workIdBeforeCapture"))
    }

    @Test
    fun `pinned multiframe anchor bypasses later transient readiness waits`() {
        val manager = source("src/main/java/com/bncam/core/engine/BnCameraManager.kt")
        val capture = manager.substringAfter("suspend fun executeCapture(")
            .substringBefore("// ========================================================\n    // HARDWARE CONFIG TO NATIVE BRIDGE")

        assertTrue(capture.contains("val pinnedAnchorCanOwnAdmission"))
        assertTrue(capture.contains("READY_PINNED_ANCHOR"))
        assertTrue(capture.contains("MULTI_PINNED_ANCHOR_ADMISSION"))
        assertTrue(capture.contains("350L"))
    }

}
