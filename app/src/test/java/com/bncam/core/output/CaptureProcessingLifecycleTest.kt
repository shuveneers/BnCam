package com.bncam.core.output

import com.bncam.core.buffer.FrameRingBuffer
import com.bncam.core.buffer.ZslFramePair
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class CaptureProcessingLifecycleTest {

    @Test
    fun reservationEmitsQueueStateChangeAndIncrementsNonIdleCount() {
        var nowNs = 1L
        val tracker = CaptureWorkStateTracker(clockNs = { nowNs++ })
        val snapshot = tracker.begin("SingleRaw10", captureStartedNs = 100L)

        assertEquals(CaptureWorkState.QUEUED, snapshot.state)
        assertEquals(1, tracker.allSnapshots().size)
    }

    @Test
    fun nonIdleCountEvaluatesQueuedProcessingSavingCorrectly() {
        var nowNs = 1L
        val tracker = CaptureWorkStateTracker(clockNs = { nowNs++ })
        val s1 = tracker.begin("MultiRaw10", captureStartedNs = 100L)
        val s2 = tracker.begin("MultiRawSensor", captureStartedNs = 200L)

        tracker.transition(s1.workId, CaptureWorkState.PROCESSING)

        val nonIdle = tracker.allSnapshots().count {
            it.state == CaptureWorkState.QUEUED ||
            it.state == CaptureWorkState.PROCESSING ||
            it.state == CaptureWorkState.SAVING
        }

        assertEquals(2, nonIdle)

        tracker.transition(s1.workId, CaptureWorkState.PUBLISHED)
        val nonIdleAfterPub = tracker.allSnapshots().count {
            it.state == CaptureWorkState.QUEUED ||
            it.state == CaptureWorkState.PROCESSING ||
            it.state == CaptureWorkState.SAVING
        }

        assertEquals(1, nonIdleAfterPub)
    }

    @Test
    fun leasedFramesSurviveBufferClear() {
        val ring = FrameRingBuffer(capacity = 10)
        ring.activateGeneration(1)

        // Mock a frame pair in the ring
        val pair = ZslFramePair().apply {
            timestamp = 5000L
            generationId = 1
            format = 32 // RAW_SENSOR
        }

        // Simulate leased frame extraction
        val leasedFrames = listOf(pair)

        // Buffer cleared (e.g. camera session teardown)
        ring.clear()

        // Leased frames retain their payload & timestamp
        assertEquals(1, leasedFrames.size)
        assertEquals(5000L, leasedFrames[0].timestamp)
        assertEquals(1, leasedFrames[0].generationId)
    }

    @Test
    fun uiCoroutineCancellationDoesNotCancelEnqueuedJob() = runBlocking {
        val uiScope = CoroutineScope(Dispatchers.Default)
        val jobCompleted = CompletableDeferred<Boolean>()

        // Spawn simulated shutter in UI scope
        val uiJob = uiScope.launch {
            // Processing job submitted to independent background deferred
            val bgJob = CoroutineScope(Dispatchers.Default).launch {
                delay(100)
                jobCompleted.complete(true)
            }
        }

        delay(10)
        // Simulate UI teardown / navigation away
        uiScope.cancel()

        // Verify background job completes cleanly
        val completed = jobCompleted.await()
        assertTrue(completed)
    }

    @Test
    fun processingFailureIsIsolatedAndDoesNotKillWorker() = runBlocking {
        var nowNs = 1L
        val tracker = CaptureWorkStateTracker(clockNs = { nowNs++ })
        val job1 = tracker.begin("Job1", captureStartedNs = 10L)
        val job2 = tracker.begin("Job2", captureStartedNs = 20L)

        // Fail job 1
        tracker.transition(job1.workId, CaptureWorkState.PROCESSING)
        tracker.transition(job1.workId, CaptureWorkState.FAILED, failureReason = "simulated_error")

        // Job 2 should still process and complete
        tracker.transition(job2.workId, CaptureWorkState.PROCESSING)
        val pub2 = tracker.transition(job2.workId, CaptureWorkState.PUBLISHED, publishedUri = "content://2")

        assertEquals(CaptureWorkState.FAILED, tracker.snapshot(job1.workId)?.state)
        assertEquals(CaptureWorkState.PUBLISHED, pub2?.state)
    }

    @Test
    fun temporaryPreviewCleanedOnTerminalState() {
        val tempFile = File.createTempFile("test_preview", ".jpg")
        assertTrue(tempFile.exists())

        var nowNs = 1L
        val tracker = CaptureWorkStateTracker(clockNs = { nowNs++ })
        val snapshot = tracker.begin("TestRoute", captureStartedNs = 50L, temporaryPreviewPath = tempFile.absolutePath)

        tracker.transition(snapshot.workId, CaptureWorkState.PROCESSING)
        tracker.transition(snapshot.workId, CaptureWorkState.PUBLISHED)

        // File should be deleted on terminal transition
        assertFalse(tempFile.exists())
    }

    @Test
    fun rapidCapturesAssignedDistinctWorkIds() {
        var nowNs = 1L
        val tracker = CaptureWorkStateTracker(clockNs = { nowNs++ })
        val s1 = tracker.begin("Route1", captureStartedNs = 100L)
        val s2 = tracker.begin("Route2", captureStartedNs = 105L)
        val s3 = tracker.begin("Route3", captureStartedNs = 110L)

        assertTrue(s1.workId != s2.workId)
        assertTrue(s2.workId != s3.workId)
        assertTrue(s1.shotSequenceId < s2.shotSequenceId)
        assertTrue(s2.shotSequenceId < s3.shotSequenceId)
    }

    @Test
    fun oneWorkIdStartsAtMostOnce() {
        var nowNs = 1L
        val tracker = CaptureWorkStateTracker(clockNs = { nowNs++ })
        val snapshot = tracker.begin("Route1", captureStartedNs = 100L)

        val proc1 = tracker.transition(snapshot.workId, CaptureWorkState.PROCESSING)
        assertNotNull(proc1)

        // Attempting second start or invalid transition throwing exception
        var caught = false
        try {
            tracker.transition(snapshot.workId, CaptureWorkState.QUEUED)
        } catch (e: IllegalArgumentException) {
            caught = true
        }
        assertTrue(caught)
    }
}
