package com.bncam.core.capture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RawFlickerStabilityTrackerTest {
    @Test
    fun `auto lock requires sustained same frequency evidence`() {
        val tracker = RawFlickerStabilityTracker(60L, 180L, 300L, 600L)
        assertEquals(RawFlickerFrequency.NONE, tracker.observe(RawFlickerObservation.HZ_50, 100L).stableFrequency)
        val locked = tracker.observe(RawFlickerObservation.HZ_50, 160L)
        assertEquals(RawFlickerFrequency.HZ_50, locked.stableFrequency)
        assertFalse(locked.fallbackActive)
    }

    @Test
    fun `transient none does not tear down real lock but sustained none releases it`() {
        val tracker = RawFlickerStabilityTracker(60L, 180L, 300L, 600L)
        tracker.observe(RawFlickerObservation.HZ_60, 100L)
        tracker.observe(RawFlickerObservation.HZ_60, 160L)
        assertEquals(RawFlickerFrequency.HZ_60, tracker.observe(RawFlickerObservation.NONE_DETECTED, 220L).stableFrequency)
        assertEquals(RawFlickerFrequency.HZ_60, tracker.observe(RawFlickerObservation.NONE_DETECTED, 700L).stableFrequency)
        assertEquals(RawFlickerFrequency.NONE, tracker.observe(RawFlickerObservation.NONE_DETECTED, 820L).stableFrequency)
    }

    @Test
    fun `opposite real frequency needs stronger switch evidence`() {
        val tracker = RawFlickerStabilityTracker(60L, 180L, 300L, 600L)
        tracker.observe(RawFlickerObservation.HZ_50, 100L)
        tracker.observe(RawFlickerObservation.HZ_50, 160L)
        tracker.observe(RawFlickerObservation.HZ_60, 200L)
        assertEquals(RawFlickerFrequency.HZ_50, tracker.observe(RawFlickerObservation.HZ_60, 300L).stableFrequency)
        assertEquals(RawFlickerFrequency.HZ_60, tracker.observe(RawFlickerObservation.HZ_60, 380L).stableFrequency)
    }

    @Test
    fun `explicit none never fabricates 50hz fallback`() {
        val tracker = RawFlickerStabilityTracker(60L, 180L, 300L, 600L)
        tracker.observe(RawFlickerObservation.NONE_DETECTED, 100L)
        val none = tracker.observe(RawFlickerObservation.NONE_DETECTED, 1_000L)
        assertEquals(RawFlickerFrequency.NONE, none.stableFrequency)
        assertFalse(none.fallbackActive)
    }

    @Test
    fun `unavailable statistic falls back to 50hz and real 60hz replaces it`() {
        val tracker = RawFlickerStabilityTracker(60L, 180L, 300L, 600L)
        tracker.observe(RawFlickerObservation.UNAVAILABLE, 100L)
        val fallback = tracker.observe(RawFlickerObservation.UNAVAILABLE, 400L)
        assertEquals(RawFlickerFrequency.HZ_50, fallback.stableFrequency)
        assertTrue(fallback.fallbackActive)

        tracker.observe(RawFlickerObservation.HZ_60, 450L)
        val replaced = tracker.observe(RawFlickerObservation.HZ_60, 510L)
        assertEquals(RawFlickerFrequency.HZ_60, replaced.stableFrequency)
        assertFalse(replaced.fallbackActive)
    }
    @Test
    fun `explicit none immediately clears synthetic fallback authority`() {
        val tracker = RawFlickerStabilityTracker(60L, 180L, 300L, 600L)
        tracker.observe(RawFlickerObservation.UNAVAILABLE, 100L)
        val fallback = tracker.observe(RawFlickerObservation.UNAVAILABLE, 400L)
        assertTrue(fallback.fallbackActive)

        val cleared = tracker.observe(RawFlickerObservation.NONE_DETECTED, 450L)
        assertEquals(RawFlickerFrequency.NONE, cleared.stableFrequency)
        assertFalse(cleared.fallbackActive)
    }

}
