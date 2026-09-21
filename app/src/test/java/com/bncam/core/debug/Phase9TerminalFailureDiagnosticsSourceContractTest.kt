package com.bncam.core.debug

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class Phase9TerminalFailureDiagnosticsSourceContractTest {
    private fun appDir(): File = sequenceOf(File("."), File("app"))
        .firstOrNull { File(it, "src/main/java/com/bncam/core/debug/ShotLogger.kt").isFile }
        ?: error("Cannot locate app module")

    @Test
    fun terminalFailurePublishesBoundedDiagnosticsBeforeRichPayloadExists() {
        val root = appDir()
        val logger = File(root, "src/main/java/com/bncam/core/debug/ShotLogger.kt").readText()

        assertTrue(logger.contains("fun finalizeFailureWithPublicDiagnostics("))
        assertTrue(logger.contains("fun finalizeActiveFailureWithPublicDiagnostics("))
        assertTrue(logger.contains("activeAttemptId != attemptId"))
        assertTrue(logger.contains("activeAttemptState != CaptureStatusState.STARTED"))
        assertTrue(logger.contains("UNAVAILABLE_BEFORE_TERMINAL_FAILURE"))
        assertTrue(logger.contains("Failure stage"))
        assertTrue(logger.contains("Exception class"))
        assertTrue(logger.contains("Exception message"))
    }

    @Test
    fun singleFrameDeferredFailuresCloseTheSameShotLoggerAttempt() {
        val root = appDir()
        val runner = File(root, "src/main/java/com/bncam/core/runners/SingleFrameRunner.kt").readText()

        assertTrue(runner.contains("stage = \"ASYNC_RAW_PROCESSING_WORKER\""))
        assertTrue(runner.contains("stage = \"ASYNC_YUV_PROCESSING_WORKER\""))
        assertTrue(runner.contains("stage = \"ASYNC_OUTPUT_PUBLICATION\""))
        assertTrue(runner.contains("stage = \"ASYNC_RAW_ONLY_PUBLICATION\""))
        assertTrue(runner.contains("stage = \"ASYNC_DNG_FALLBACK_PUBLICATION\""))
    }

    @Test
    fun captureRouterClosesRejectedAndExceptionalShotsInPublicDiagnostics() {
        val root = appDir()
        val manager = File(root, "src/main/java/com/bncam/core/engine/BnCameraManager.kt").readText()

        assertTrue(manager.contains("stage = \"CAPTURE_SUBMISSION_REJECTED\""))
        assertTrue(manager.contains("Capture submission rejected: "))
        assertTrue(manager.contains("stage = \"CAPTURE_ROUTER_EXCEPTION\""))
        assertTrue(manager.contains("shotLogger.finalizeActiveFailureWithPublicDiagnostics("))
        assertTrue(!manager.contains("attemptId = \"${activeLens.id}-$userShutterTimestampNs\""))
    }
    @Test
    fun staleProcessRecoveryReplacesPublicWaitingPlaceholders() {
        val root = appDir()
        val logger = File(root, "src/main/java/com/bncam/core/debug/ShotLogger.kt").readText()

        assertTrue(logger.contains("val publicFolderName = dir.name.removePrefix(TRANSIENT_STATE_PREFIX)"))
        assertTrue(logger.contains("BNCAM RECOVERED CAPTURE FAILURE"))
        assertTrue(logger.contains("Recovered Process-Termination Evidence"))
        assertTrue(logger.contains("PublicShotDiagnosticsStorage.publish("))
        assertTrue(logger.contains("STALE_PROCESS_TERMINATED_AT_"))
    }

}
