package com.bncam.ui.screens.capture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RawSourceFrameRateEstimatorTest {
    @Test
    fun estimatesThirtyFpsFromSensorTimestamps() {
        val estimator = RawSourceFrameRateEstimator()
        var timestamp = 1_000_000_000L
        var fps: Float? = null
        repeat(20) {
            fps = estimator.offer(timestamp)
            timestamp += 33_325_557L
        }
        assertEquals(30.0075f, fps!!, 0.01f)
    }

    @Test
    fun adaptsToExposureLimitedCadenceWithoutDeviceConstants() {
        val estimator = RawSourceFrameRateEstimator()
        var timestamp = 1_000_000_000L
        var fps: Float? = null
        repeat(20) {
            fps = estimator.offer(timestamp)
            timestamp += 59_999_959L
        }
        assertEquals(16.6667f, fps!!, 0.01f)
    }

    @Test
    fun duplicateTimestampDoesNotBecomeAnotherFrame() {
        val estimator = RawSourceFrameRateEstimator(minimumSamples = 2)
        assertNull(estimator.offer(1_000_000_000L))
        assertNull(estimator.offer(1_033_333_333L))
        val before = estimator.offer(1_066_666_666L)
        val afterDuplicate = estimator.offer(1_066_666_666L)
        assertEquals(before, afterDuplicate)
    }
}
