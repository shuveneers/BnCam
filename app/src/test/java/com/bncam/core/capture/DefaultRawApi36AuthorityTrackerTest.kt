package com.bncam.core.capture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DefaultRawApi36AuthorityTrackerTest {
    @Test
    fun `realized shutter searching at minimum iso reboots reference after stable evidence`() {
        val tracker = DefaultRawApi36AuthorityTracker()

        repeat(2) {
            val decision = tracker.observe(
                currentGeneration = 7,
                repeatingResult = true,
                realizationStatus = "API36_EXPOSURE_REALIZED",
                aeSearching = true,
                actualIso = 100,
                minIso = 100
            )
            assertEquals(DefaultRawApi36AuthorityAction.KEEP_PRIORITY, decision.action)
        }

        val third = tracker.observe(7, true, "API36_EXPOSURE_REALIZED", true, 100, 100)
        assertEquals(DefaultRawApi36AuthorityAction.REBOOTSTRAP_AE_REFERENCE, third.action)
        assertTrue(third.api36Allowed)
    }

    @Test
    fun `converged frame clears minimum iso searching evidence`() {
        val tracker = DefaultRawApi36AuthorityTracker()
        tracker.observe(3, true, "API36_EXPOSURE_REALIZED", true, 100, 100)
        tracker.observe(3, true, "API36_EXPOSURE_REALIZED", true, 100, 100)

        val converged = tracker.observe(3, true, "API36_EXPOSURE_REALIZED", false, 100, 100)
        assertEquals(DefaultRawApi36AuthorityAction.KEEP_PRIORITY, converged.action)
        assertEquals(0, converged.consecutiveMinIsoSearchingFrames)
    }

    @Test
    fun `repeated priority realization failure rejects api36 for the generation`() {
        val tracker = DefaultRawApi36AuthorityTracker()
        repeat(2) {
            val decision = tracker.observe(
                9, true, "API36_EXPOSURE_NOT_REALIZED_SHORT", false, 400, 100
            )
            assertEquals(DefaultRawApi36AuthorityAction.KEEP_PRIORITY, decision.action)
        }

        val third = tracker.observe(9, true, "API36_EXPOSURE_NOT_REALIZED_SHORT", false, 400, 100)
        assertEquals(DefaultRawApi36AuthorityAction.REJECT_API36_PRIORITY, third.action)
        assertFalse(third.api36Allowed)
        assertFalse(tracker.isAllowed(9))
    }

    @Test
    fun `new pipeline generation gets a fresh capability trial`() {
        val tracker = DefaultRawApi36AuthorityTracker(realizationFailureThreshold = 1)
        tracker.observe(4, true, "API36_PRIORITY_MODE_MISMATCH", false, 400, 100)
        assertFalse(tracker.isAllowed(4))
        assertTrue(tracker.isAllowed(5))
    }

    @Test
    fun `one shot results cannot poison repeating route trust`() {
        val tracker = DefaultRawApi36AuthorityTracker(realizationFailureThreshold = 1)
        val decision = tracker.observe(
            2, false, "API36_EXPOSURE_NOT_REALIZED_SHORT", true, 100, 100
        )
        assertEquals(DefaultRawApi36AuthorityAction.KEEP_PRIORITY, decision.action)
        assertTrue(tracker.isAllowed(2))
    }
}
