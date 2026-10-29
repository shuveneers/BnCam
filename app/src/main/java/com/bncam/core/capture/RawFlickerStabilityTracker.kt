package com.bncam.core.capture

enum class RawFlickerObservation {
    HZ_50,
    HZ_60,
    NONE_DETECTED,
    UNAVAILABLE
}

data class RawFlickerStabilitySnapshot(
    val stableFrequency: RawFlickerFrequency,
    val candidateFrequency: RawFlickerFrequency,
    val candidateAgeNs: Long,
    val unresolvedAgeNs: Long,
    val noFlickerAgeNs: Long,
    val fallbackActive: Boolean,
    val source: String
)

/**
 * Temporal authority for Camera2 STATISTICS_SCENE_FLICKER.
 *
 * A missing statistic is not evidence for 50 Hz or 60 Hz. We can briefly retain a previously
 * measured lock during metadata gaps, but never synthesize one from geographic assumptions.
 * The third constructor parameter retains its historical name for source compatibility; it now
 * represents a minimum age before a missing-statistic lock may be released, not a fallback timer.
 */
class RawFlickerStabilityTracker(
    private val acquireHoldNs: Long = 60_000_000L,
    private val switchHoldNs: Long = 180_000_000L,
    private val unavailableFallbackHoldNs: Long = 300_000_000L,
    private val releaseHoldNs: Long = 600_000_000L
) {
    private var stableFrequency = RawFlickerFrequency.NONE
    private var candidateFrequency = RawFlickerFrequency.NONE
    private var candidateSinceNs = 0L
    private var unresolvedSinceNs = 0L
    private var noFlickerSinceNs = 0L

    @Synchronized
    fun observe(
        observation: RawFlickerObservation,
        nowNs: Long = System.nanoTime()
    ): RawFlickerStabilitySnapshot {
        val now = nowNs.coerceAtLeast(0L)
        when (observation) {
            RawFlickerObservation.HZ_50 -> observeFrequency(RawFlickerFrequency.HZ_50, now)
            RawFlickerObservation.HZ_60 -> observeFrequency(RawFlickerFrequency.HZ_60, now)
            RawFlickerObservation.NONE_DETECTED -> observeNoFlicker(now)
            RawFlickerObservation.UNAVAILABLE -> observeUnavailable(now)
        }
        return snapshot(now)
    }

    @Synchronized
    fun reset(): RawFlickerStabilitySnapshot {
        stableFrequency = RawFlickerFrequency.NONE
        candidateFrequency = RawFlickerFrequency.NONE
        candidateSinceNs = 0L
        unresolvedSinceNs = 0L
        noFlickerSinceNs = 0L
        return snapshot(0L)
    }

    @Synchronized
    fun current(nowNs: Long = System.nanoTime()): RawFlickerStabilitySnapshot =
        snapshot(nowNs.coerceAtLeast(0L))

    private fun observeFrequency(observed: RawFlickerFrequency, now: Long) {
        unresolvedSinceNs = 0L
        noFlickerSinceNs = 0L
        if (stableFrequency == observed) {
            clearCandidate()
            return
        }
        if (candidateFrequency != observed) {
            candidateFrequency = observed
            candidateSinceNs = now.coerceAtLeast(1L)
            return
        }
        val requiredHold = if (stableFrequency == RawFlickerFrequency.NONE) acquireHoldNs else switchHoldNs
        if (elapsed(now, candidateSinceNs) >= requiredHold) {
            stableFrequency = observed
            clearCandidate()
        }
    }

    private fun observeNoFlicker(now: Long) {
        unresolvedSinceNs = 0L
        clearCandidate()
        if (noFlickerSinceNs == 0L) noFlickerSinceNs = now.coerceAtLeast(1L)
        if (stableFrequency != RawFlickerFrequency.NONE &&
            elapsed(now, noFlickerSinceNs) >= releaseHoldNs
        ) stableFrequency = RawFlickerFrequency.NONE
    }

    private fun observeUnavailable(now: Long) {
        noFlickerSinceNs = 0L
        clearCandidate()
        if (unresolvedSinceNs == 0L) unresolvedSinceNs = now.coerceAtLeast(1L)
        // Once metadata has remained unavailable, the last real frequency can no longer be
        // asserted. Returning UNKNOWN is safer than applying the wrong raster indefinitely.
        if (stableFrequency != RawFlickerFrequency.NONE &&
            elapsed(now, unresolvedSinceNs) >= maxOf(unavailableFallbackHoldNs, releaseHoldNs)
        ) stableFrequency = RawFlickerFrequency.NONE
    }

    private fun clearCandidate() {
        candidateFrequency = RawFlickerFrequency.NONE
        candidateSinceNs = 0L
    }

    private fun snapshot(nowNs: Long): RawFlickerStabilitySnapshot {
        val source = when {
            unresolvedSinceNs > 0L && stableFrequency != RawFlickerFrequency.NONE ->
                "AUTO_HELD_LAST_SCENE_FLICKER_${stableFrequency.name}_STATISTIC_UNAVAILABLE"
            stableFrequency == RawFlickerFrequency.HZ_50 -> "AUTO_STABLE_SCENE_FLICKER_50HZ"
            stableFrequency == RawFlickerFrequency.HZ_60 -> "AUTO_STABLE_SCENE_FLICKER_60HZ"
            candidateFrequency == RawFlickerFrequency.HZ_50 -> "AUTO_ACQUIRING_SCENE_FLICKER_50HZ"
            candidateFrequency == RawFlickerFrequency.HZ_60 -> "AUTO_ACQUIRING_SCENE_FLICKER_60HZ"
            noFlickerSinceNs > 0L -> "AUTO_SCENE_FLICKER_NONE"
            unresolvedSinceNs > 0L -> "AUTO_SCENE_FLICKER_STATISTIC_UNAVAILABLE"
            else -> "AUTO_SCENE_FLICKER_UNRESOLVED"
        }
        return RawFlickerStabilitySnapshot(
            stableFrequency = stableFrequency,
            candidateFrequency = candidateFrequency,
            candidateAgeNs = if (candidateSinceNs > 0L) elapsed(nowNs, candidateSinceNs) else 0L,
            unresolvedAgeNs = if (unresolvedSinceNs > 0L) elapsed(nowNs, unresolvedSinceNs) else 0L,
            noFlickerAgeNs = if (noFlickerSinceNs > 0L) elapsed(nowNs, noFlickerSinceNs) else 0L,
            fallbackActive = false,
            source = source
        )
    }

    private fun elapsed(now: Long, since: Long): Long =
        if (since <= 0L || now <= since) 0L else now - since
}
