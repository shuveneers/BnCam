package com.bncam.core.output

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CaptureProcessingQueueTest {
    @Test
    fun admissionAllowsOneActiveAndOnlyTwoAdditionalOwnedInputs() {
        val admission = BoundedCaptureWorkAdmission(maximumInFlight = 3)

        assertTrue(admission.tryAcquire())
        assertTrue(admission.tryAcquire())
        assertTrue(admission.tryAcquire())
        assertFalse(admission.tryAcquire())
        assertEquals(3, admission.count())

        admission.release()
        assertTrue(admission.tryAcquire())
        assertEquals(3, admission.count())
    }

    @Test
    fun processingSavingPublicationLifecycleIsExplicit() {
        var nowNs = 1L
        val tracker = CaptureWorkStateTracker(clockNs = { nowNs++ })
        val queued = tracker.begin("SingleRaw10", captureStartedNs = 10L)

        assertEquals(CaptureWorkState.QUEUED, queued.state)
        assertEquals(
            CaptureWorkState.PROCESSING,
            tracker.transition(queued.workId, CaptureWorkState.PROCESSING)?.state
        )
        assertEquals(
            CaptureWorkState.SAVING,
            tracker.transition(queued.workId, CaptureWorkState.SAVING)?.state
        )
        assertEquals(
            CaptureWorkState.PUBLISHED,
            tracker.transition(
                queued.workId,
                CaptureWorkState.PUBLISHED,
                publishedUri = "content://image/1"
            )?.state
        )
        assertEquals("content://image/1", tracker.snapshot(queued.workId)?.publishedUri)
    }

    @Test
    fun failureIsTerminalAndCannotBeRewrittenAsPublished() {
        var nowNs = 1L
        val tracker = CaptureWorkStateTracker(clockNs = { nowNs++ })
        val queued = tracker.begin("SingleRawSensor", captureStartedNs = 20L)
        tracker.transition(queued.workId, CaptureWorkState.PROCESSING)
        val failed = tracker.transition(
            queued.workId,
            CaptureWorkState.FAILED,
            failureReason = "save_failed"
        )

        assertEquals(CaptureWorkState.FAILED, failed?.state)
        assertEquals("save_failed", failed?.failureReason)
        assertEquals(
            CaptureWorkState.FAILED,
            tracker.transition(queued.workId, CaptureWorkState.PUBLISHED)?.state
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun invalidLifecycleTransitionIsRejected() {
        var nowNs = 1L
        val tracker = CaptureWorkStateTracker(clockNs = { nowNs++ })
        val queued = tracker.begin("SingleRaw10", captureStartedNs = 30L)
        tracker.transition(queued.workId, CaptureWorkState.SAVING)
    }

    @Test
    fun repeatedAdmissionStressNeverExceedsThreeOwnedRawInputs() {
        val admission = BoundedCaptureWorkAdmission(maximumInFlight = 3)
        var owned = 0
        var observedMaximum = 0

        repeat(100) {
            if (!admission.tryAcquire()) {
                assertEquals(3, owned)
                admission.release()
                owned--
                assertTrue(admission.tryAcquire())
            }
            owned++
            observedMaximum = maxOf(observedMaximum, owned)
            assertTrue(owned <= 3)
        }
        repeat(owned) { admission.release() }

        assertEquals(3, observedMaximum)
        assertEquals(0, admission.count())
    }
    @Test
    fun shutterTimestampLookupReturnsExactOverlappingWork() {
        var nowNs = 1L
        val tracker = CaptureWorkStateTracker(clockNs = { nowNs++ })
        val first = tracker.begin("MultiRaw10", captureStartedNs = 1_000L)
        val second = tracker.begin("MultiRaw10", captureStartedNs = 2_000L)

        assertEquals(first.workId, tracker.snapshotForCaptureStartedNs(1_000L)?.workId)
        assertEquals(second.workId, tracker.snapshotForCaptureStartedNs(2_000L)?.workId)
        assertEquals(null, tracker.snapshotForCaptureStartedNs(3_000L))
    }

}
