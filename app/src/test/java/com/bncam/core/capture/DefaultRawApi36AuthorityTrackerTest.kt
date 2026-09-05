package com.bncam.core.capture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DefaultRawApi36AuthorityTrackerTest {
    @Test
    fun `production default rejects api36 exposure time priority so bncam owns iso`() {
        val tracker = DefaultRawApi36AuthorityTracker()
        assertFalse(tracker.isAllowed(1))

        val observation = tracker.observe(
            currentGeneration = 1,
            repeatingResult = true,
            realizationStatus = "API36_EXPOSURE_REALIZED",
            aeSearching = false,
            actualIso = 400,
            minIso = 100
        )
        assertEquals(DefaultRawApi36AuthorityAction.KEEP_PRIORITY, observation.action)
        assertFalse(observation.api36Allowed)
        assertEquals(
            "api36_exposure_time_priority_disabled_for_deterministic_iso_ownership",
            observation.reason
        )
    }

    @Test
    fun `explicit api36 trial can still reboot reference after minimum iso search`() {
        val tracker = DefaultRawApi36AuthorityTracker(priorityEnabled = true)

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
    fun `explicit api36 trial clears minimum iso evidence after convergence`() {
        val tracker = DefaultRawApi36AuthorityTracker(priorityEnabled = true)
        tracker.observe(3, true, "API36_EXPOSURE_REALIZED", true, 100, 100)
        tracker.observe(3, true, "API36_EXPOSURE_REALIZED", true, 100, 100)

        val converged = tracker.observe(3, true, "API36_EXPOSURE_REALIZED", false, 100, 100)
        assertEquals(DefaultRawApi36AuthorityAction.KEEP_PRIORITY, converged.action)
        assertEquals(0, converged.consecutiveMinIsoSearchingFrames)
    }

    @Test
    fun `repeated priority realization failure rejects explicit api36 trial`() {
        val tracker = DefaultRawApi36AuthorityTracker(priorityEnabled = true)
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
    fun `new pipeline generation gets fresh capability trial when explicitly enabled`() {
        val tracker = DefaultRawApi36AuthorityTracker(
            realizationFailureThreshold = 1,
            priorityEnabled = true
        )
        tracker.observe(4, true, "API36_PRIORITY_MODE_MISMATCH", false, 400, 100)
        assertFalse(tracker.isAllowed(4))
        assertTrue(tracker.isAllowed(5))
    }

    @Test
    fun `one shot results cannot poison explicit repeating route trust`() {
        val tracker = DefaultRawApi36AuthorityTracker(
            realizationFailureThreshold = 1,
            priorityEnabled = true
        )
        val decision = tracker.observe(
            2, false, "API36_EXPOSURE_NOT_REALIZED_SHORT", true, 100, 100
        )
        assertEquals(DefaultRawApi36AuthorityAction.KEEP_PRIORITY, decision.action)
        assertTrue(tracker.isAllowed(2))
    }
}
