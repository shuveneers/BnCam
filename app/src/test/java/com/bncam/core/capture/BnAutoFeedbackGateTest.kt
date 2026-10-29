package com.bncam.core.capture

import org.junit.Assert.*
import org.junit.Test

class BnAutoFeedbackGateTest {
    private val frame = 33_333_333L
    @Test fun queueIsNotSensorRealizationAndOldEpochCannotAcknowledge() {
        val g = BnAutoFeedbackGate()
        assertTrue(g.reserve("main", 1_000L))
        assertFalse(g.reserve("main", 2_000L))
        assertFalse(g.admit("main", 100L, 4L, 10_000_000L, 200, 3_000L, frame))
        g.submitted("main", 5L, 4_000L, 10_000_000L, 200)
        assertFalse(g.admit("main", 101L, 4L, 10_000_000L, 200, 5_000L, frame))
        assertFalse(g.admit("main", 102L, 5L, 5_000_000L, 100, 6_000L, frame))
        assertTrue(g.admit("main", 103L, 5L, 10_000_000L, 200, 7_000L, frame))
        assertEquals(3_000L, g.realizationLatencyNs)
        assertFalse(g.admit("main", 102L, 5L, 10_000_000L, 200, 8_000L, frame))
        assertTrue(g.reserve("main", 8_000L))
    }

    @Test fun afRequestsDoNotPostponeHalClampRecovery() {
        val g = BnAutoFeedbackGate()
        g.submitted("main", 5L, 1_000L, 10_000_000L, 200)
        g.submitted("main", 6L, 300_000_000L, 10_000_000L, 200)
        assertTrue(g.admit("main", 100L, 6L, 8_000_000L, 240, 400_000_000L, frame))
        assertTrue(g.reserve("main", 400_000_001L))
    }

    @Test fun timeoutDoesNotAdmitOldPipelineFramesOrMixSensorAndHostClocks() {
        val g = BnAutoFeedbackGate()
        g.submitted("main", 8L, 1_000_000_000L, 10_000_000L, 200)
        assertFalse(g.admit("main", 90_000_000_000L, 7L, 8_000_000L, 240, 2_000_000_000L, frame))
        assertFalse(g.admit("main", 90_000_000_001L, 8L, 8_000_000L, 240, 1_100_000_000L, frame))
        assertTrue(g.admit("main", 90_000_000_002L, 8L, 8_000_000L, 240, 1_400_000_000L, frame))
    }

    @Test fun canceledQueueAndNewLensDoNotRetainTargets() {
        val g = BnAutoFeedbackGate()
        assertTrue(g.reserve("main", 1L))
        g.cancelQueued("main")
        assertTrue(g.reserve("main", 2L))
        assertTrue(g.reserve("tele", 3L))
    }

    @Test fun discardedControlEventuallyReleasesWithoutSubmittingASecondTarget() {
        val g = BnAutoFeedbackGate()
        assertTrue(g.reserve("main", 1L))
        assertFalse(g.admit("main", 100L, 1L, 10_000_000L, 200, 999_999_999L, frame))
        assertTrue(g.admit("main", 101L, 1L, 10_000_000L, 200, 1_100_000_000L, frame))
    }

    @Test fun retiringQueueCallbackCannotCancelNewLensRequest() {
        val g = BnAutoFeedbackGate()
        assertTrue(g.reserve("main", 1L))
        assertTrue(g.reserve("tele", 2L))
        assertFalse(g.cancelQueued("main"))
        assertFalse(g.reserve("tele", 3L))
        assertTrue(g.cancelQueued("tele"))
        assertTrue(g.reserve("tele", 4L))
    }
}
