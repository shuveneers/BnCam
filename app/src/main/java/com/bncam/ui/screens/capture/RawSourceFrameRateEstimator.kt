package com.bncam.ui.screens.capture

import java.util.ArrayDeque

/** Estimates the active phone/camera RAW cadence from unique SENSOR_TIMESTAMP deltas. */
class RawSourceFrameRateEstimator(
    private val sampleCount: Int = 15,
    private val minimumSamples: Int = 5
) {
    private val intervalsNs = ArrayDeque<Long>()
    private var lastTimestampNs = 0L

    @Synchronized
    fun reset() {
        intervalsNs.clear()
        lastTimestampNs = 0L
    }

    @Synchronized
    fun offer(sensorTimestampNs: Long): Float? {
        if (sensorTimestampNs <= lastTimestampNs) return currentFramesPerSecond()
        val previous = lastTimestampNs
        lastTimestampNs = sensorTimestampNs
        if (previous <= 0L) return null
        val interval = sensorTimestampNs - previous
        // Reject discontinuities and impossible camera intervals without assuming a device FPS.
        if (interval !in MIN_INTERVAL_NS..MAX_INTERVAL_NS) {
            intervalsNs.clear()
            return null
        }
        intervalsNs.addLast(interval)
        while (intervalsNs.size > sampleCount) intervalsNs.removeFirst()
        return currentFramesPerSecond()
    }

    private fun currentFramesPerSecond(): Float? {
        if (intervalsNs.size < minimumSamples) return null
        val sorted = intervalsNs.sorted()
        val middle = sorted.size / 2
        val median = if (sorted.size % 2 == 0) {
            (sorted[middle - 1] + sorted[middle]) / 2L
        } else {
            sorted[middle]
        }
        return if (median > 0L) 1_000_000_000f / median else null
    }

    private companion object {
        const val MIN_INTERVAL_NS = 2_000_000L
        const val MAX_INTERVAL_NS = 1_000_000_000L
    }
}
