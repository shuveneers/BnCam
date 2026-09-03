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
 * - Real 50/60 Hz evidence needs a short hold before acquisition.
 * - Switching an established real lock needs stronger opposite evidence.
 * - One or a few NONE frames cannot tear a lock down; sustained NONE can.
 * - Only a genuinely unavailable statistic may fall back to 50 Hz. An explicit Camera2 NONE never
 *   fabricates a mains frequency. Real 60 Hz evidence replaces a 50 Hz fallback quickly.
 */
class RawFlickerStabilityTracker(
    private val acquireHoldNs: Long = 60_000_000L,
    private val switchHoldNs: Long = 180_000_000L,
    private val unavailableFallbackHoldNs: Long = 300_000_000L,
    private val releaseHoldNs: Long = 600_000_000L
) {
    private var stableFrequency = RawFlickerFrequency.NONE
    private var stableIsFallback = false
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
        stableIsFallback = false
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
        if (stableFrequency == observed && !stableIsFallback) {
            clearCandidate()
            return
        }
        if (candidateFrequency != observed) {
            candidateFrequency = observed
            candidateSinceNs = now.coerceAtLeast(1L)
            return
        }
        val requiredHold = if (stableFrequency == RawFlickerFrequency.NONE || stableIsFallback) {
            acquireHoldNs
        } else {
            switchHoldNs
        }
        if (elapsed(now, candidateSinceNs) >= requiredHold) {
            stableFrequency = observed
            stableIsFallback = false
            clearCandidate()
        }
    }

    private fun observeNoFlicker(now: Long) {
        unresolvedSinceNs = 0L
        clearCandidate()
        if (noFlickerSinceNs == 0L) noFlickerSinceNs = now.coerceAtLeast(1L)
        if (stableIsFallback) {
            // A synthetic 50 Hz fallback has no authority once Camera2 explicitly reports NONE.
            stableFrequency = RawFlickerFrequency.NONE
            stableIsFallback = false
            return
        }
        if (stableFrequency != RawFlickerFrequency.NONE &&
            elapsed(now, noFlickerSinceNs) >= releaseHoldNs
        ) {
            stableFrequency = RawFlickerFrequency.NONE
            stableIsFallback = false
        }
    }

    private fun observeUnavailable(now: Long) {
        noFlickerSinceNs = 0L
        clearCandidate()
        if (unresolvedSinceNs == 0L) unresolvedSinceNs = now.coerceAtLeast(1L)
        if (stableFrequency == RawFlickerFrequency.NONE &&
            elapsed(now, unresolvedSinceNs) >= unavailableFallbackHoldNs
        ) {
            stableFrequency = RawFlickerFrequency.HZ_50
            stableIsFallback = true
        }
    }

    private fun clearCandidate() {
        candidateFrequency = RawFlickerFrequency.NONE
        candidateSinceNs = 0L
    }

    private fun snapshot(nowNs: Long): RawFlickerStabilitySnapshot {
        val source = when {
            stableIsFallback -> "AUTO_FALLBACK_50HZ_STATISTIC_UNAVAILABLE"
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
            fallbackActive = stableIsFallback,
            source = source
        )
    }

    private fun elapsed(now: Long, since: Long): Long =
        if (since <= 0L || now <= since) 0L else now - since
}
