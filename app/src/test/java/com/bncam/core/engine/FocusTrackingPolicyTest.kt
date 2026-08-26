package com.bncam.core.engine

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class FocusTrackingPolicyTest {
    @Test
    fun adaptiveAfRegionScalesWithTargetButStaysBounded() {
        assertEquals(0.035f, FocusTrackingPolicy.adaptiveAfRegionPct(0.02f, 0.03f))
        assertTrue(FocusTrackingPolicy.adaptiveAfRegionPct(0.10f, 0.08f) in 0.047f..0.049f)
        assertEquals(0.14f, FocusTrackingPolicy.adaptiveAfRegionPct(0.80f, 0.70f))
    }

    @Test
    fun reacquisitionWindowExpandsButCannotRunAway() {
        val start = FocusTrackingPolicy.reacquisitionSearchRadius(0)
        val later = FocusTrackingPolicy.reacquisitionSearchRadius(10)
        val capped = FocusTrackingPolicy.reacquisitionSearchRadius(100)
        assertTrue(later > start)
        assertEquals(0.30f, capped)
    }

    @Test
    fun trackingLossTransitionsFromReacquiringToLost() {
        assertEquals(FocusTrackingPhase.REACQUIRING, FocusTrackingPolicy.phaseForLostFrames(1))
        assertEquals(FocusTrackingPhase.LOST, FocusTrackingPolicy.phaseForLostFrames(14))
        assertTrue(FocusTrackingPolicy.confidenceForLostFrames(20) < FocusTrackingPolicy.confidenceForLostFrames(3))
    }
}
