package com.bncam.core.capture

import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Conservative RAW-domain rejection of a *spatially coherent* illumination fluctuation.
 *
 * This does not measure mains frequency, does not estimate motion, and never grants permission
 * for a longer exposure. It prevents a scene-wide light pulse from being labelled subject motion.
 * The caller retains motion ceilings from its independent estimator.
 */
internal object RawIlluminationChangePolicy {
    private const val WIDTH = 64
    private const val HEIGHT = 48
    private const val REGIONS = 12
    private const val REGION_SIZE = 256

    fun consistentGlobalShift(
        previous: DoubleArray,
        current: DoubleArray,
        exposureRatio: Double,
        previousSlope: Double,
        previousOffset: Double,
        currentSlope: Double,
        currentOffset: Double,
        previousQuantizationVariance: Double,
        currentQuantizationVariance: Double
    ): Boolean {
        if (previous.size != WIDTH * HEIGHT || current.size != previous.size ||
            !exposureRatio.isFinite() || exposureRatio !in 0.25..4.0 ||
            listOf(previousSlope, previousOffset, currentSlope, currentOffset,
                previousQuantizationVariance, currentQuantizationVariance).any {
                    !it.isFinite() || it < 0.0
                }) return false

        // Spatial medians, not a single global mean: a moving bright subject cannot masquerade
        // as scene-wide flicker by dominating one part of the grid.
        val byRegion = Array(REGIONS) { DoubleArray(REGION_SIZE) }
        val counts = IntArray(REGIONS)
        var expectedTotal = 0.0
        var actualTotal = 0.0
        for (i in previous.indices) {
            val expected = previous[i] * exposureRatio
            val actual = current[i]
            if (!expected.isFinite() || !actual.isFinite() ||
                expected !in 0.04..0.75 || actual !in 0.025..0.85) continue
            val localRatio = actual / expected
            if (!localRatio.isFinite() || localRatio !in 0.5..1.5) continue
            expectedTotal += expected
            actualTotal += actual
            val region = (i / WIDTH / 16) * 4 + (i % WIDTH / 16)
            byRegion[region][counts[region]++] = localRatio
        }
        // Normal exposure-matched frames take the fast path; avoid twelve sorts at preview FPS.
        if (expectedTotal <= 0.0 || abs(actualTotal / expectedTotal - 1.0) < 0.025) return false
        val valid = ArrayList<Double>(REGIONS)
        for (region in 0 until REGIONS) {
            if (counts[region] < 48) continue
            val sorted = byRegion[region].copyOf(counts[region])
            sorted.sort()
            val median = sorted[sorted.size / 2]
            valid.add(median)
        }
        if (valid.size < 9 || counts.sum() < 900) return false
        valid.sort()
        val gain = valid[valid.size / 2]
        // Only a clearly resolved modest fluctuation qualifies. Other changes remain motion or
        // uncertainty; this deliberately does not suppress subject motion on poorly lit frames.
        if (abs(gain - 1.0) !in 0.04..0.25) return false
        if (valid.count { abs(it / gain - 1.0) <= 0.025 } < 9) return false

        var eligible = 0
        var changedBefore = 0
        var changedAfter = 0
        for (i in previous.indices) {
            val expected = previous[i] * exposureRatio
            val actual = current[i]
            if (!expected.isFinite() || !actual.isFinite() ||
                expected !in 0.04..0.75 || actual !in 0.025..0.85) continue
            val variance = ((currentSlope * actual + currentOffset + currentQuantizationVariance) +
                exposureRatio * exposureRatio * gain * gain *
                (previousSlope * previous[i] + previousOffset + previousQuantizationVariance)) / 4.0
            if (!variance.isFinite() || variance <= 0.0) continue
            val threshold = 3.0 * sqrt(variance)
            if (abs(actual - expected) > threshold) changedBefore++
            if (abs(actual - expected * gain) > threshold) changedAfter++
            eligible++
        }
        return eligible >= 900 && changedBefore >= eligible * 0.12 &&
            changedAfter <= eligible * 0.02
    }
}
