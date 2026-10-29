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
    fun `missing statistics never claim 50hz or 60hz`() {
        val tracker = RawFlickerStabilityTracker(60L, 180L, 300L, 600L)
        tracker.observe(RawFlickerObservation.UNAVAILABLE, 100L)
        val unknown = tracker.observe(RawFlickerObservation.UNAVAILABLE, 1_000L)
        assertEquals(RawFlickerFrequency.NONE, unknown.stableFrequency)
        assertFalse(unknown.fallbackActive)
        assertTrue(unknown.source.contains("STATISTIC_UNAVAILABLE"))
        tracker.observe(RawFlickerObservation.HZ_60, 1_100L)
        val acquired = tracker.observe(RawFlickerObservation.HZ_60, 1_160L)
        assertEquals(RawFlickerFrequency.HZ_60, acquired.stableFrequency)
        assertFalse(acquired.fallbackActive)
    }

    @Test
    fun `temporary metadata gaps retain measured lock then expire`() {
        val tracker = RawFlickerStabilityTracker(60L, 180L, 300L, 600L)
        tracker.observe(RawFlickerObservation.HZ_60, 100L)
        tracker.observe(RawFlickerObservation.HZ_60, 160L)
        assertEquals(RawFlickerFrequency.HZ_60,
            tracker.observe(RawFlickerObservation.UNAVAILABLE, 200L).stableFrequency)
        assertEquals(RawFlickerFrequency.HZ_60,
            tracker.observe(RawFlickerObservation.UNAVAILABLE, 700L).stableFrequency)
        val expired = tracker.observe(RawFlickerObservation.UNAVAILABLE, 800L)
        assertEquals(RawFlickerFrequency.NONE, expired.stableFrequency)
        assertTrue(expired.source.contains("UNAVAILABLE"))
    }

    @Test
    fun `explicit none releases real evidence only after sustained absence`() {
        val tracker = RawFlickerStabilityTracker(60L, 180L, 300L, 600L)
        tracker.observe(RawFlickerObservation.HZ_50, 100L)
        tracker.observe(RawFlickerObservation.HZ_50, 160L)
        assertEquals(RawFlickerFrequency.HZ_50,
            tracker.observe(RawFlickerObservation.NONE_DETECTED, 200L).stableFrequency)
        assertEquals(RawFlickerFrequency.NONE,
            tracker.observe(RawFlickerObservation.NONE_DETECTED, 800L).stableFrequency)
    }
}
