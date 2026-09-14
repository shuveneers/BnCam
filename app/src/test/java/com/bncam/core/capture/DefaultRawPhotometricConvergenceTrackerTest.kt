package com.bncam.core.capture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class DefaultRawPhotometricConvergenceTrackerTest {
    @Test
    fun `allocation readiness is not photometric convergence`() {
        val tracker = DefaultRawPhotometricConvergenceTracker()
        val snapshot = tracker.observe(
            currentGeneration = 1,
            currentRoute = DefaultRawExposureRoute.MANUAL_FALLBACK,
            allocationReady = true,
            targetLuma = 0.18f,
            observedLuma = 0.0225f,
            realizationStatus = "MANUAL_EXPOSURE_REALIZED",
            aeStable = false,
            nowElapsedNs = 1_000_000L
        )
        assertTrue(snapshot.allocationReady)
        assertFalse(snapshot.photometricConverged)
        assertTrue(abs(snapshot.exposureErrorEv!! - 3f) < 0.01f)
    }

    @Test
    fun `manual fallback requires two fresh near target samples`() {
        val tracker = DefaultRawPhotometricConvergenceTracker()
        val first = tracker.observe(
            2, DefaultRawExposureRoute.MANUAL_FALLBACK, true,
            0.18f, 0.175f, "MANUAL_EXPOSURE_REALIZED", false, 10L
        )
        assertFalse(first.photometricConverged)
        val second = tracker.observe(
            2, DefaultRawExposureRoute.MANUAL_FALLBACK, true,
            0.18f, 0.178f, "MANUAL_EXPOSURE_REALIZED", false, 20L
        )
        assertTrue(second.photometricConverged)
        assertEquals(2, second.consecutiveConvergedSamples)
    }

    @Test
    fun `api36 requires realized priority plus stable camera ae`() {
        val tracker = DefaultRawPhotometricConvergenceTracker()
        tracker.observe(
            3, DefaultRawExposureRoute.API36_EXPOSURE_TIME_PRIORITY, true,
            0.18f, 0.18f, "API36_EXPOSURE_REALIZED", false, 100L
        )
        val searching = tracker.observe(
            3, DefaultRawExposureRoute.API36_EXPOSURE_TIME_PRIORITY, true,
            0.18f, 0.18f, "API36_EXPOSURE_REALIZED", false, 200L
        )
        assertFalse(searching.photometricConverged)

        tracker.observe(
            3, DefaultRawExposureRoute.API36_EXPOSURE_TIME_PRIORITY, true,
            0.18f, 0.18f, "API36_EXPOSURE_REALIZED", true, 300L
        )
        val converged = tracker.observe(
            3, DefaultRawExposureRoute.API36_EXPOSURE_TIME_PRIORITY, true,
            0.18f, 0.18f, "API36_EXPOSURE_REALIZED", true, 400L
        )
        assertTrue(converged.photometricConverged)
    }

    @Test
    fun `unrealized request cannot converge even at target luma`() {
        val tracker = DefaultRawPhotometricConvergenceTracker()
        repeat(3) {
            val snapshot = tracker.observe(
                4, DefaultRawExposureRoute.MANUAL_FALLBACK, true,
                0.18f, 0.18f, "MANUAL_EXPOSURE_NOT_REALIZED", false, it.toLong()
            )
            assertFalse(snapshot.photometricConverged)
        }
    }

    @Test
    fun `meaningful scene change timestamp is recorded on large error transition`() {
        val tracker = DefaultRawPhotometricConvergenceTracker()
        tracker.observe(
            5, DefaultRawExposureRoute.MANUAL_FALLBACK, true,
            0.18f, 0.18f, "MANUAL_EXPOSURE_REALIZED", false, 1_000L
        )
        val changed = tracker.observe(
            5, DefaultRawExposureRoute.MANUAL_FALLBACK, true,
            0.18f, 0.045f, "MANUAL_EXPOSURE_REALIZED", false, 2_000L
        )
        assertEquals(2_000L, changed.lastMeaningfulSceneChangeElapsedNs)
        assertEquals(0L, changed.timeSinceLastMeaningfulSceneChangeNs)
        assertNotNull(changed.exposureErrorEv)
    }

    @Test
    fun `new generation resets convergence evidence`() {
        val tracker = DefaultRawPhotometricConvergenceTracker()
        tracker.observe(
            6, DefaultRawExposureRoute.MANUAL_FALLBACK, true,
            0.18f, 0.18f, "MANUAL_EXPOSURE_REALIZED", false, 10L
        )
        val converged = tracker.observe(
            6, DefaultRawExposureRoute.MANUAL_FALLBACK, true,
            0.18f, 0.18f, "MANUAL_EXPOSURE_REALIZED", false, 20L
        )
        assertTrue(converged.photometricConverged)

        val reset = tracker.observe(
            7, DefaultRawExposureRoute.MANUAL_FALLBACK, true,
            0.18f, 0.18f, "MANUAL_EXPOSURE_REALIZED", false, 30L
        )
        assertFalse(reset.photometricConverged)
        assertEquals(1, reset.consecutiveConvergedSamples)
    }
}
