package com.bncam.core.capture

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CapturePreviewContinuityTrackerTest {
    @Test
    fun `counts preview frames and reports longest gap during capture`() {
        var now = 1_000_000_000L
        val tracker = CapturePreviewContinuityTracker { now }

        tracker.begin(attemptId = 7L, sessionEpoch = 11L, repeatingRequestActive = true)
        now += 16_000_000L
        tracker.previewFrame()
        now += 42_000_000L
        tracker.previewFrame()
        now += 20_000_000L

        val result = tracker.finish(
            attemptId = 7L,
            sessionEpoch = 11L,
            captureOutstandingImageCount = 2
        ) ?: error("missing snapshot")

        assertEquals(2L, result.previewFramesDuringCapture)
        assertEquals(42.0, result.previewLongestFrameGapMs, absoluteTolerance = 0.0001)
        assertTrue(result.repeatingRequestActiveDuringCapture)
        assertFalse(result.cameraSessionReconfigured)
        assertEquals(2, result.captureOutstandingImageCount)
    }

    @Test
    fun `detects repeating loss and session reconfiguration`() {
        var now = 5_000_000_000L
        val tracker = CapturePreviewContinuityTracker { now }
        tracker.begin(attemptId = 9L, sessionEpoch = 3L, repeatingRequestActive = true)
        now += 10_000_000L
        tracker.previewFrame()
        tracker.repeatingRequestState(false)
        now += 12_000_000L

        val result = tracker.finish(
            attemptId = 9L,
            sessionEpoch = 4L,
            captureOutstandingImageCount = 0
        ) ?: error("missing snapshot")

        assertFalse(result.repeatingRequestActiveDuringCapture)
        assertTrue(result.cameraSessionReconfigured)
    }
}
