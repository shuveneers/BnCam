package com.bncam.core.capture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DefaultRawApi36AuthorityTrackerTest {
    @Test
    fun `production default allows api36 exposure time priority trial`() {
        val tracker = DefaultRawApi36AuthorityTracker()
        assertTrue(tracker.isAllowed(1))
    }

    @Test
    fun `feature can still be explicitly disabled`() {
        val tracker = DefaultRawApi36AuthorityTracker(priorityEnabled = false)
        assertFalse(tracker.isAllowed(1))

        val observation = tracker.observe(
            currentGeneration = 1,
            repeatingResult = true,
            realizationStatus = "API36_EXPOSURE_REALIZED",
            aeSearching = false,
            actualIso = 400,
            minIso = 100,
            maxIso = 6400
        )
        assertEquals(DefaultRawApi36AuthorityAction.KEEP_PRIORITY, observation.action)
        assertFalse(observation.api36Allowed)
        assertEquals("api36_exposure_time_priority_feature_disabled", observation.reason)
    }

    @Test
    fun `realized priority remains trusted`() {
        val tracker = DefaultRawApi36AuthorityTracker()
        repeat(6) {
            val decision = tracker.observe(
                currentGeneration = 2,
                repeatingResult = true,
                realizationStatus = "API36_EXPOSURE_REALIZED",
                aeSearching = false,
                actualIso = 400,
                minIso = 100,
                maxIso = 6400
            )
            assertEquals(DefaultRawApi36AuthorityAction.KEEP_PRIORITY, decision.action)
            assertTrue(decision.api36Allowed)
        }
        assertTrue(tracker.isAllowed(2))
    }

    @Test
    fun `minimum iso searching reboots reference once`() {
        val tracker = DefaultRawApi36AuthorityTracker()
        repeat(2) {
            val decision = tracker.observe(
                currentGeneration = 7,
                repeatingResult = true,
                realizationStatus = "API36_EXPOSURE_REALIZED",
                aeSearching = true,
                actualIso = 100,
                minIso = 100,
                maxIso = 6400
            )
            assertEquals(DefaultRawApi36AuthorityAction.KEEP_PRIORITY, decision.action)
        }

        val third = tracker.observe(7, true, "API36_EXPOSURE_REALIZED", true, 100, 100, 6400)
        assertEquals(DefaultRawApi36AuthorityAction.REBOOTSTRAP_AE_REFERENCE, third.action)
        assertTrue(third.api36Allowed)
        assertEquals(1, third.boundRebootstrapAttempts)
    }

    @Test
    fun `maximum iso searching reboots reference once`() {
        val tracker = DefaultRawApi36AuthorityTracker()
        repeat(2) {
            val decision = tracker.observe(
                currentGeneration = 8,
                repeatingResult = true,
                realizationStatus = "API36_EXPOSURE_REALIZED",
                aeSearching = true,
                actualIso = 6400,
                minIso = 100,
                maxIso = 6400
            )
            assertEquals(DefaultRawApi36AuthorityAction.KEEP_PRIORITY, decision.action)
        }

        val third = tracker.observe(8, true, "API36_EXPOSURE_REALIZED", true, 6400, 100, 6400)
        assertEquals(DefaultRawApi36AuthorityAction.REBOOTSTRAP_AE_REFERENCE, third.action)
        assertTrue(third.reason.contains("max_iso"))
        assertTrue(third.api36Allowed)
    }

    @Test
    fun `second unsolved iso-bound episode rejects route for generation`() {
        val tracker = DefaultRawApi36AuthorityTracker(
            isoBoundSearchingThreshold = 1,
            maxBoundRebootstrapAttempts = 1
        )
        val first = tracker.observe(5, true, "API36_EXPOSURE_REALIZED", true, 6400, 100, 6400)
        assertEquals(DefaultRawApi36AuthorityAction.REBOOTSTRAP_AE_REFERENCE, first.action)
        assertTrue(first.api36Allowed)

        val second = tracker.observe(5, true, "API36_EXPOSURE_REALIZED", true, 6400, 100, 6400)
        assertEquals(DefaultRawApi36AuthorityAction.REJECT_API36_PRIORITY, second.action)
        assertFalse(second.api36Allowed)
        assertFalse(tracker.isAllowed(5))
    }

    @Test
    fun `convergence clears prior bound rebootstrap debt`() {
        val tracker = DefaultRawApi36AuthorityTracker(isoBoundSearchingThreshold = 1)
        tracker.observe(3, true, "API36_EXPOSURE_REALIZED", true, 100, 100, 6400)

        val converged = tracker.observe(3, true, "API36_EXPOSURE_REALIZED", false, 400, 100, 6400)
        assertEquals(DefaultRawApi36AuthorityAction.KEEP_PRIORITY, converged.action)
        assertEquals(0, converged.boundRebootstrapAttempts)
        assertTrue(converged.api36Allowed)
    }

    @Test
    fun `repeated priority realization failure rejects production trial`() {
        val tracker = DefaultRawApi36AuthorityTracker()
        repeat(2) {
            val decision = tracker.observe(
                9, true, "API36_EXPOSURE_NOT_REALIZED_SHORT", false, 400, 100, 6400
            )
            assertEquals(DefaultRawApi36AuthorityAction.KEEP_PRIORITY, decision.action)
        }

        val third = tracker.observe(9, true, "API36_EXPOSURE_NOT_REALIZED_SHORT", false, 400, 100, 6400)
        assertEquals(DefaultRawApi36AuthorityAction.REJECT_API36_PRIORITY, third.action)
        assertFalse(third.api36Allowed)
        assertFalse(tracker.isAllowed(9))
    }

    @Test
    fun `new pipeline generation gets fresh capability trial`() {
        val tracker = DefaultRawApi36AuthorityTracker(realizationFailureThreshold = 1)
        tracker.observe(4, true, "API36_PRIORITY_MODE_MISMATCH", false, 400, 100, 6400)
        assertFalse(tracker.isAllowed(4))
        assertTrue(tracker.isAllowed(5))
    }

    @Test
    fun `one shot results cannot poison repeating route trust`() {
        val tracker = DefaultRawApi36AuthorityTracker(realizationFailureThreshold = 1)
        val decision = tracker.observe(
            2, false, "API36_EXPOSURE_NOT_REALIZED_SHORT", true, 6400, 100, 6400
        )
        assertEquals(DefaultRawApi36AuthorityAction.KEEP_PRIORITY, decision.action)
        assertTrue(tracker.isAllowed(2))
    }
    @Test
    fun `api36 trust requires realized repeating evidence and resets on failure`() {
        val tracker = DefaultRawApi36AuthorityTracker(priorityEnabled = true)
        assertFalse(tracker.isTrusted(11))
        tracker.observe(11, true, "API36_EXPOSURE_REALIZED", false, 400, 100, 3200)
        assertFalse(tracker.isTrusted(11))
        tracker.observe(11, true, "API36_EXPOSURE_REALIZED", false, 400, 100, 3200)
        assertTrue(tracker.isTrusted(11))
        tracker.observe(11, true, "API36_EXPOSURE_NOT_REALIZED_SHORT", false, 400, 100, 3200)
        assertFalse(tracker.isTrusted(11))
    }

    @Test
    fun `one shot realization does not establish api36 trust`() {
        val tracker = DefaultRawApi36AuthorityTracker(priorityEnabled = true)
        tracker.observe(12, false, "API36_EXPOSURE_REALIZED", false, 400, 100, 3200)
        tracker.observe(12, false, "API36_EXPOSURE_REALIZED", false, 400, 100, 3200)
        assertFalse(tracker.isTrusted(12))
    }

}
