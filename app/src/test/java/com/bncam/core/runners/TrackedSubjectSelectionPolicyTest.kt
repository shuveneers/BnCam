package com.bncam.core.runners

import com.bncam.core.engine.FocusCaptureContext
import com.bncam.core.engine.FocusOwner
import com.bncam.core.engine.FocusTrackingPhase
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TrackedSubjectSelectionPolicyTest {
    @Test
    fun reliableTrackingMovesGlobalSharpnessBudgetIntoRoiFocus() {
        val base = SingleFrameSelectionWeights(
            freshness = 0.12,
            sharpness = 0.35,
            motion = 0.15,
            exposure = 0.07,
            clipping = 0.09,
            ae = 0.08,
            awb = 0.08,
            focus = 0.06,
            alignability = 0.00
        )
        val adjusted = TrackedSubjectSelectionPolicy.adjustWeights(base, reliableTrackedSubject = true)
        assertTrue(adjusted.focus > base.focus)
        assertTrue(adjusted.sharpness < base.sharpness)
        val sum = adjusted.freshness + adjusted.sharpness + adjusted.motion + adjusted.exposure +
            adjusted.clipping + adjusted.ae + adjusted.awb + adjusted.focus + adjusted.alignability
        assertTrue(kotlin.math.abs(sum - 1.0) < 0.000001)
    }

    @Test
    fun trackedSelectionPenalizesFramesWithoutTrackedRoiEvidence() {
        val withEvidence = TrackedSubjectSelectionPolicy.focusScore(1.0, 0.88, true, true)
        val withoutEvidence = TrackedSubjectSelectionPolicy.focusScore(1.0, 0.88, false, true)
        assertTrue(withEvidence > 0.8)
        assertTrue(withoutEvidence < 0.0)
    }

    @Test
    fun multiFrameAnchorPolicyPrefersSharperTrackedSubjectWithinFreshWindow() {
        val selected = TrackedSubjectAnchorPolicy.selectIndex(
            listOf(
                TrackedSubjectAnchorEvidence(0, true, true, true, 0.92, 90.0, false),
                TrackedSubjectAnchorEvidence(1, true, true, true, 0.55, 20.0, false),
                TrackedSubjectAnchorEvidence(2, false, true, true, 0.99, 10.0, false)
            ),
            enabled = true
        )
        assertEquals(0, selected)
        assertNull(
            TrackedSubjectAnchorPolicy.selectIndex(
                listOf(TrackedSubjectAnchorEvidence(0, false, true, true, 1.0, 10.0, false)),
                enabled = true
            )
        )
    }

    @Test
    fun focusCaptureContextRequiresActualTrackingLockAndConfidence() {
        assertTrue(
            FocusCaptureContext(
                owner = FocusOwner.TRACK_TIMED,
                trackingPhase = FocusTrackingPhase.TRACKING,
                trackingConfidence = 0.8f
            ).reliableTrackedSubject
        )
        assertTrue(
            !FocusCaptureContext(
                owner = FocusOwner.TRACK_TIMED,
                trackingPhase = FocusTrackingPhase.REACQUIRING,
                trackingConfidence = 0.8f
            ).reliableTrackedSubject
        )
    }
}
