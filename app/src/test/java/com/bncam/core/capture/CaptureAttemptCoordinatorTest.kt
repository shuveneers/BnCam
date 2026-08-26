package com.bncam.core.capture

import com.bncam.core.output.CaptureWorkState
import com.bncam.core.output.CaptureWorkStateTracker
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CaptureAttemptCoordinatorTest {
    private var nowNs = 0L
    private fun coordinator() = CaptureAttemptCoordinator(clockNs = { nowNs })
    private fun context() = CaptureAttemptContext(
        cameraId = "0",
        physicalCameraId = null,
        lensName = "Main",
        format = "YUV_420_888",
        bufferMode = "ZSL",
        captureMode = "SINGLE_FRAME_ZSL",
        generationId = 7,
        imageReaderMaxImages = 8,
        ringBufferFrameCount = 5
    )

    @Test
    fun acquisitionOwnershipIsExplicitAndReleasedOnAsyncSubmission() {
        val coordinator = coordinator()
        val first = coordinator.begin(context())!!
        assertTrue(coordinator.ownsAcquisition(first))

        coordinator.markSubmitted(first, 77L)

        assertFalse(coordinator.ownsAcquisition(first))
        assertTrue(coordinator.snapshot.nextCaptureAllowed)
    }

    @Test
    fun secondCaptureAllowedAfterFirstCaptureCompletes() {
        val coordinator = coordinator()
        val first = coordinator.begin(context())!!
        assertNull(coordinator.begin(context()))
        coordinator.finish(first, CaptureAttemptResult.SUCCESS, "ok")
        assertNotNull(coordinator.begin(context()))
    }

    @Test
    fun asyncSubmissionReleasesAcquisitionSlotWithoutLosingAttemptOwnership() {
        val coordinator = coordinator()
        val first = coordinator.begin(context())!!
        val firstListener = coordinator.listenerFor(first)
        firstListener.sourceImageAcquired()
        nowNs += 1_000_000
        coordinator.markSubmitted(first, 101L)

        assertTrue(coordinator.snapshot.nextCaptureAllowed)
        val second = coordinator.begin(context())!!
        assertTrue(coordinator.snapshot.captureInProgress)
        assertEquals(second, coordinator.snapshot.captureAttemptId)

        // A detached worker for the first shot must never mutate/finalize the second shot.
        firstListener.nativeProcessingStart()
        firstListener.nativeProcessingEnd(true)
        coordinator.finish(first, CaptureAttemptResult.SUCCESS, "published")
        assertTrue(coordinator.snapshot.captureInProgress)
        assertEquals(second, coordinator.snapshot.captureAttemptId)
    }

    @Test
    fun acquisitionCanFinishWhileDetachedRawWorkContinues() {
        val coordinator = coordinator()
        val first = coordinator.begin(context())!!
        coordinator.sourceImageAcquired()

        var nowNs = 1L
        val workTracker = CaptureWorkStateTracker(clockNs = { nowNs++ })
        val work = workTracker.begin("SingleRaw10", captureStartedNs = 0L)
        workTracker.transition(work.workId, CaptureWorkState.PROCESSING)

        coordinator.finish(first, CaptureAttemptResult.SUCCESS, "raw16_processing_queued")

        assertTrue(coordinator.snapshot.nextCaptureAllowed)
        assertNotNull(coordinator.begin(context()))
        assertEquals(
            CaptureWorkState.PROCESSING,
            workTracker.snapshot(work.workId)?.state
        )
    }

    @Test
    fun stateResetsOnSuccess() {
        val coordinator = coordinator()
        val id = coordinator.begin(context())!!
        coordinator.nativeProcessingStart()
        coordinator.saveStart()
        coordinator.finish(id, CaptureAttemptResult.SUCCESS, "ok")
        val state = coordinator.snapshot
        assertFalse(state.captureInProgress)
        assertFalse(state.processingInProgress)
        assertFalse(state.saveInProgress)
        assertFalse(state.pendingCapture)
        assertTrue(state.stateReset)
        assertTrue(state.nextCaptureAllowed)
    }

    @Test
    fun stateResetsOnProcessingFailure() {
        val coordinator = coordinator()
        val id = coordinator.begin(context())!!
        coordinator.nativeProcessingStart()
        coordinator.nativeProcessingEnd(false)
        assertEquals(CaptureAttemptResult.PROCESSING_FAILURE, coordinator.terminalResult(false))
        coordinator.finish(id, CaptureAttemptResult.PROCESSING_FAILURE, "native failed")
        assertTrue(coordinator.snapshot.nextCaptureAllowed)
    }

    @Test
    fun stateResetsOnSaveFailure() {
        val coordinator = coordinator()
        val id = coordinator.begin(context())!!
        coordinator.nativeProcessingStart()
        coordinator.nativeProcessingEnd(true)
        coordinator.saveStart()
        coordinator.saveEnd(false)
        assertEquals(CaptureAttemptResult.SAVE_FAILURE, coordinator.terminalResult(false))
        coordinator.finish(id, CaptureAttemptResult.SAVE_FAILURE, "save failed")
        assertTrue(coordinator.snapshot.stateReset)
    }

    @Test
    fun timingBreakdownIsAvailable() {
        val coordinator = coordinator()
        val id = coordinator.begin(context())!!
        nowNs += 1_000_000; coordinator.captureRequestSubmitted()
        nowNs += 2_000_000; coordinator.sourceImageAcquired()
        nowNs += 3_000_000; coordinator.nativeProcessingStart()
        nowNs += 4_000_000; coordinator.nativeProcessingEnd(true)
        nowNs += 5_000_000; coordinator.saveStart()
        nowNs += 6_000_000; coordinator.saveEnd(true)
        nowNs += 7_000_000
        val timing = coordinator.finish(id, CaptureAttemptResult.SUCCESS, "ok")!!
        assertEquals(1.0, timing.timeShutterToRequestMs, 0.001)
        assertEquals(2.0, timing.timeRequestToImageMs, 0.001)
        assertEquals(4.0, timing.timeProcessingMs, 0.001)
        assertEquals(6.0, timing.timeSaveMs, 0.001)
        assertEquals(28.0, timing.timeTotalShotMs, 0.001)
    }
}
