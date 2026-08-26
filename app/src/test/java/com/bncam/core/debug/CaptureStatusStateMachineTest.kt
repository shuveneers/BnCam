package com.bncam.core.debug

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class CaptureStatusStateMachineTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun testIdempotentStateTransitions() {
        val attemptId = "test_attempt_123"

        val logger = ShotLogger(tempFolder.root)
        logger.startNewShot("TestSensor", "SingleFrame")
        logger.writeInitialStatus(attemptId, "SHUTTER_DISPATCH")

        // First transition to FAILED
        val transition1 = logger.finalizeAttemptOnce(
            attemptId = attemptId,
            terminalState = CaptureStatusState.FAILED,
            stage = "TEST_FAILURE",
            exception = IllegalStateException("Test exception")
        )
        assertTrue(transition1)

        // Attempt second transition to COMPLETED (MUST BE REJECTED)
        val transition2 = logger.finalizeAttemptOnce(
            attemptId = attemptId,
            terminalState = CaptureStatusState.COMPLETED,
            stage = "TEST_COMPLETED",
            jpegPublished = true
        )
        assertFalse(transition2)

        // Fallback finalizer attempt (MUST BE REJECTED)
        logger.fallbackFinalizeIfStarted(attemptId, "terminal_state_missing")

        // Verify status.json file content
        val shotDir = logger.getShotDir()
        val statusFile = File(shotDir, "status.json")
        assertTrue(statusFile.exists())
        val content = statusFile.readText()
        assertTrue(content.contains("\"state\": \"FAILED\""))
        assertTrue(content.contains("IllegalStateException"))
        assertTrue(content.contains("Test exception"))
        assertFalse(content.contains("\"state\": \"COMPLETED\""))
    }

    @Test
    fun testDebugFailureTestTrigger() {
        if (!com.bncam.BuildConfig.DEBUG) return
        DebugFailureTestTrigger.forceFailureForNextCapture = true
        assertTrue(DebugFailureTestTrigger.shouldFailAndClear())
        assertFalse(DebugFailureTestTrigger.shouldFailAndClear()) // Automatically cleared after firing
    }
    @Test
    fun terminalTransientStateIsRemovedWhenNextCaptureStarts() {
        val logger = ShotLogger(tempFolder.root)
        logger.startNewShot("TestSensor", "SingleFrame")
        logger.writeInitialStatus("attempt_one", "SHUTTER_DISPATCH")
        assertTrue(
            logger.finalizeAttemptOnce(
                attemptId = "attempt_one",
                terminalState = CaptureStatusState.COMPLETED,
                stage = "PUBLISHED",
                jpegPublished = true
            )
        )
        val firstStateDir = logger.getShotDir()
        assertTrue(firstStateDir?.exists() == true)

        logger.startNewShot("TestSensor", "SingleFrame")

        assertFalse(firstStateDir?.exists() == true)
        assertTrue(logger.getShotDir()?.exists() == true)
    }

}
