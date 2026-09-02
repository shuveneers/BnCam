package com.bncam.core.capture

enum class RawFlickerFrequency(val halfCyclePeriodNs: Long?) {
    NONE(null),
    HZ_50(10_000_000L),
    HZ_60(8_333_333L)
}

data class RawFlickerConstraint(
    val frequency: RawFlickerFrequency = RawFlickerFrequency.NONE,
    val source: String = "UNRESOLVED"
) {
    val periodNs: Long? get() = frequency.halfCyclePeriodNs

    /**
     * Quantize a long RAW exposure down to the nearest flicker-safe half-cycle multiple.
     * Shorter-than-one-period exposures remain unchanged because forcing them upward would violate
     * the motion ceiling and forcing them to zero is invalid.
     */
    fun constrainExposureNs(candidateNs: Long, minExposureNs: Long, maxExposureNs: Long): Long {
        val bounded = candidateNs.coerceIn(minExposureNs, maxExposureNs)
        val period = periodNs ?: return bounded
        if (period <= 0L || bounded < period) return bounded
        val multiple = bounded / period
        if (multiple <= 0L) return bounded
        val quantized = multiple * period
        return quantized.coerceIn(minExposureNs, bounded)
    }

    fun changed(candidateNs: Long, minExposureNs: Long, maxExposureNs: Long): Boolean =
        constrainExposureNs(candidateNs, minExposureNs, maxExposureNs) != candidateNs.coerceIn(minExposureNs, maxExposureNs)
}
