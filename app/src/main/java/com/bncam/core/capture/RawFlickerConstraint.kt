package com.bncam.core.capture

/**
 * Frequency of the light-output modulation relevant to exposure integration.
 * 50 Hz mains produces a 100 Hz light period; 60 Hz mains produces a 120 Hz light period.
 */
enum class RawFlickerFrequency(val lightPeriodsPerSecond: Long?) {
    NONE(null),
    HZ_50(100L),
    HZ_60(120L);

    /** Compatibility alias for existing diagnostics/tests; still represents one light half-cycle. */
    val halfCyclePeriodNs: Long?
        get() = lightPeriodsPerSecond?.let { rate ->
            (1_000_000_000L + rate / 2L) / rate
        }
}

/**
 * Flicker-safe sensor timing contract. The frequency is already temporally stabilized by the
 * Camera2 owner; this class only performs deterministic shutter timing math.
 */
data class RawFlickerConstraint(
    val frequency: RawFlickerFrequency = RawFlickerFrequency.NONE,
    val source: String = "UNRESOLVED",
    val fallbackActive: Boolean = false
) {
    /** Rationally rounded light-period duration. 120 Hz does not accumulate 8,333,333 ns drift. */
    val periodNs: Long?
        get() = frequency.lightPeriodsPerSecond?.let { durationForPeriods(1L, it) }

    /**
     * Align exposures of at least one light period downward to an integer number of complete light
     * periods. Truly shorter shutters remain untouched: motion safety is stronger than flicker
     * avoidance when a complete light period physically does not fit.
     */
    fun constrainExposureNs(candidateNs: Long, minExposureNs: Long, maxExposureNs: Long): Long {
        val bounded = candidateNs.coerceIn(minExposureNs, maxExposureNs)
        val rate = frequency.lightPeriodsPerSecond ?: return bounded
        val first = durationForPeriods(1L, rate)
        if (bounded + ALIGNMENT_TOLERANCE_NS < first) return bounded

        val periods = ((bounded + ALIGNMENT_TOLERANCE_NS) * rate / NANOS_PER_SECOND)
            .coerceAtLeast(1L)
        val aligned = durationForPeriods(periods, rate)
        if (aligned > maxExposureNs + ALIGNMENT_TOLERANCE_NS) return bounded
        return aligned.coerceIn(minExposureNs, maxExposureNs)
    }

    /** Closest complete-period shutter that does not exceed the physical ceiling. */
    fun closestAlignedExposureNs(
        candidateNs: Long,
        maxAllowedNs: Long,
        minExposureNs: Long,
        maxExposureNs: Long
    ): Long {
        val rate = frequency.lightPeriodsPerSecond
            ?: return candidateNs.coerceIn(minExposureNs, minOf(maxAllowedNs, maxExposureNs))
        val ceiling = minOf(maxAllowedNs, maxExposureNs).coerceAtLeast(minExposureNs)
        val first = durationForPeriods(1L, rate)
        if (ceiling + ALIGNMENT_TOLERANCE_NS < first) {
            return candidateNs.coerceIn(minExposureNs, ceiling)
        }

        val boundedCandidate = candidateNs.coerceIn(minExposureNs, ceiling)
        val floorPeriods = (boundedCandidate * rate / NANOS_PER_SECOND).coerceAtLeast(1L)
        val ceilPeriods = ((boundedCandidate * rate + NANOS_PER_SECOND - 1L) / NANOS_PER_SECOND)
            .coerceAtLeast(1L)
        val floorValue = durationForPeriods(floorPeriods, rate)
        val ceilValue = durationForPeriods(ceilPeriods, rate)
        val candidates = listOf(floorValue, ceilValue)
            .filter { it in minExposureNs..ceiling }
        return candidates.minByOrNull { kotlin.math.abs(it - boundedCandidate) }
            ?: constrainExposureNs(boundedCandidate, minExposureNs, ceiling)
    }

    fun isExposureAligned(exposureNs: Long): Boolean {
        val rate = frequency.lightPeriodsPerSecond ?: return true
        if (exposureNs <= 0L) return false
        val nearestPeriods = ((exposureNs * rate + NANOS_PER_SECOND / 2L) / NANOS_PER_SECOND)
            .coerceAtLeast(1L)
        val nearest = durationForPeriods(nearestPeriods, rate)
        return kotlin.math.abs(nearest - exposureNs) <= ALIGNMENT_TOLERANCE_NS
    }

    /** Advance by at most one complete light period while staying within the physical target. */
    fun nextLongerAlignedExposureNs(
        currentNs: Long,
        desiredNs: Long,
        minExposureNs: Long,
        maxExposureNs: Long
    ): Long {
        val rate = frequency.lightPeriodsPerSecond
            ?: return desiredNs.coerceIn(minExposureNs, maxExposureNs)
        val boundedDesired = desiredNs.coerceIn(minExposureNs, maxExposureNs)
        if (!isExposureAligned(currentNs)) {
            return closestAlignedExposureNs(
                candidateNs = currentNs,
                maxAllowedNs = boundedDesired,
                minExposureNs = minExposureNs,
                maxExposureNs = maxExposureNs
            )
        }
        val desiredPeriods = ((boundedDesired + ALIGNMENT_TOLERANCE_NS) * rate / NANOS_PER_SECOND)
            .coerceAtLeast(1L)
        val currentPeriods = ((currentNs * rate + NANOS_PER_SECOND / 2L) / NANOS_PER_SECOND)
            .coerceAtLeast(1L)
        val nextPeriods = minOf(currentPeriods + 1L, desiredPeriods)
        val next = durationForPeriods(nextPeriods, rate)
        return if (next <= boundedDesired + ALIGNMENT_TOLERANCE_NS) {
            next.coerceIn(minExposureNs, maxExposureNs)
        } else {
            currentNs.coerceIn(minExposureNs, maxExposureNs)
        }
    }

    /**
     * Frame cadence is not part of the flicker raster. Flicker suppression is obtained by the
     * exposure-integration interval and Camera2 anti-banding; forcing frame duration to four light
     * periods needlessly limits 50 Hz acquisition to 25 fps and 60 Hz acquisition to 30 fps.
     *
     * Request the shortest frame duration admitted by the caller. Camera2/HAL remains authoritative
     * for the configured stream's physical minimum frame duration and exposure/readout overhead.
     */
    fun stableFrameDurationNs(
        exposureNs: Long,
        minFrameDurationNs: Long = 0L
    ): Long = maxOf(exposureNs, minFrameDurationNs, 1L)

    fun changed(candidateNs: Long, minExposureNs: Long, maxExposureNs: Long): Boolean =
        constrainExposureNs(candidateNs, minExposureNs, maxExposureNs) !=
            candidateNs.coerceIn(minExposureNs, maxExposureNs)

    companion object {
        private const val NANOS_PER_SECOND = 1_000_000_000L
        private const val ALIGNMENT_TOLERANCE_NS = 100_000L

        private fun durationForPeriods(periods: Long, rate: Long): Long =
            (periods * NANOS_PER_SECOND + rate / 2L) / rate
    }
}
