package com.bncam.core.capture

import org.junit.Assert.assertTrue
import org.junit.Test

class RepeatedCaptureValidatorTest {
    @Test
    fun fiveShotSequenceRemainsValid() {
        val results = (1..5).map { index ->
            RepeatedCaptureShotResult(
                shotIndex = index,
                captureAttemptId = index.toLong(),
                success = true,
                jpegProduced = true,
                debugProduced = true,
                stateReset = true,
                nextCaptureAllowed = true,
                timeToCaptureMs = 10.0,
                timeToProcessMs = 20.0,
                timeToSaveMs = 5.0
            )
        }
        assertTrue(RepeatedCaptureValidator.isValid(results))
    }
}
